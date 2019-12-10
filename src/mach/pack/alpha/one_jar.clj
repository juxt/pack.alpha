(ns mach.pack.alpha.one-jar
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
   java.nio.file.attribute.FileAttribute
   java.util.Arrays
   [javax.tools Diagnostic$Kind DiagnosticCollector ToolProvider]))

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn javac
  "Compile java sources. Primitive version for building a self-contained bootstrap"
  [tgt
   options] ;; options passed to java compiler
  (.mkdirs tgt)
  (let [diag-coll (DiagnosticCollector.)
        compiler  (or (ToolProvider/getSystemJavaCompiler)
                      (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))]
    (with-open [file-mgr (.getStandardFileManager compiler diag-coll nil nil)]
      (let [file-mgr (.getStandardFileManager compiler diag-coll nil nil)
            opts (->> ["-d"  (.getPath tgt)]
                      (concat options)
                      (into-array String)
                      Arrays/asList)

            bootstrap
            (map
              #(let [file (io/resource %)]
                 (proxy [javax.tools.SimpleJavaFileObject]
                   [(java.net.URI. (str "string://" (string/replace % #"mach/pack" "")))
                    javax.tools.JavaFileObject$Kind/SOURCE]
                   (getCharContent [ignoredEncodingErrors]
                     (slurp file))))
              #_(comment (into [] (comp (filter (memfn isFile)) (map #(string/replace % #"^src/" ""))) (file-seq (io/file "src/mach/pack/alpha/bootstrap/onejar/src/"))))
              ["mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/IProperties.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/JarClassLoader.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/Handler.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/OneJarURLConnection.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/OneJarFile.java" "mach/pack/alpha/bootstrap/onejar/src/com/simontuffs/onejar/Boot.java" "mach/pack/alpha/bootstrap/onejar/src/OneJar.java"])]
        (-> compiler
            (.getTask *err* file-mgr diag-coll opts nil bootstrap)
            (.call))
        (let [diagnostics (.getDiagnostics diag-coll)]
          (doseq [d diagnostics
                  :let [k (.getKind d)]]
            (let [log #(println (apply format %&))]
              (if (nil? (.getSource d))
                (log "%s: %s\n"
                     (.toString k)
                     (.getMessage d nil))
                (log "%s: %s, line %d: %s\n"
                     (.toString k)
                     (.. d getSource getName)
                     (.getLineNumber d)
                     (.getMessage d nil)))))
          (when-first [_ (filter #(= Diagnostic$Kind/ERROR (.getKind %))
                                 diagnostics)]
            (throw (Exception. "java compiler error"))))))))

(defn- deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory prefix
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))

(defn- create-bootstrap
  []
  (let [bootstrap-p (deleting-tmp-dir "pack-bootstrap")]
    (javac (.toFile bootstrap-p)
           nil)
    bootstrap-p))

(defn write-jar
  [{::tools-deps/keys [lib-map paths]} jar-location main & [args]]
  (let [bootstrap-p (create-bootstrap)]
    (vfs/write-vfs
      {:stream (io/output-stream jar-location)
       :type :jar
       :manifest {:main "com.simontuffs.onejar.Boot"
                  :ext-attrs
                  (concat
                    [["One-Jar-Main-Class" main]
                     ;; See https://dev.clojure.org/jira/browse/CLJ-971
                     ["One-Jar-URL-Factory" "com.simontuffs.onejar.JarClassLoader$OneJarURLFactory"]]
                    (when args
                      [["One-Jar-Main-Args" args]]))}}

      (concat
        (map
          (fn [{:keys [path] :as all}]
            {:input (delay (io/input-stream path))
             :path ["lib" (elodin/jar-name all)]})
          (lib-map/lib-jars lib-map))

        (map
          (fn [{:keys [path] :as all}]
            {:paths (vfs/files-path (file-seq (io/file path)) (io/file path))
             :path ["lib" (format "%s.jar" (elodin/directory-name all))]})
          (lib-map/lib-dirs lib-map))

        [{:path ["lib" "project.jar"]
          :paths (mapcat
                   (fn [dir]
                     (let [root (io/file dir)]
                       (vfs/files-path (file-seq root) root)))
                   paths)}]

        ;; This code will hoover up important files like the license from
        ;; OneJar.  but only works in dev, so combine with fireplace's c!! to
        ;; actually use.
        #_(comment (into []
                         (comp (filter (memfn isFile))
                               (map #(string/replace % #"^src/" ""))
                               (map (fn [x]
                                      {:path (into [] (elodin/file->path-seq (string/replace x #"^.*resources/" "")))
                                       :input (list 'delay (list 'io/input-stream (list 'io/resource x)))})))
                         (file-seq (io/file "src/mach/pack/alpha/bootstrap/onejar/resources/"))))
        [{:path [".version"], :input (delay (io/input-stream (io/resource "mach/pack/alpha/bootstrap/onejar/resources/.version")))} {:path ["doc" "one-jar-license.txt"], :input (delay (io/input-stream (io/resource "mach/pack/alpha/bootstrap/onejar/resources/doc/one-jar-license.txt")))}]
        (let [root (.toFile bootstrap-p)]
          (vfs/files-path
            (filter #(.endsWith (.getName %) ".class") (file-seq root))
            root))))))

(def ^:private cli-options
  (concat
    tools-deps/cli-spec
    [["-m" "--main STRING" "Override the default main of clojure.main. You MUST use AOT compilation with this."
      :default "clojure.main"]
     [nil "--args STRING" "Default args to the main"]
     ["-h" "--help" "show this help"]]))

(defn- usage
  [summary]
  (->>
    ["Usage: clj -m mach.pack.alpha.one-jar [options] <path/to/output.jar>"
     ""
     "Options:"
     summary
     ""
     "output.jar is where to put the output uberjar. Leading directories will be created."]
    (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn -main
  [& args]
  (let [{{:keys [help
                 main
                 args]
          :as options} :options
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
      (do
        (when (and main (not= main "clojure.main"))
          (println (format "NOTE: You've specified a custom main.  This usually isn't necessary, you can adjust startup command to `java -jar foo.jar -m %s` and save yourself the trouble of AOT." main)))
        (write-jar
          (-> (tools-deps/slurp-deps options)
              (tools-deps/parse-deps-map options))
          output
          main
          args)))))
