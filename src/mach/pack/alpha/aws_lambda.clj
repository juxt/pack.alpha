(ns mach.pack.alpha.aws-lambda
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [mach.pack.alpha.impl.tools-deps :as tools-deps]
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
                                (cons "lib" (elodin/hash-derived-name %)))
                             identity)))
                classpath)))))

(defn- usage
  [summary]
  (->>
    ["Usage: clj -m mach.pack.alpha.aws-lambda [options] <path/to/output.zip>"
     ""
     "Options:"
     summary
     ""
     "output.zip is where to put the output zip. Leading directories will be created."]
    (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn -main
  [& args]
  (let [{{:keys [help main]
          :as options} :options
         [jar-location] :arguments
         :as parsed-opts} (cli/parse-opts
                            args
                            (concat tools-deps/cli-spec
                                    [["-h" "--help" "show this help"]]))
        errors (cond-> (:errors parsed-opts)
                 (not jar-location)
                 (conj "Output must be specified"))]
    (cond
      help
      (println (usage (:summary parsed-opts)))
      errors
      (println (error-msg errors))
      :else
      (classpath-string->zip
        (-> (tools-deps/slurp-deps options)
            (tools-deps/parse-deps-map options)
            (tools-deps/make-classpath))
        jar-location))))
