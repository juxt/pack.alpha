(ns mach.pack.alpha.skinny
  (:require
    [clojure.tools.cli :as cli]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [mach.pack.alpha.impl.assembly :refer [spit-jar!]]
    [mach.pack.alpha.impl.elodin :as elodin]
    [mach.pack.alpha.impl.tools-deps :as tools-deps]
    [me.raynes.fs :as fs])
  (:import [java.nio.file Paths]))

(defn file->type
 [file]
 (let [file (io/file file)]
   (cond
     (fs/directory? file) :dir
     (and (fs/file? file) (= (fs/extension file) ".jar")) :jar)))

(defn lib->steps
 [lib-name lib {:keys [lib-dir output-target]}]
 (map (fn [path]
        (let [input-type (file->type path)
              output-type (if (= output-target :keep)
                           input-type
                           output-target)]
          {:input {:paths [path] :type input-type}
           :output {:components
                      (concat
                       lib-dir
                       [(str (namespace lib-name)
                             "__"
                             (name lib-name)
                             (when-let [v ((some-fn :mvn/version :sha) lib)]
                               (str "__" v))
                             (when (= input-type :dir)
                               (str "-"
                                    (string/join
                                      "-"
                                      (elodin/path->path-seq
                                        (.relativize (elodin/str->abs-path (:deps/root lib))
                                                     (elodin/str->path path))))))
                             (when (= output-type :jar)
                               ;; No suffix, just presume that
                               ;; there's only ever one jar.
                               :jar
                               ".jar"))])
                    :type output-type}}))
      (:paths lib)))

(defn project-paths->steps
 [paths path-root {:keys [output-components output-target] :as project-config}]
 (case output-target
   :jar [{:input {:paths paths :type :dir}
          :output {:components output-components :type output-target}}]
   :dir (map (fn [path]
               ;; TODO: path needs resolving relative to the project-root
               {:input {:paths [path] :type :dir}
                :output {:components
                           (concat #_(butlast output-components)
                                   #_[(str (last output-components) "-" path)]
                                   output-components
                                   [(str
                                      (.relativize (elodin/str->abs-path path-root)
                                                   (elodin/str->abs-path path)))])
                         :type output-target}})
             paths)))

(defn tools-deps->steps
 [{::tools-deps/keys [lib-map paths]} project-config lib-config]
 (let [path-root (fs/parent "deps.edn")]
   (concat
    (when project-config
      (project-paths->steps paths path-root project-config))
    (when lib-config
      (mapcat (fn [[n lib]] (lib->steps n lib lib-config)) lib-map)))))

(defn copy-dir-to-dir
 [{:keys [input output]}]
 (doseq [path (:paths input)]
   (fs/copy-dir path (-> output :components elodin/path-seq->str))))

(defn copy-file-to-file
 [{:keys [input output]}]
 (doseq [path (:paths input)]
   (fs/copy+ path (-> output :components elodin/path-seq->str))))

(defn jar-file-seq
 [path]
 (map (juxt #(str (.relativize (.toPath path) (.toPath %))) io/file)
      (filter (memfn isFile) (file-seq path))))

(defn dir->jar
 [{:keys [input output]}]
 (spit-jar! (-> output :components elodin/path-seq->str io/file)
            (mapcat (comp jar-file-seq io/file) (:paths input))
            (:jar/attr output)
            (:jar/main output)))

(defn run-steps
 [steps]
 (doseq [{:keys [output]} steps
         :let [p (-> output :components elodin/path-seq->str)]]
   (when (fs/exists? p)
     (throw (IllegalArgumentException.
             (str p " already exists, expected behaviour is unknown.")))))
 (doseq [{:keys [input output] :as step} steps]
   (let [f (case [(:type input) (:type output)]
             ([:dir :dir]) copy-dir-to-dir
             ([:jar :jar]) copy-file-to-file
             ([:dir :jar]) dir->jar)]
     (f step))))

(comment
 (run-steps (project-paths->steps ["src"]
                                  {:output-components ["mytest" "app.jar"]
                                   :output-target :jar}))
 (run-steps
   (map )
   (tools-deps->steps (-> (tools-deps/slurp-deps nil)
                          (tools-deps/parse-deps-map {}))
                      {:output-components ["mytest" "app.jar"] :output-target :jar}
                      {:lib-dir ["mytest" "lib"] :output-target :jar})))

(def ^:private cli-options
  (concat
    [[nil "--no-libs" "Skip lib outputs"
      :default false]
     [nil "--no-project" "Skip project outputs"
      :default false]
     [nil "--lib-dir PATH" "Where to place the output libraries"
      :default "target/lib"]
     [nil "--lib-type STRING" "Lib type format to use, keep or jar. Keep will keep in original format (jar or dir)"
      :default :jar
      :parse-fn keyword
      :validate [#{:jar :keep} "Only keep or jar are valid"]]
     [nil "--project-path PATH" "Where to place the project output, if it ends with .jar then the project will automatically output as a jar also."
      :default "target/app.jar"]]
    tools-deps/cli-spec
    [["-h" "--help" "show this help"]]))

(defmacro *ns*-name
  []
  (str *ns*))

(defn- usage
  [summary]
  (->>
    [(format "Usage: clj -m %s [options]" (*ns*-name))
     ""
     "Options:"
     summary]
    (string/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn -main
  [& args]
  (let [{{:keys [help
                 no-libs
                 no-project
                 lib-dir
                 lib-type
                 project-path]
          :as options} :options
         :as parsed-opts} (cli/parse-opts args cli-options)
        errors (:errors parsed-opts)]
    (cond
      help
      (println (usage (:summary parsed-opts)))
      errors
      (println (error-msg errors))
      :else
      (run-steps
        (tools-deps->steps
          (-> (tools-deps/slurp-deps options)
              (tools-deps/parse-deps-map options))
          (when-not no-project
            {:output-components (elodin/file->path-seq project-path)
             :output-target (case (fs/extension project-path)
                              nil :dir
                              ".jar" :jar)})
          (when-not no-libs
            {:lib-dir (elodin/file->path-seq lib-dir)
             :output-target lib-type}))))))
