(ns mach.pack.alpha.capsule
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.cli :as cli]
   [clojure.tools.deps.alpha :as tools.deps]
   [clojure.tools.deps.alpha.script.make-classpath]
   [clojure.tools.deps.alpha.reader :as tools.deps.reader]
   [mach.pack.alpha.impl.assembly :refer [spit-jar!]]
   [mach.pack.alpha.impl.elodin :as elodin]
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

(defn paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

(defn classpath-string->jar
  [classpath-string jar-location manifest]
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
                  (map (juxt (comp elodin/path-seq->str
                                   elodin/hash-derived-name)
                             io/file)))
                classpath)
              [["Capsule.class" (io/resource "Capsule.class")]])
      manifest
      "Capsule")))

(def manifest-header-pattern
  ;; see https://docs.oracle.com/javase/7/docs/technotes/guides/jar/jar.html
  ;; NOTE: we're being slightly more liberal than the spec, which is OK since
  ;; we're only interested in breaking the supplied Manifest entry into a [name value] pair;
  ;; further validation will happen downstream at the java.util.jar/Manifest level. (Valentin Waeselynck, 23 Jun 2018)
  #"([a-zA-Z0-9_\-]+):\s(.*)")

(def ^:private cli-options
  [["-m" "--main SYMBOL" "main namespace"
    :parse-fn symbol]
   [nil "--application-id STRING" "globally unique name for application, used for caching"]
   [nil "--application-version STRING" "unique version for this uberjar, used for caching"]
   [nil "--system-properties STRING" "space-separated list of propName=value pairs, specifying JVM System Properties which will be passed to the application. Maps to the 'System-Properties' entry in the Capsule Manifest."]
   [nil "--jvm-args STRING" "space-separated list of JVM argument that will be used to launch the application (e.g \"-server -Xms200m -Xmx600m\"). Maps to the 'JVM-Args' entry in the Capsule Manifest."]
   ["-e" "--extra-path STRING" "add directory to classpath for building"
    :assoc-fn (fn [m k v] (update m k conj v))]
   ["-d" "--deps STRING" "deps.edn file location"
    :default "deps.edn"
    :validate [(comp (memfn exists) io/file) "deps.edn file must exist"]]
   ["-a" "--alias STRING" "Aliases to use for determining extra dependencies. E.g. -a :server:client/release"
    :default ""
    :assoc-fn (fn [m k v]
                (assoc m k (map keyword (rest (string/split v #":")))))]
   ["-M" "--manifest-entry STRING"
    "a \"Key: Value\" pair that will be appended to the Capsule Manifest; useful for conveying arbitrary Manifest entries to the Capsule Manifest. Can be repeated to supply several entries."
    :validate [(fn [arg] (re-matches manifest-header-pattern arg))
               "Manifest Entry must be of the form \"Name: Value\" (whitespace matters)"]
    :assoc-fn (fn [m opt arg]
                (let [[_ k v] (re-matches manifest-header-pattern arg)]
                  (update m opt #(-> % (or []) (conj [k v])))))]
   ["-h" "--help" "show this help"]])

(defn- usage
  [summary]
  (->>
    ["Usage: clj -m mach.pack.alpha.capsule [options] <path/to/output.jar>"
     ""
     "Options:"
     summary
     ""
     "output.jar is where to put the output uberjar. Leading directories will be created."
     ""
     "Please see capsule user guide for explanation of application id and version"
     "and whether you need them."
     "http://www.capsule.io/user-guide/"]
    (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn -main
  [& args]
  (let [{{:keys [deps
                 alias
                 extra-path
                 main
                 application-id
                 application-version
                 system-properties
                 jvm-args
                 manifest-entry
                 help]} :options
         [output] :arguments
         :as parsed-opts}
        (cli/parse-opts args cli-options)
        errors (cond-> (:errors parsed-opts)
                 (not output)
                 (conj "Output jar must be specified"))]
    (cond
      help
      (println (usage (:summary parsed-opts)))
      errors
      (println (error-msg errors))
      :else
      (let [deps-map (tools.deps.reader/slurp-deps (io/file deps))
            resolve-args (tools.deps/combine-aliases
                          deps-map
                          (get-in parsed-opts [:options :alias]))]
        (classpath-string->jar
          (tools.deps/make-classpath
           (tools.deps/resolve-deps deps-map resolve-args)
           (map
            #(.resolveSibling (paths-get [deps])
                              (paths-get [%]))
            (:paths deps-map))
           {:extra-paths extra-path})
          output
          (cond->
              [["Application-Class" "clojure.main"]
               ["Application-ID" application-id]
               ["Application-Version" application-version]]
            system-properties
            (conj ["System-Properties" system-properties])
            jvm-args
            (conj ["JVM-Args" jvm-args])
            main
            (conj ["Args" (str "-m " main)])
            true
            (into manifest-entry)))))))
