(ns io.dominic.pack.impl.vfs
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [io.dominic.pack.impl.elodin :refer [path-seq->str path->path-seq] :as elodin])
  (:import
    [java.io ByteArrayInputStream ByteArrayOutputStream]
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]
    [java.util.concurrent ConcurrentLinkedQueue Executors LinkedBlockingQueue TimeUnit]
    [java.util.jar Manifest Attributes$Name]
    [java.util.zip ZipEntry ZipException]
    [org.apache.commons.compress.archivers.zip JarMarker ScatterZipOutputStream ZipArchiveEntry ZipArchiveEntryRequest ZipArchiveOutputStream]
    [org.apache.commons.compress.parallel InputStreamSupplier]))

(defmulti write-output
  "Writes children to a path"
  (fn [path _children]
    (:type (:output path))))

(defn- prepare-path
  [path]
  (string/join "/" (map #(string/replace % #"\\" "/") path)))

(defn set-last-modified
  [entry last-modified]
  (if last-modified
    (doto entry (.setTime last-modified))
    entry))

(defn- create-manifest
  [main ext-attrs]
  (let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))
    manifest))

(defn- manifest-child
  [manifest]
  (let [bytes (ByteArrayOutputStream.)]
    (.write (create-manifest (:main manifest) (:ext-attrs manifest)) bytes)
    (let [data (.toByteArray bytes)]
      {:path ["META-INF" "MANIFEST.MF"]
       :input #(ByteArrayInputStream. data)})))

(defn- archive-entries
  [children manifest]
  (let [child (when manifest (manifest-child manifest))
        name (when child (prepare-path (:path child)))
        initial (if child
                  [#{name} [[name (:input child) nil]]]
                  [#{} []])]
    (second
      (reduce (fn [[seen entries] [name input last-modified directory?]]
                (if (contains? seen name)
                  (if directory?
                    [seen entries]
                    (throw (ZipException. (str "duplicate entry: " name))))
                  [(conj seen name) (conj entries [name input last-modified])]))
              initial
              (mapcat (fn [child]
                        (concat
                          (map (fn [parent]
                                 [(str (prepare-path parent) "/")
                                  #(ByteArrayInputStream. (byte-array 0)) nil true])
                               (elodin/path-seq-parents (:path child)))
                          [[(prepare-path (:path child)) (:input child) (:last-modified child) false]]))
                      children)))))

(defn- close-scatter
  [streams cleanup]
  (let [failure (reduce (fn [failure stream]
                          (try
                            (.close ^ScatterZipOutputStream stream)
                            failure
                            (catch Throwable t
                              (if failure
                                (do (.addSuppressed ^Throwable failure t) failure)
                                t))))
                        nil streams)]
    (try
      (cleanup)
      (catch Throwable t
        (if failure
          (.addSuppressed ^Throwable failure t)
          (throw t))))
    (when failure (throw failure))))

(defn- stop-scatter
  [executor streams cleanup]
  (.shutdownNow executor)
  (let [finish #(close-scatter streams cleanup)
        defer #(doto (Thread. (fn []
                                (try
                                  (.awaitTermination executor Long/MAX_VALUE TimeUnit/NANOSECONDS)
                                  (finish)
                                  (catch Throwable _)))
                              "pack-scatter-cleanup")
                 (.setDaemon true)
                 (.start))]
    (if (try
          (.awaitTermination executor 1 TimeUnit/SECONDS)
          (catch InterruptedException e
            (defer)
            (.interrupt (Thread/currentThread))
            (throw e)))
      (finish)
      (defer))))

(defn- scatter-stream
  [streams]
  (let [file (Files/createTempFile "pack-scatter-" ".zip" (make-array FileAttribute 0))
        stream (try
                 (ScatterZipOutputStream/pathBased file)
                 (catch Throwable t
                   (try (Files/deleteIfExists file)
                        (catch Throwable e (.addSuppressed t e)))
                   (throw t)))]
    (.add streams stream)
    stream))

(defn- write-scattered
  [out entries jar? executor streams]
  (let [completions (LinkedBlockingQueue.)
        failure (atom nil)
        local-stream (proxy [ThreadLocal] []
                       (initialValue [] (scatter-stream streams)))
        ordered (object-array (count entries))]
    (doseq [[index [name input last-modified]] (map-indexed vector entries)]
      (let [entry (doto (ZipArchiveEntry. ^String name)
                    (.setMethod ZipEntry/DEFLATED)
                    (set-last-modified last-modified))
            _ (when (and jar? (zero? index))
                (.addAsFirstExtraField entry (JarMarker/getInstance)))
            request (ZipArchiveEntryRequest/createZipArchiveEntryRequest
                      entry (reify InputStreamSupplier (get [_] (input))))]
        (when-let [t @failure] (throw t))
        (.submit executor ^Runnable
                 (fn []
                   (try
                     (when-not @failure
                       (let [stream (.get local-stream)]
                         (.addArchiveEntry ^ScatterZipOutputStream stream request)
                         (.offer completions [index stream nil])))
                     (catch Throwable t
                       (reset! failure t)
                       (.offer completions [index nil t])))))))
    (dotimes [_ (count entries)]
      (let [[index stream error] (.take completions)]
        (when error (throw error))
        (aset ordered index stream)))
    (doseq [stream ordered]
      (.writeNextZipEntry (.zipEntryWriter ^ScatterZipOutputStream stream) out))))

(defn- with-resource
  [resource f]
  (let [result (try
                 (f resource)
                 (catch Throwable t
                   (try (.close ^java.io.Closeable resource)
                        (catch Throwable e (.addSuppressed t e)))
                   (throw t)))]
    (.close ^java.io.Closeable resource)
    result))

(defn- write-archive
  [path children manifest]
  (let [cleanup (or (:cleanup path) (fn []))
        out (try
              (ZipArchiveOutputStream. (get-in path [:output :stream]))
              (catch Throwable t
                (try (cleanup) (catch Throwable e (.addSuppressed t e)))
                (throw t)))]
    (with-resource out
      (fn [out]
        (let [[entries executor streams]
              (try
                (let [entries (archive-entries children manifest)
                      streams (ConcurrentLinkedQueue.)
                      executor (Executors/newFixedThreadPool
                                 (max 1 (min (count entries) (.availableProcessors (Runtime/getRuntime))))
                                 (reify java.util.concurrent.ThreadFactory
                                   (newThread [_ task]
                                     (doto (Thread. task "pack-scatter-worker") (.setDaemon true)))))]
                  [entries executor streams])
                (catch Throwable t
                  (try (cleanup) (catch Throwable e (.addSuppressed t e)))
                  (throw t)))]
          (try
            (write-scattered out entries (= :jar (get-in path [:output :type])) executor streams)
            (catch Throwable t
              (try (stop-scatter executor streams cleanup)
                   (catch Throwable e (.addSuppressed t e)))
              (when (instance? InterruptedException t)
                (.interrupt (Thread/currentThread)))
              (throw t)))
          (.shutdown executor)
          (close-scatter streams cleanup))))))

(defmethod write-output :jar
  [path children]
  (write-archive path children (get-in path [:output :manifest])))

(defmethod write-output :zip
  [path children]
  (write-archive path children nil))

(defmethod write-output :dir
  [path children]
  (when-let [root (get-in path [:output :root])]
    (doseq [child children]
      (let [out (io/file root (path-seq->str (:path child)))]
        (io/make-parents out)
        (with-open [in ((:input child))]
          (io/copy in out))))))

(declare write-vfs)

(defn- materialize-child
  [child]
  (if (:paths child)
    (let [file (Files/createTempFile "pack-nested-" ".jar" (make-array FileAttribute 0))]
      (try
        (with-open [out (Files/newOutputStream file (make-array java.nio.file.OpenOption 0))]
          (write-vfs {:type :jar :stream out} (:paths child)))
        (assoc child :input #(Files/newInputStream file (make-array java.nio.file.OpenOption 0))
                     ::scratch file)
        (catch Throwable t
          (try (Files/deleteIfExists file)
               (catch Throwable cleanup (.addSuppressed t cleanup)))
          (throw t))))
    child))

(defn- delete-scratch
  [children]
  (when-let [failure
             (reduce (fn [failure child]
                       (try
                         (when-let [file (::scratch child)] (Files/deleteIfExists file))
                         failure
                         (catch Throwable t
                           (if failure
                             (do (.addSuppressed failure t) failure)
                             t))))
                     nil children)]
    (throw failure)))

(defn write-vfs
  [output paths]
  (let [children (loop [remaining paths children []]
                   (let [[remaining child]
                         (try
                           (when-let [remaining (seq remaining)]
                             [remaining (materialize-child (first remaining))])
                           (catch Throwable t
                             (try (delete-scratch children)
                                  (catch Throwable e (.addSuppressed t e)))
                             (when (#{:zip :jar} (:type output))
                               (try (.close ^java.io.Closeable (:stream output))
                                    (catch Throwable e (.addSuppressed t e))))
                             (throw t)))]
                     (if remaining
                       (recur (lazy-seq (rest remaining)) (conj children child))
                       children)))
        cleanup #(delete-scratch children)]
    (if (#{:zip :jar} (:type output))
      (write-output {:output output :cleanup cleanup} children)
      (with-open [_resource (reify java.io.Closeable (close [_] (cleanup)))]
        (write-output {:output output} children)))))

(defn files-path
  [files dir]
  (map (fn [file]
         {:path (path->path-seq
                  (.relativize (.toPath dir)
                               (.toPath file)))
          :input #(io/input-stream file)
          :last-modified (.lastModified file)})
       (filter (memfn isFile) files)))
