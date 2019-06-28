(ns mach.pack.alpha.jib
  (:require [mach.pack.alpha.impl.tools-deps :as tools-deps]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import (com.google.cloud.tools.jib.api Jib AbsoluteUnixPath)
           (java.nio.file Paths Files LinkOption)
           (com.google.cloud.tools.jib.api LayerConfiguration
                                           Containerizer
                                           DockerDaemonImage
                                           TarImage
                                           RegistryImage
                                           ImageReference)
           (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)))

(def string-array (into-array String []))
(def target-dir "/home/app")

(defn jib [{::tools-deps/keys [paths lib-map]} {:keys [image-name image-type tar-file base-image target-dir main]}]
  (println "Building" image-name)
  (-> (Jib/from base-image)
      (.addLayer (into []
                       (map #(Paths/get % string-array))
                       (mapcat :paths (vals lib-map)))
                 (AbsoluteUnixPath/get target-dir))
      (.addLayer (-> (reduce (fn [acc project-path]
                               (let [path (Paths/get project-path string-array)]
                                 (if (Files/exists path (into-array LinkOption []))
                                   (.addEntryRecursive acc
                                                       path
                                                       (AbsoluteUnixPath/get (str target-dir "/" project-path)))
                                   acc)))
                             (LayerConfiguration/builder)
                             paths)
                     (.build)))
      (.setEntrypoint (into-array String ["java"
                                          "-cp" (str/join ":" (cons (str target-dir "/*")
                                                                    (map #(str target-dir "/" %) paths)))
                                          "clojure.main" "-m" main]))
      (.containerize (Containerizer/to (case image-type
                                         :docker (DockerDaemonImage/named image-name)
                                         :tar (-> (TarImage/named image-name)
                                                  (.saveTo (Paths/get tar-file string-array)))
                                         :registry (-> (RegistryImage/named image-name)
                                                       (.addCredentialRetriever (-> (CredentialRetrieverFactory/forImage (ImageReference/parse image-name))
                                                                                    (.dockerConfig)))))))))

(def image-types #{:docker :registry :tar})

(def ^:private cli-options
  (concat
   [[nil "--image-name NAME" "Name of the image"]
    [nil "--image-type TYPE" (str "Type of the image, one of: " (str/join ", " (map name image-types)))
     :parse-fn keyword
     :validate [image-types (str "Supported image types: " (str/join ", " (map name image-types)))]
     :default :docker]
    [nil "--tar-file FILE" "Tarball file name"]
    [nil "--base-image BASE-IMAGE" "Base Docker image to use"
     :default "gcr.io/distroless/java:11"]
    ["-m" "--main SYMBOL" "Main namespace"]]
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
                 image-type
                 tar-file
                 base-image
                 main]
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
            :image-name image-name
            :image-type image-type
            :tar-file tar-file
            :main main}))))
