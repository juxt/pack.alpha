(ns mach.pack.alpha.capsule
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.deps.alpha :as tools.deps]
    [clojure.tools.deps.alpha.script.make-classpath]
    [clojure.tools.deps.alpha.reader :as tools.deps.reader]
    [me.raynes.fs :as fs])
  (:import
    [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]
    [java.util.zip ZipException]
    [javax.tools ToolProvider DiagnosticCollector Diagnostic$Kind]
    [java.util Arrays]
    [java.io File]
    [java.nio.file Paths Path Files]
    [java.nio.file.attribute FileAttribute]))

;; code adapted from boot
(defn- create-manifest [main ext-attrs]
  (let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))
    manifest))

(defn- write! [stream file]
  (let [buf (byte-array 1024)]
    (with-open [in (io/input-stream file)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write stream buf 0 n)
          (recur (.read in buf)))))))

(defn dupe? [t]
  (and (instance? ZipException t)
       (.startsWith (.getMessage t) "duplicate entry:")))

(defprotocol ILastModified
  (last-modified [self]))

(extend-protocol ILastModified
  java.io.File
  (last-modified [self] (.lastModified self))
  java.net.URL
  (last-modified [self] (.getLastModified (.openConnection self))))

(defn jarentry [path f & [dir?]]
  (doto (JarEntry. (str (.replaceAll path "\\\\" "/") (when dir? "/")))
    (.setTime (last-modified f))))

(defn spit-jar! [jarpath files attr main]
  (let [manifest  (create-manifest main attr)
        jarfile   (io/file jarpath)
        dirs      (atom #{})
        parents*  #(iterate (comp (memfn getParent) io/file) %)
        parents   #(->> % parents* (drop 1)
                        (take-while (complement empty?))
                        (remove (partial contains? @dirs)))]
    (io/make-parents jarfile)
    (with-open [s (JarOutputStream. (io/output-stream jarfile) manifest)]
      (doseq [[jarpath srcpath] files]
        (let [e (jarentry jarpath srcpath)]
          (try
            (doseq [d (parents jarpath) :let [f (io/file d)]]
              (swap! dirs conj d)
              (doto s (.putNextEntry (jarentry d f true)) .closeEntry))
            (doto s (.putNextEntry e) (write! (io/input-stream srcpath)) .closeEntry)
            (catch Throwable t
              (if-not (dupe? t) (throw t) (println " warning: %s\n" (.getMessage t))))))))))

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn- deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory prefix
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))

(defn split-classpath-string
  [classpath-string]
  (string/split
    classpath-string
    ;; re-pattern should be safe given the characters that can be separators, but could be safer
    (re-pattern File/pathSeparator)))

(defn- signature
  [algorithm]
  (javax.xml.bind.DatatypeConverter/printHexBinary (.digest algorithm)))

(defn- consume-input-stream
  [input-stream]
  (let [buf-n 2048
        buffer (byte-array buf-n)]
    (while (> (.read input-stream buffer 0 buf-n) 0))))

(defn sha256
  [file]
  (.getMessageDigest
    (doto
      (java.security.DigestInputStream. (io/input-stream file)
                                        (java.security.MessageDigest/getInstance "SHA-256"))
      consume-input-stream)))

(defn paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

(defn classpath-string->jar
  [classpath-string jar-location application-id application-version]
  (let [classpath
        (map io/file (split-classpath-string classpath-string))]
    (spit-jar!
      jar-location
      (concat ;; directories on the classpath
              (mapcat
                (fn [cp-dir]
                  (let [cp-dir-p (.toPath cp-dir)]
                    (map
                      (juxt #(str (.relativize cp-dir-p (.toPath %)))
                            io/file)
                      (filter (memfn isFile) (file-seq cp-dir)))))
                (filter (memfn isDirectory) classpath))
              ;; jar deps
              (sequence
                (comp
                  (map file-seq)
                  cat
                  (filter (memfn isFile))
                  (filter #(by-ext % "jar"))
                  (map (juxt #(str (signature (sha256 %))
                                   "-"
                                   (.getName %))
                             io/file)))
                classpath)
              [["Capsule.class" (io/resource "Capsule.class")]])
      [["Application-Class" "clojure.main"]
       ["Application-ID" application-id]
       ["Application-Version" application-version]]
      "Capsule")))

(defn -main
  [& args]
  (let [[deps-edn jar-location build-dir application-id application-version] args
        deps-map (tools.deps.reader/slurp-deps
                   (io/file deps-edn))]
    (classpath-string->jar
      (tools.deps/make-classpath
        (tools.deps/resolve-deps deps-map nil)
        (conj
          (map
            #(.resolveSibling (paths-get [deps-edn])
                              (paths-get [%]))
            (:paths deps-map))
          build-dir)
        nil)
      jar-location
      application-id
      application-version)))
