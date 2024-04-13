(ns ^:no-doc juxt.pack.jib
  (:require [clojure.tools.deps.util.dir :refer [*the-dir*]]
            [juxt.pack.impl.lib-map :as lib-map]
            [juxt.pack.impl.elodin :as elodin]
            [clojure.string :as str])
  (:import (com.google.cloud.tools.jib.api Jib JibContainerBuilder)
           (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath FileEntriesLayer ModificationTimeProvider Platform)
           (java.nio.file Paths Files LinkOption FileSystems)
           (com.google.cloud.tools.jib.api Containerizer
                                           DockerDaemonImage
                                           TarImage
                                           RegistryImage
                                           ImageReference
                                           Credential
                                           CredentialRetriever)
           (com.google.cloud.tools.jib.frontend CredentialRetrieverFactory)
           (com.google.cloud.tools.jib.event.events ProgressEvent TimerEvent)
           (java.util.function Consumer)
           (java.util Optional)
           (java.time Instant)))

(def string-array (into-array String []))
(def target-dir "/app")

(defn make-logger
  ([_] (make-logger))
  ([]
   (reify Consumer
     (accept [this log-event]))))

(defn unique-base-path
  "Creates a unique string from a path by joining path elements with `-`.

  Used for creating base directory for project paths such that it won't clash with library directories."
  [path]
  (->> path
       (.iterator)
       (iterator-seq)
       (map str)
       (str/join "-")))

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
  (reify ModificationTimeProvider
    (get [_this source-path _destination-path]
      (if (.matches classfile-matcher source-path)
        (Instant/ofEpochSecond 8589934591)
        FileEntriesLayer/DEFAULT_MODIFICATION_TIME))))

;; Very loose port of https://github.com/GoogleContainerTools/jib/blob/97ed78631c516117cc8fc837d76c58ad8e668e6e/jib-plugins-common/src/main/java/com/google/cloud/tools/jib/plugins/common/DefaultCredentialRetrievers.java#L157
(let [xdg-auth-file (Paths/get "containers" (into-array ["auth.json"]))
      docker-config-file (Paths/get "config.json" string-array)
      k8s-docker-file(Paths/get "config.json" string-array)]
  (defn- docker-config-files
    []
    (distinct
      (keep identity
            [(some-> (System/getenv "XDG_RUNTIME_DIR") (Paths/get string-array) (.resolve xdg-auth-file))
             (some-> (System/getenv "XDG_CONFIG_HOME") (Paths/get string-array) (.resolve xdg-auth-file))
             (some-> (System/getProperty "user.home") (Paths/get string-array) (.resolve ".config") (.resolve xdg-auth-file))
             (some-> (System/getenv "HOME") (Paths/get string-array) (.resolve ".config") (.resolve xdg-auth-file))

             (some-> (System/getenv "DOCKER_CONFIG") (Paths/get string-array) (.resolve docker-config-file))
             (some-> (System/getenv "DOCKER_CONFIG") (Paths/get string-array) (.resolve k8s-docker-file))

             (some-> (System/getProperty "user.home") (Paths/get string-array) (.resolve ".docker") (.resolve docker-config-file))
             (some-> (System/getProperty "user.home") (Paths/get string-array) (.resolve ".docker") (.resolve k8s-docker-file))

             (some-> (System/getenv "HOME") (Paths/get string-array) (.resolve ".docker") (.resolve docker-config-file))
             (some-> (System/getenv "HOME") (Paths/get string-array) (.resolve ".docker") (.resolve k8s-docker-file)) ]))))

(let [legacy-docker-config-file (Paths/get "config.json" string-array)]
  (defn- legacy-docker-config-files
    []
    (distinct
      (keep identity
            [(some-> (System/getenv "DOCKER_CONFIG") (Paths/get string-array) (.resolve legacy-docker-config-file))
             (some-> (System/getProperty "user.home") (Paths/get string-array) (.resolve ".docker") (.resolve legacy-docker-config-file))
             (some-> (System/getenv "HOME") (Paths/get string-array) (.resolve ".docker") (.resolve legacy-docker-config-file))]))))

(defn- default-credential-retrievers
  [^CredentialRetrieverFactory factory & {:keys [credential]}]
  (cond->> (concat (map #(.dockerConfig factory %) (docker-config-files))
                   (map #(.dockerConfig factory %) (legacy-docker-config-files))
                   [(.wellKnownCredentialHelpers factory)
                    (.googleApplicationDefaultCredentials factory)])
    credential
    (cons credential)))

(defn- add-credential-retrievers
  [registry-image retrievers]
  (doseq [retriever retrievers
          :when retriever]
    (.addCredentialRetriever registry-image retriever)))

(defn make-base-image [base-image from-registry-username from-registry-password logger]
  (let [factory (CredentialRetrieverFactory/forImage (ImageReference/parse base-image) logger)]
    (doto (RegistryImage/named ^String base-image)
      (add-credential-retrievers
        (default-credential-retrievers
          factory
          :credential (explicit-credentials from-registry-username from-registry-password))))))

(defn make-containerizer [image-type image-name tar-file to-registry-username to-registry-password logger]
  (Containerizer/to
    (case image-type
      :docker (DockerDaemonImage/named image-name)
      :tar (-> (TarImage/at tar-file)
               (.named image-name))
      :registry (doto (RegistryImage/named image-name)
                  (add-credential-retrievers
                    (default-credential-retrievers
                      (CredentialRetrieverFactory/forImage (ImageReference/parse image-name) logger)
                      :credential (explicit-credentials to-registry-username to-registry-password)))))))

(defn add-include-layers [jib-container-builder includes]
  (reduce (fn [jib-container-builder* include]
            (.addLayer jib-container-builder*
                       [(Paths/get (first (.split include ":")) string-array)]
                       (AbsoluteUnixPath/get (last (.split include ":")))))
          jib-container-builder
          includes))

(defn- add-lib-jar-entry
  [builder all]
  (let [container-path (AbsoluteUnixPath/get
                         (str target-dir "/" (elodin/jar-name all)))]
    {:container-path container-path
     :builder (.addEntry builder
                         (Paths/get (:path all) string-array)
                         container-path)}))

(defn- add-lib-dir-entry
  [builder all]
  (let [container-path (AbsoluteUnixPath/get (str target-dir "/" (elodin/directory-name all)))]
    {:container-path container-path
     :builder (.addEntryRecursive builder
                                  (Paths/get (:path all) string-array)
                                  container-path)}))

(defn- add-paths-entry
  [builder root]
  (let [raw-path (Paths/get root string-array)
        src-path (.resolve (.toPath *the-dir*) raw-path)]
    (when (Files/exists src-path (into-array LinkOption []))
      (let [container-path (AbsoluteUnixPath/get
                             (str target-dir "/" (unique-base-path raw-path)))]
        {:builder
         (.addEntryRecursive builder
                             src-path
                             container-path
                             FileEntriesLayer/DEFAULT_FILE_PERMISSIONS_PROVIDER
                             timestamp-provider)
         :container-path container-path}))))


(defn- make-builtin-layers
  [basis]
  (->
    (reduce
      (fn [layers root]
        (let [{:keys [path-key lib-name]} (get-in basis [:classpath root])]
          (cond
            path-key
            (let [{:keys [container-path builder]} (add-paths-entry (:paths-layer layers) root)]
              (-> layers
                  (assoc :paths-layer builder)
                  (assoc-in [:container-roots root] container-path)))

            lib-name
            (let [coordinate (assoc (get-in basis [:libs lib-name])
                                    :lib lib-name
                                    :path root)
                  {:keys [container-path builder]}
                  (case (lib-map/classify root)
                    :jar (add-lib-jar-entry (:libs-layer layers) coordinate)
                    :dir (add-lib-dir-entry (:libs-layer layers) coordinate)
                    :dne {:builder (:libs-layer layers)}
                    (throw (ex-info "Cannot classify path as jar or dir" {:path root :lib lib-name})))]
              (cond-> layers
                true
                (assoc :libs-layer builder)
                container-path
                (assoc-in [:container-roots root] container-path))))))

      {:paths-layer (-> (FileEntriesLayer/builder)
                        (.setName "Paths"))
       :libs-layer (-> (FileEntriesLayer/builder)
                       (.setName "Libs"))
       :container-roots {}}
      (:classpath-roots basis))
    (update :paths-layer (memfn build))
    (update :libs-layer (memfn build))))

(defn- set-layers
  [^JibContainerBuilder jib-container-builder layers]
  (.setFileEntriesLayers jib-container-builder (into-array FileEntriesLayer layers)))

(defn jib
  [{:keys [basis

           base-image from-registry-username from-registry-password

           env
           image-type
           image-name
           tar-file
           to-registry-username
           to-registry-password

           labels
           tags

           volumes
           layers
           platforms

           creation-time
           user]
    :or {base-image "gcr.io/distroless/java:11"
         creation-time (java.time.Instant/now)
         layers [:libs :paths]
         env {}}}]
  (let [tar-file (if (string? tar-file)
                   (Paths/get tar-file string-array)
                   tar-file)
        logger (make-logger)
        base-image-with-creds (make-base-image base-image from-registry-username from-registry-password logger)
        {:keys [libs-layer paths-layer container-roots]} (make-builtin-layers basis)]
    (-> (Jib/from base-image-with-creds)
        (add-labels labels)
        (.setUser user)
        (.setCreationTime creation-time)
        (set-layers
          (map
            (fn [layer]
              (cond
                (= :libs layer) libs-layer
                (= :paths layer) paths-layer
                :else layer))
            layers))
        ;; TODO: maybe parameterize target-dir
        (.setWorkingDirectory (AbsoluteUnixPath/get target-dir))
        (.setEnvironment env)
        (.setVolumes (into #{} (map #(AbsoluteUnixPath/get %)) volumes) )
        (.setEntrypoint
          (into-array String
                      (concat ["java"
                               ;; Early in case users override in :jvm-opts
                               "-Dclojure.main.report=stderr"
                               "-Dfile.encoding=UTF-8"]
                              (-> basis :argmap :jvm-opts)
                              ["-cp"
                               (str/join ":"
                                         (map
                                           #(str (get container-roots %))
                                           (:classpath-roots basis)))
                               "clojure.main"]
                              (-> basis :argmap :main-opts))))
        (cond-> (seq platforms)
          (.setPlatforms
           (into #{} (map #(Platform. (name %) (namespace %)) platforms))))
        (.containerize
          (add-additional-tags
            (make-containerizer
              image-type
              image-name
              tar-file
              to-registry-username
              to-registry-password
              logger)
            tags)))))
