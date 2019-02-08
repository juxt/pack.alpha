(ns mach.pack.alpha.skinny
  (:require
    [clojure.tools.cli :as cli]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [mach.pack.alpha.impl.elodin :as elodin]
    [mach.pack.alpha.impl.tools-deps :as tools-deps]
    [mach.pack.alpha.impl.lib-map :as lib-map]
    [mach.pack.alpha.impl.vfs :as vfs]
    [me.raynes.fs :as fs])
  (:import [java.nio.file Paths]))

(defn write-project
  [{::tools-deps/keys [paths]}
   {:keys [output-path output-target]}]
  (io/make-parents (io/file output-path))
  (case output-target
    :jar (vfs/write-vfs
           {:type :jar
            :stream (io/output-stream output-path)}
           (mapcat
             (fn [path]
               (vfs/files-path
                 (file-seq (io/file path))
                 (io/file path)))
             paths))

    :dir (vfs/write-vfs
           {:type :dir
            :root output-path}
           (mapcat
             (fn [path]
               (map
                 (fn [x]
                   (update x :path (fn [spath]
                                     (cons
                                       (string/join
                                         "-"
                                         (elodin/path->path-seq
                                           (elodin/str->path path)))
                                       spath))))
                 (vfs/files-path (file-seq (io/file path)) (io/file path))))
             paths))))

(defn write-libs
  [{::tools-deps/keys [lib-map]}
   {:keys [lib-dir output-target]}]
  (let [root (io/file lib-dir)]
    (vfs/write-vfs
      {:type :dir
       :root root}
      (concat
        (map
          ;; TODO: Master elodin should be in charge of this
          (fn [{:keys [path] :as all}]
            {:path [(format "%s.jar" (elodin/versioned-lib all))]
             :input (io/input-stream path)})
          (lib-map/lib-jars lib-map))
        (case output-target
          :keep 
          (mapcat
            (fn [{:keys [lib path] :as all}]
              (map (fn prefix-paths [pat]
                     (update pat :path
                             #(cons
                                (format "%s-%s"
                                        (elodin/versioned-lib all)
                                        (elodin/directory-unique all))
                                %)))
                   (vfs/files-path (file-seq (io/file path)) (io/file path))))
            (lib-map/lib-dirs lib-map))
          :jar
          (map
            (fn [{:keys [lib path] :as all}]
              {:path
               [(format "%s-%s.jar"
                        (elodin/versioned-lib all)
                        (elodin/directory-unique all))]

               :paths (vfs/files-path (file-seq (io/file path))
                                      (io/file path))

               :type :jar})
            (lib-map/lib-dirs lib-map)))))))

(defn write-all
  [x project-config lib-config]
  (when project-config
    (write-project x project-config))
  (when lib-config
    (write-libs x lib-config)))

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
      (write-all
        (-> (tools-deps/slurp-deps options)
            (tools-deps/parse-deps-map options))
        (when-not no-project
          {:output-path project-path
           :output-target (case (fs/extension project-path)
                            nil :dir
                            ".jar" :jar)})
        (when-not no-libs
          {:lib-dir lib-dir
           :output-target lib-type})))))
