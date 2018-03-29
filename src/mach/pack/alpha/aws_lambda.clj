(ns mach.pack.alpha.aws-lambda
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.deps.alpha :as tools.deps]
   [clojure.tools.deps.alpha.reader :as tools.deps.reader]
   [clojure.tools.deps.alpha.script.make-classpath]
   [mach.pack.alpha.impl.assembly :refer [spit-zip!]]
   [mach.pack.alpha.impl.elodin :as elodin])
  (:import
   java.io.File
   java.nio.file.Paths))

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
                  (map (juxt #(elodin/path-seq->str
                                (cons "lib" (elodin/hash-derived-name)))
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
