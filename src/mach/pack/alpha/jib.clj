(ns mach.pack.alpha.jib
  (:require [mach.pack.alpha.impl.tools-deps :as tools-deps]
            [mach.pack.alpha.impl.lib-map :as lib-map]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [mach.pack.alpha.impl.elodin :as elodin])
  (:import (com.google.cloud.tools.jib.api Jib AbsoluteUnixPath)
           (java.nio.file Paths)
           (com.google.cloud.tools.jib.api LayerConfiguration
                                           LayerConfiguration$Builder
                                           Containerizer
                                           DockerDaemonImage)))

(defn jib [{::tools-deps/keys [lib-map]} {:keys [image-name base-image target-dir]}]
  (println "Building" image-name)
  (let [libs-layer (.build (reduce (fn [acc {:keys [path] :as all}]
                                     (.addEntry acc
                                                (Paths/get path (into-array String []))
                                                (AbsoluteUnixPath/get (str target-dir
                                                                           "/"
                                                                           (elodin/versioned-lib all)
                                                                           ".jar"))))
                                   (LayerConfiguration/builder)
                                   (lib-map/lib-jars lib-map)))]
    (-> (Jib/from base-image)
        (.addLayer libs-layer)
        (.setEntrypoint (into-array String ["java" "-cp" (str target-dir "/*") "clojure.main"]))
        (.containerize (Containerizer/to (DockerDaemonImage/named image-name))))))

(def ^:private cli-options
  (concat
   [[nil "--image-name IMAGE" "Name of image to build for docker daemon"]
    [nil "--base-image BASE-IMAGE" "Base Docker image to use"
     :default "openjdk:11-jre-slim"]
    [nil "--target-dir PATH" "Where to place resources in the Docker image"
     :default "/home/app/lib"]]
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
    (str/join \newline)))

(defn- error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn -main [& args]
  (let [{{:keys [help
                 image-name
                 base-image
                 target-dir]
          :as options} :options
         :as parsed-opts} (cli/parse-opts args cli-options)
        errors (:errors parsed-opts)]
    (cond
      help
      (println (usage (:summary parsed-opts)))
      errors
      (println (error-msg errors))
      :else
      (jib (-> (tools-deps/slurp-deps options)
               (tools-deps/parse-deps-map options))
           {:base-image base-image
            :target-dir target-dir
            :image-name image-name}))))
