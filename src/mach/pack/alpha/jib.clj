(ns mach.pack.alpha.jib
  (:require [mach.pack.alpha.impl.tools-deps :as tools-deps]
            [mach.pack.alpha.impl.lib-map :as lib-map]
            [mach.pack.alpha.impl.elodin :as elodin]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [progrock.core :as pr])
  (:import (com.google.cloud.tools.jib.api Jib)
           (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath)
           (java.nio.file Paths Files LinkOption FileSystems)
           (com.google.cloud.tools.jib.api LayerConfiguration
                                           Containerizer
                                           DockerDaemonImage
                                           TarImage
                                           RegistryImage
                                           ImageReference
                                           LogEvent
                                           Credential
                                           CredentialRetriever)
           (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)
           (com.google.cloud.tools.jib.event.events ProgressEvent TimerEvent)
           (java.util.function Consumer BiFunction)
           (java.util Optional)
           (java.time Instant)))

(def string-array (into-array String []))
(def target-dir "/app")

(def logger
  (reify java.util.function.Consumer
    (accept [this log-event]
      (println log-event))))

(defn unique-base-path
  "Creates a unique string from a path by joining path elements with `-`.

  Used for creating base directory for project paths such that it won't clash with library directories."
  [path]
  (->> path
       (.iterator)
       (iterator-seq)
       (map str)
       (str/join "-")))

(defn progress-bar-consumer []
  (let [progress-bar (atom (pr/progress-bar 100))]
    (reify Consumer
      (accept [this t]
        (let [progress (* (-> t (.getAllocation) (.getFractionOfRoot))
                          (.getUnits t))
              bar (swap! progress-bar pr/tick (Math/round (* progress 100)))]
          (pr/print bar {:format "[:bar] :progress/:total  :elapsed"}))))))

(defn add-additional-tags [containerizer tags]
  (reduce (fn [acc tag]
            (.withAdditionalTag acc tag))
          containerizer
          tags))

(defn add-labels [jib-container-builder labels]
  (reduce (fn [acc [key value]]
            (.addLabel acc key value))
          jib-container-builder
          labels))

(defn explicit-credentials [username password]
  (when (and (string? username) (string? password))
    (reify CredentialRetriever
      (retrieve [_]
        (Optional/of (Credential/from username password))))))

(def classfile-matcher (-> (FileSystems/getDefault)
                           (.getPathMatcher "glob:**.class")))

(def timestamp-provider
  (reify BiFunction
    (apply [_ source-path destination-path]
      (if (.matches classfile-matcher source-path)
        (Instant/ofEpochSecond 8589934591)
        LayerConfiguration/DEFAULT_MODIFICATION_TIME))))

(defn jib [{::tools-deps/keys [paths lib-map]} {:keys [image-name image-type tar-file base-image target-dir include additional-tags labels user creation-time to-registry-username to-registry-password from-registry-username from-registry-password quiet verbose extra-java-args main]}]
  (when-not quiet
    (println "Building" image-name))
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
                                                                                     container-path
                                                                                     LayerConfiguration/DEFAULT_FILE_PERMISSIONS_PROVIDER
                                                                                     timestamp-provider))
                                               (update :container-paths conj container-path)))
                                         acc)))
                                   {:builder (-> (LayerConfiguration/builder)
                                                 (.setName "project directories"))
                                    :container-paths nil}
                                   paths)
        base-image-with-creds (-> (RegistryImage/named ^String base-image)
                                  (.addCredentialRetriever (or
                                                             (explicit-credentials from-registry-username from-registry-password)
                                                             (-> (CredentialRetrieverFactory/forImage (ImageReference/parse base-image) logger)
                                                                 (.dockerConfig)))))]
    (-> (cond-> (Jib/from base-image-with-creds)
          include (.addLayer [(Paths/get (first (.split include ":")) string-array)]
                             (AbsoluteUnixPath/get (last (.split include ":"))))
          (seq labels) (add-labels labels)
          user (.setUser user))
        (.setCreationTime (or (some->> creation-time
                                       (Long/valueOf)
                                       (Instant/ofEpochSecond))
                              (Instant/EPOCH)))
        (.addLayer (-> lib-jars-layer :builder (.build)))
        (.addLayer (-> lib-dirs-layer :builder (.build)))
        (.addLayer (-> project-dirs-layer :builder (.build)))
        (.setWorkingDirectory (AbsoluteUnixPath/get target-dir))
        (.setEntrypoint (into-array String (concat ["java"]
                                                   (when (seq extra-java-args)
                                                     (str/split extra-java-args #"\s+"))
                                                   ["-Dclojure.main.report=stderr"
                                                    "-Dfile.encoding=UTF-8"
                                                    "-cp" (str/join ":" (map str (mapcat :container-paths [lib-jars-layer
                                                                                                           lib-dirs-layer
                                                                                                           project-dirs-layer])))
                                                    "clojure.main" "-m" main])))
        (.containerize (cond-> (Containerizer/to (case image-type
                                                   :docker (DockerDaemonImage/named image-name)
                                                   :tar (-> (TarImage/at (Paths/get tar-file string-array))
                                                            (.named image-name))
                                                   :registry (-> (RegistryImage/named image-name)
                                                                 (.addCredentialRetriever (or
                                                                                            (explicit-credentials to-registry-username to-registry-password)
                                                                                            (-> (CredentialRetrieverFactory/forImage (ImageReference/parse image-name) logger)
                                                                                                (.dockerConfig)))))))
                         (seq additional-tags) (add-additional-tags additional-tags)
                         (not quiet) (.addEventHandler ProgressEvent (progress-bar-consumer))
                         verbose (.addEventHandler LogEvent (reify Consumer
                                                              (accept [this t]
                                                                (println (.getMessage t)))))
                         verbose (.addEventHandler TimerEvent (reify Consumer
                                                                (accept [this t]
                                                                  (println (.getDescription t))))))))))

(def image-types #{:docker :registry :tar})

(def ^:private cli-options
  (concat
   [[nil "--image-name NAME" "Name of the image"]
    [nil "--image-type TYPE" (str "Type of the image, one of: " (str/join ", " (map name image-types)))
     :validate [image-types (str "Supported image types: " (str/join ", " (map name image-types)))]
     :parse-fn keyword
     :default :docker
     :default-desc (name :docker)]
    [nil "--tar-file FILE" "Tarball file name"]
    [nil "--base-image BASE-IMAGE" "Base Docker image to use"
     :default "gcr.io/distroless/java:11"]
    [nil "--creation-time CREATION-TIME-EPOCH" "Set creation time of image in epoch seconds, e.g. $(git log -1 --pretty=format:%ct) Defaults to 0."]
    [nil "--include [src:]dest" "Include file or directory, relative to container root"]
    [nil "--additional-tag TAG" "Additional tag for the image, e.g latest. Repeat to add multiple tags"
     :assoc-fn #(update %1 %2 conj %3)]
    [nil "--label LABEL=VALUE" "Set a label for the image, e.g. GIT_COMMIT=${CI_COMMIT_SHORT_SHA}. Repeat to add multiple labels."
     :assoc-fn #(update %1 %2 conj (str/split %3 #"="))]
    [nil "--user USER" "Set the user and group to run the container as. Valid formats are: user, uid, user:group, uid:gid, uid:group, user:gid"]
    [nil "--from-registry-username USER" "Set the username to use when pulling from registry, e.g. gitlab-ci-token."]
    [nil "--from-registry-password PASSWORD" "Set the password to use when pulling from registry, e.g. ${CI_JOB_TOKEN}."]
    [nil "--to-registry-username USER" "Set the username to use when deploying to registry, e.g. gitlab-ci-token."]
    [nil "--to-registry-password PASSWORD" "Set the password to use when deploying to registry, e.g. ${CI_JOB_TOKEN}."]
    ["-q" "--quiet" "Don't print a progress bar nor a start of build message"
     :default false]
    ["-v" "--verbose" "Print status of image building"
     :default false]

    [nil "--extra-java-args JAVA_ARGS" "Extra arguments to pass to the `java` command, e.g. --extra-java-args \"-Dfoo=bar -ea\""
     :default ""]
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
                 include
                 additional-tag
                 label
                 user
                 creation-time
                 to-registry-username
                 to-registry-password
                 from-registry-username
                 from-registry-password
                 quiet
                 verbose
                 extra-java-args
                 main]
          :as options} :options
         :as  parsed-opts} (cli/parse-opts args cli-options)
        errors (:errors parsed-opts)]
    (cond
      (or help (nil? image-name) (and (= :tar image-type) (nil? tar-file)) (nil? main))
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
            :include include
            :additional-tags additional-tag
            :labels label
            :user user
            :creation-time creation-time
            :to-registry-username to-registry-username
            :to-registry-password to-registry-password
            :from-registry-username from-registry-username
            :from-registry-password from-registry-password
            :quiet quiet
            :verbose verbose
            :extra-java-args extra-java-args
            :main main}))))
