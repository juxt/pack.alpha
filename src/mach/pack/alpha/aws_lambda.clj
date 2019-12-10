(ns mach.pack.alpha.aws-lambda
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [mach.pack.alpha.impl.tools-deps :as tools-deps]
    [mach.pack.alpha.impl.elodin :as elodin]
    [mach.pack.alpha.impl.vfs :as vfs]
    [mach.pack.alpha.impl.lib-map :as lib-map]))

(defn write-zip
  [{::tools-deps/keys [lib-map paths]} output]
  (vfs/write-vfs
    {:type :zip
     :stream (io/output-stream output)}
    (concat
      (map
        (fn [{:keys [path] :as all}]
          {:input (delay (io/input-stream path))
           :path ["lib" (elodin/jar-name all)]})
        (lib-map/lib-jars lib-map))

      (map
        (fn [{:keys [path lib] :as all}]
          {:paths (vfs/files-path
                    (file-seq (io/file path))
                    (io/file path))
           :path ["lib" (format "%s.jar" (elodin/directory-name all))]})
        (lib-map/lib-dirs lib-map))

      (mapcat
        (fn [dir]
          (let [root (io/file dir)]
            (vfs/files-path
              (file-seq root)
              root)))
        paths))))

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
      (write-zip
        (-> (tools-deps/slurp-deps options)
            (tools-deps/parse-deps-map options))
        jar-location))))
