(ns mach.pack.alpha.capsule
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.deps.alpha :as tools.deps]
   [clojure.tools.deps.alpha.script.make-classpath]
   [clojure.tools.deps.alpha.reader :as tools.deps.reader]
   [mach.pack.alpha.impl.assembly :refer [spit-jar!]]
   [me.raynes.fs :as fs])
  (:import
   java.io.File
   [java.nio.file Files Paths]
   java.nio.file.attribute.FileAttribute))

;; code adapted from boot
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
