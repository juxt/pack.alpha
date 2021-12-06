(ns juxt.pack.impl.lib-map
  (:require
    [clojure.java.io :as io]))

(defn- file-ext?
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn- extract-paths
  [lib-map p?]
  (mapcat (fn [[k v]]
            (map
              (fn [path]
                (-> v
                    (assoc :path path
                           :lib k)
                    (dissoc :paths)))
              (filter p? (:paths v))))
          lib-map))

(defn lib-jars
  [lib-map]
  (extract-paths lib-map #(file-ext? (io/file %) "jar")))

(defn lib-dirs
  [lib-map]
  (extract-paths lib-map #(.isDirectory (io/file %))))

(defn classify
  [path]
  (cond
    (file-ext? (io/file path) "jar") :jar
    (.isDirectory (io/file path)) :dir))
