(ns mach.pack.alpha.impl.vfs
  (:require
    [clojure.java.io :as io]
    [clojure.walk :refer [postwalk]]
    [clojure.string :as string]
    [mach.pack.alpha.impl.elodin
     :refer [path-seq->str
             path->path-seq]
     :as elodin])
  (:import
    [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]
    [java.util.zip ZipEntry ZipOutputStream]))

(defn- post-order-tree-seq
  [branch? children root]
  (let [walk (fn walk [n]
               (if (branch? n)
                 (concat (mapcat walk (children n)) [n])
                 [n]))]
    (walk root)))

(defn- lazy-post-order-tree-seq
  [branch? children root]
  (let [walk (fn walk [n]
               (lazy-seq
                 (if (branch? n)
                   (into (mapcat walk (children n)) [n])
                   [n])))]
    (walk root)))

(defn- tree-height
  [branch? children root]
  (let [walk (fn walk [n height]
               (if (branch? n)
                 (apply max
                        (inc height)
                        (map walk
                             (children n)
                             (repeat (inc height))))
                 height))]
    (walk root 0)))

(defn- process-tree
  [branch? children f root]
  (let [walk (fn walk [n height]
               (if (branch? n)
                 (f n
                    (mapv walk (children n) (repeat (inc height)))
                    height)
                 (f n nil height)))]
    (walk root 0)))

(defn- create-thread
  [name f]
  (let [t (doto (Thread. f)
            (.setName name)
            (.setDaemon false)
            (.start))]
    t))

(defn- process-height
  [height f]
  (let [in (java.util.concurrent.LinkedBlockingQueue.)]
    {:t (create-thread (str "process-height-" height)
                       (fn []
                         (loop [x (.take in)]
                           (when (not= x ::end)
                             (f x)
                             (recur (.take in))))))
     :in in}))

(defn- iterate-tree
  [branch? children processors g]
  (process-tree branch? children
                (fn [node children height]
                  (.put (:in (get processors height))
                        {:node node
                         :children children
                         :height height})
                  node)
                g))

(defn- create-processors
  [tree-height f]
  (mapv #(process-height % f) (range (inc tree-height))))

(defn- stop-processors
  [processors]
  (doseq [p processors]
    (.put (:in p) ::end))
  (doseq [p processors]
    (.join (:t p))))

(defn- write! [stream in]
  (let [buf (byte-array 1024)]
    (with-open [in in]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write stream buf 0 n)
          (recur (.read in buf)))))))

(defn- add-output
  [path]
  (if (:paths path)
    (let [out (java.io.PipedOutputStream.)
          in (java.io.PipedInputStream. out)]
      (assoc path
             :input in
             :output {:stream out
                      :type :jar}))
    path))

(def ^:private path? map?)

(defmulti write-output
  "Writes children to a path"
  (fn [path children]
    (:type (:output path))))

(defn- prepare-path
  [path]
  (string/join "/" (map #(string/replace % #"\\" "/") path)))

(defn set-last-modified
  [entry last-modified]
  (if last-modified
    (doto entry (.setTime last-modified))
    entry))

(defn- create-manifest [main ext-attrs]
  (let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))
    manifest))

(defn- create-parents
  [out path-seq]
  (doseq [dir (map path-seq->str (elodin/path-seq-parents path-seq))]
    (try
      (.putNextEntry out (ZipEntry. dir))
      (catch java.util.zip.ZipException e
        ;; Ignore duplicate entry exceptions
        (when-not (re-matches #"duplicate entry:.*" (.getMessage e))
          (throw e)))
      (finally
        (.closeEntry out)))))

(defmethod write-output :jar
  [path children]
  (with-open [out (if-let [manifest (:manifest (:output path))]
                    (JarOutputStream. (get-in path [:output :stream])
                                      (create-manifest
                                        (:main manifest)
                                        (:ext-attrs manifest)))
                    (JarOutputStream. (get-in path [:output :stream])))]
    (doseq [child children]
      (try
        (create-parents out (:path child))
        (doto out
          ;; TODO: Make the jarentry code more robust around windows paths
          ;;TODO: Looked into this, \ is never valid in a zip path, which means java.nio.Path is unusable for this case.
          ; http://www.pkware.com/documents/casestudies/APPNOTE.TXT spec here
          ; Not much mention is made of backslashes. Tests with `zip` indicated that \ is preserved as part of the filename.
          ; Forward slash is not a valid character in a windows filename, nor Unix.
          ; I think the ultimate solution here is to convert '\' to '/' as we do now. I think it covers the common bases.
          ; It's unclear to me what is expected by having \ in a filename in a zip file would mean, but could be supported if someone articulated it.
          (.putNextEntry (-> (:path child)
                             (prepare-path)
                             (JarEntry.)
                             (set-last-modified (:last-modified child))))
          (write! (:input child))
          (.closeEntry))
        (catch NullPointerException e
          (println "NPE while write! on:" (pr-str child))
          (throw e))))))

(defmethod write-output :zip
  [path children]
  (with-open [out (ZipOutputStream. (get-in path [:output :stream]))]
    (doseq [child children]
      (create-parents out (:path child))
      (doto out
        (.putNextEntry (-> (:path child)
                           (prepare-path)
                           (ZipEntry.)
                           (set-last-modified (:last-modified child))))
        (write! (:input child))
        (.closeEntry)))))

(defmethod write-output :dir
  [path children]
  (when-let [root (get-in path [:output :root])]
    (doseq [child children]
      (let [out (io/file root (path-seq->str (:path child)))]
        (io/make-parents out)
        (io/copy (:input child) out)))))

(defn write-vfs
  [output paths]
  (let [branch? path?
        children :paths

        g {:paths (postwalk #(if (path? %) (add-output %) %) paths)
           :output output}]

    (let [processors
          (create-processors
            (tree-height branch? children g)
            (fn [{:keys [node height children]}]
              (try
                (when (:output node)
                  (cond
                    (:paths node)
                    (write-output node children)
                    
                    (:input node)
                    (with-open [o (get-in node [:output :stream])]
                      (write! o (:input node)))))
                (catch Exception e
                  #_(println (str "An exception occurred" (pr-str node height children)))
                  (throw e)))))]
      (iterate-tree branch? children processors g)
      ;; Work is not complete until all processor threads have finished
      (stop-processors processors))))

(defn files-path
  [files dir]
  (map (fn [file]
         {:path (path->path-seq
                  (.relativize (.toPath dir)
                               (.toPath file)))
          :input (io/input-stream file)
          :last-modified (.lastModified file)})
       (filter (memfn isFile) files)))
