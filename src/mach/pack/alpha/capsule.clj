(ns mach.pack.alpha.capsule
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [mach.pack.alpha.impl.tools-deps :as tools-deps]
    [mach.pack.alpha.impl.elodin :as elodin]
    [mach.pack.alpha.impl.lib-map :as lib-map]
    [mach.pack.alpha.impl.vfs :as vfs]
    [me.raynes.fs :as fs])
  (:import
   java.io.File
   [java.nio.file Files Paths]
   java.nio.file.attribute.FileAttribute))

(defn- deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory prefix
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))

(defn write-jar
  [{::tools-deps/keys [lib-map paths]} output ext-attrs]
  (vfs/write-vfs
    {:type :jar
     :stream (io/output-stream output)
     :manifest {:main "Capsule"
                :ext-attrs ext-attrs}}
    (concat
      (map
        (fn [{:keys [path] :as all}]
          {:input (io/input-stream path)
           :path [(elodin/jar-name all)]})
        (lib-map/lib-jars lib-map))

      (map
        (fn [{:keys [path] :as all}]
          {:paths (vfs/files-path (file-seq (io/file path)) (io/file path))
           :path [(format "%s.jar" (elodin/directory-name all))]})
        (lib-map/lib-dirs lib-map))

      [{:paths (mapcat
                 (fn [dir]
                   (let [root (io/file dir)]
                     (vfs/files-path
                       (file-seq root)
                       root)))
                 paths)
        :path ["project.jar"]}
       {:path ["Capsule.class"]
        :input (io/input-stream (io/resource "Capsule.class"))}])))

(def manifest-header-pattern
  ;; see https://docs.oracle.com/javase/7/docs/technotes/guides/jar/jar.html
  ;; NOTE: we're being slightly more liberal than the spec, which is OK since
  ;; we're only interested in breaking the supplied Manifest entry into a [name value] pair;
  ;; further validation will happen downstream at the java.util.jar/Manifest level. (Valentin Waeselynck, 23 Jun 2018)
  #"([a-zA-Z0-9_\-]+):\s(.*)")

(def ^:private cli-options
  (concat
    [["-m" "--main SYMBOL" "main namespace"
      :parse-fn symbol]
     [nil "--application-id STRING" "globally unique name for application, used for caching"]
     [nil "--application-version STRING" "unique version for this uberjar, used for caching"]
     [nil "--system-properties STRING" "space-separated list of propName=value pairs, specifying JVM System Properties which will be passed to the application. Maps to the 'System-Properties' entry in the Capsule Manifest."]
     [nil "--jvm-args STRING" "space-separated list of JVM argument that will be used to launch the application (e.g \"-server -Xms200m -Xmx600m\"). Maps to the 'JVM-Args' entry in the Capsule Manifest."]
     ["-M" "--manifest-entry STRING"
      "a \"Key: Value\" pair that will be appended to the Capsule Manifest; useful for conveying arbitrary Manifest entries to the Capsule Manifest. Can be repeated to supply several entries."
      :validate [(fn [arg] (re-matches manifest-header-pattern arg))
                 "Manifest Entry must be of the form \"Name: Value\" (whitespace matters)"]
      :assoc-fn (fn [m opt arg]
                  (let [[_ k v] (re-matches manifest-header-pattern arg)]
                    (update m opt #(-> % (or []) (conj [k v])))))]]
    tools-deps/cli-spec
    [["-h" "--help" "show this help"]]))

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
  (let [{{:keys [main
                 application-id
                 application-version
                 system-properties
                 jvm-args
                 manifest-entry
                 help]
          :as options} :options
         [output] :arguments
         :as parsed-opts}
        (cli/parse-opts args cli-options)
        errors (cond-> (:errors parsed-opts)
                 (not output)
                 (conj "Output jar must be specified")
                 (not application-id)
                 (conj "--application-id must be specified"))]
    (cond
      help
      (println (usage (:summary parsed-opts)))
      errors
      (println (error-msg errors))
      :else
      (write-jar
        (-> (tools-deps/slurp-deps options)
            (tools-deps/parse-deps-map options))
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
          (into manifest-entry))))))
