(ns mach.pack.alpha.jib
  (:require [mach.pack.alpha.impl.tools-deps :as tools-deps]
            [mach.pack.alpha.impl.lib-map :as lib-map]
            [mach.pack.alpha.impl.elodin :as elodin]
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

(defn unique-base-path
  "Creates a unique string from a path by joining path elements with `-`.

  Used for creating base directory for project paths such that it won't clash with library directories."
  [path]
  (->> path
       (.iterator)
       (iterator-seq)
       (map str)
       (str/join "-")))

(defn jib [{::tools-deps/keys [paths lib-map]} {:keys [image-name image-type tar-file base-image target-dir main]}]
  (println "Building" image-name)
  (let [lib-jars-layer (reduce (fn [acc {:keys [path] :as all}]
                                 (let [container-path (AbsoluteUnixPath/get (str target-dir
                                                                                 "/"
                                                                                 (elodin/versioned-lib all)
                                                                                 ".jar"))]
                                   (-> acc
                                       (update :builder #(.addEntry %
                                                                    (Paths/get path string-array)
                                                                    container-path))
                                       (update :container-paths conj container-path))))
                               {:builder (-> (LayerConfiguration/builder)
                                             (.setName "library jars"))
                                :container-paths nil}
                               (lib-map/lib-jars lib-map))
        lib-dirs-layer (reduce (fn [acc {:keys [path] :as all}]
                                 (let [container-path (AbsoluteUnixPath/get (str target-dir
                                                                                 "/"
                                                                                 (elodin/versioned-lib all)
                                                                                 "-"
                                                                                 (elodin/directory-unique all)))]
                                   (-> acc
                                       (update :builder #(.addEntryRecursive %
                                                                             (Paths/get path string-array)
                                                                             container-path))
                                       (update :container-paths conj container-path))))
                               {:builder (-> (LayerConfiguration/builder)
                                             (.setName "library directories"))
                                :container-paths nil}
                               (lib-map/lib-dirs lib-map))
        project-dirs-layer (reduce (fn [acc project-path]
                                     (let [path (Paths/get project-path string-array)]
                                       (if (Files/exists path (into-array LinkOption []))
                                         (let [container-path (AbsoluteUnixPath/get (str target-dir
                                                                                         "/"
                                                                                         (unique-base-path path)))]
                                           (-> acc
                                               (update :builder #(.addEntryRecursive %
                                                                                     path
                                                                                     container-path))
                                               (update :container-paths conj container-path)))
                                         acc)))
                                   {:builder (-> (LayerConfiguration/builder)
                                                 (.setName "project directories"))
                                    :container-paths nil}
                                   paths)]
    (-> (Jib/from base-image)
        (.addLayer (-> lib-jars-layer :builder (.build)))
        (.addLayer (-> lib-dirs-layer :builder (.build)))
        (.addLayer (-> project-dirs-layer :builder (.build)))
        (.setEntrypoint (into-array String ["java"
                                            "-cp" (str/join ":" (map str (mapcat :container-paths [lib-jars-layer
                                                                                                   lib-dirs-layer
                                                                                                   project-dirs-layer])))
                                            "clojure.main" "-m" main]))
        (.containerize (Containerizer/to (case image-type
                                           :docker (DockerDaemonImage/named image-name)
                                           :tar (-> (TarImage/named image-name)
                                                    (.saveTo (Paths/get tar-file string-array)))
                                           :registry (-> (RegistryImage/named image-name)
                                                         (.addCredentialRetriever (-> (CredentialRetrieverFactory/forImage (ImageReference/parse image-name))
                                                                                      (.dockerConfig))))))))))

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
      (or help (nil? image-name) (and (= :tar image-type) (nil? tar-file)))
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
