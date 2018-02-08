(ns mach.pack.alpha.aws-lambda
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.deps.alpha :as tools.deps]
    [clojure.tools.deps.alpha.script.make-classpath]
    [clojure.tools.deps.alpha.reader :as tools.deps.reader]
    [me.raynes.fs :as fs])
  (:import
    [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]
    [java.util.zip ZipException ZipOutputStream ZipEntry]
    [javax.tools ToolProvider DiagnosticCollector Diagnostic$Kind]
    [java.util Arrays]
    [java.io File]
    [java.nio.file Paths Path Files]
    [java.nio.file.attribute FileAttribute]))

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

(defn spit-zip! [zippath files]
  (let [zipfile (io/file zippath)]
    (io/make-parents zipfile)
    (with-open [s (ZipOutputStream. (io/output-stream zipfile))]
      (doseq [[^String zippath ^String srcpath] files :let [f (io/file srcpath)]]
        (when-not (.isDirectory f)
          (let [entry (doto (ZipEntry. zippath) (.setTime (.lastModified f)))]
            (try
              (doto s (.putNextEntry entry) (write! srcpath) .closeEntry)
              (catch Throwable t
                (if-not (dupe? t) (throw t) (println (.getMessage t)))))))))))

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn split-classpath-string
  [classpath-string]
  (string/split
    classpath-string
    ;; re-pattern should be safe given the characters that can be separators, but could be safer
    (re-pattern File/pathSeparator)))

(defn paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

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

(defn classpath-string->zip
  [classpath-string zip-location]
  (let [classpath
        (map io/file (split-classpath-string classpath-string))]
    (spit-zip!
      zip-location
      (concat ;; directories on the classpath
              (mapcat
                (fn [cp-dir]
                  (let [cp-dir-p (.toPath cp-dir)]
                    (map
                      (juxt #(str (.relativize cp-dir-p (.toPath %)))
                            identity)
                      (filter (memfn isFile) (file-seq cp-dir)))))
                (filter (memfn isDirectory) classpath))
              ;; jar deps
              (sequence
                (comp
                  (map file-seq)
                  cat
                  (filter (memfn isFile))
                  (filter #(by-ext % "jar"))
                  (map (juxt #(str (paths-get ["lib" (str (signature (sha256 %))
                                                          "-"
                                                          (.getName %))]))
                             identity)))
                classpath)))))

(defn -main
  [& args]
  (let [[deps-edn jar-location build-dir] args
        deps-map (tools.deps.reader/slurp-deps
                   (io/file deps-edn))]
    (classpath-string->zip
      (tools.deps/make-classpath
        (tools.deps/resolve-deps deps-map nil)
        (conj
          (map
            #(.resolveSibling (paths-get [deps-edn])
                              (paths-get [%]))
            (:paths deps-map))
          build-dir)
        nil)
      jar-location)))
