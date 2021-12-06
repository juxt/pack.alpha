(ns ^:no-doc juxt.pack.jib
  (:require [juxt.pack.impl.lib-map :as lib-map]
            [juxt.pack.impl.elodin :as elodin]
            [clojure.string :as str])
  (:import (com.google.cloud.tools.jib.api Jib)
           (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath ModificationTimeProvider FileEntriesLayer)
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

(defn- make-lib-jars-layer [lib-map]
  (reduce (fn [acc {:keys [path] :as all}]
            (let [container-path (AbsoluteUnixPath/get (str target-dir
                                                            "/"
                                                            (elodin/versioned-lib all)
                                                            ".jar"))]
              (-> acc
                  (update :builder #(.addEntry %
                                               (Paths/get path string-array)
                                               container-path))
                  (assoc-in [:container-paths path] container-path))))
          {:builder (-> (FileEntriesLayer/builder)
                        (.setName "library jars"))
           :container-paths nil}
          (lib-map/lib-jars lib-map)))

(defn make-lib-dirs-layer [lib-map]
  (reduce (fn [acc {:keys [path] :as all}]
            (let [container-path (AbsoluteUnixPath/get (str target-dir
                                                            "/"
                                                            (elodin/versioned-lib all)
                                                            "-"
                                                            (elodin/directory-unique all)))]
              (-> acc
                  (update :builder #(.addEntryRecursive %
                                                        (Paths/get path string-array)
                                                        container-path))
                  (assoc-in [:container-paths path] container-path))))
          {:builder (-> (FileEntriesLayer/builder)
                        (.setName "library directories"))
           :container-paths nil}
          (lib-map/lib-dirs lib-map)))

(defn make-project-dirs-layer
  [basis]
  (reduce
    (fn [acc [project-path _]]
      (let [path (Paths/get project-path string-array)]
        (if (Files/exists path (into-array LinkOption []))
          (let [container-path (AbsoluteUnixPath/get (str target-dir
                                                          "/"
                                                          (unique-base-path path)))]
            (-> acc
                (update :builder #(.addEntryRecursive %
                                                      path
                                                      container-path
                                                      FileEntriesLayer/DEFAULT_FILE_PERMISSIONS_PROVIDER
                                                      timestamp-provider))
                (assoc-in [:container-paths project-path] container-path)))
          acc)))
    {:builder (-> (FileEntriesLayer/builder)
                  (.setName "project directories"))
     :container-paths nil}
    (filter (comp :path-key val) (:classpath basis))))

(defn make-base-image [base-image from-registry-username from-registry-password logger]
  (-> (RegistryImage/named ^String base-image)
      (.addCredentialRetriever (or (explicit-credentials from-registry-username from-registry-password)
                                   (-> (CredentialRetrieverFactory/forImage (ImageReference/parse base-image) logger)
                                       (.dockerConfig))))))

(defn make-containerizer [image-type image-name tar-file to-registry-username to-registry-password logger]
  (Containerizer/to (case image-type
                      :docker (DockerDaemonImage/named image-name)
                      :tar (-> (TarImage/at tar-file)
                               (.named image-name))
                      :registry (-> (RegistryImage/named image-name)
                                    (.addCredentialRetriever (or
                                                              (explicit-credentials to-registry-username to-registry-password)
                                                              (-> (CredentialRetrieverFactory/forImage (ImageReference/parse image-name) logger)
                                                                  (.dockerConfig))))))))

(defn add-layers [jib-container-builder layers]
  (reduce (fn [acc layer]
            (.addFileEntriesLayer acc (-> layer :builder (.build))))
          jib-container-builder
          layers))

(defn add-include-layers [jib-container-builder includes]
  (reduce (fn [jib-container-builder* include]
            (.addLayer jib-container-builder*
                       [(Paths/get (first (.split include ":")) string-array)]
                       (AbsoluteUnixPath/get (last (.split include ":")))))
          jib-container-builder
          includes))

(defn jib
  [{:keys [basis

           base-image from-registry-username from-registry-password

           image-type
           image-name
           tar-file
           to-registry-username
           to-registry-password

           labels
           tags

           creation-time
           user]
    :or {base-image "gcr.io/distroless/java:11"
         creation-time (java.time.Instant/now)}}]
  (let [lib-jars-layer (make-lib-jars-layer (:libs basis))
        lib-dirs-layer (make-lib-dirs-layer (:libs basis))
        project-dirs-layer (make-project-dirs-layer basis)
        layers [lib-jars-layer lib-dirs-layer project-dirs-layer]
        logger (make-logger)
        base-image-with-creds (make-base-image base-image from-registry-username from-registry-password logger)]
    (-> (Jib/from base-image-with-creds)
        (add-labels labels)
        (.setUser user)
        ;; TODO: Reinstate with a clojure-y api
        #_(add-include-layers include)
        (.setCreationTime creation-time)
        (add-layers layers)
        ;; TODO: maybe parameterize target-dir
        (.setWorkingDirectory (AbsoluteUnixPath/get target-dir))
        (.setEntrypoint
          (into-array String
                      (concat ["java"
                               ;; Early in case users override in :jvm-opts
                               "-Dclojure.main.report=stderr"
                               "-Dfile.encoding=UTF-8"]
                              (-> basis :classpath-args :jvm-opts)
                              ["-cp"
                               ;; TODO: It would be useful to preserve the order that tdeps produces for the claspath
                               (let [container-paths (apply merge-with merge (map :container-paths layers))]
                                 (str/join
                                   ":"
                                   (map
                                     #(str (get container-paths %))
                                     (:classpath-roots basis))))
                               "clojure.main"]
                              (-> basis :classpath-args :main-opts))))
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
