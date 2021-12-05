(ns juxt.pack.impl.elodin
  "Master namer elodin will fulfill your naming needs.

  Provides functionality for naming a file suitable for use in archives."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [java.nio.file Files Paths]))

(defn- paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

(defn path-seq->str
  [path-seq]
  (str (paths-get path-seq)))

(defn path->path-seq
  [path]
  (->> path
       .iterator
       iterator-seq
       (map str)))

(defn file->path-seq
  [file]
  (let [p (.toPath (io/file file))]
    (concat
      (some-> (.getRoot p) str vector)
      (->> p
           .iterator
           iterator-seq
           (map str)))))

(defn str->path
  [pstr]
  (.toPath (io/file pstr)))

(defn str->abs-path
  [pstr]
  (.toPath (.getCanonicalFile (io/file pstr))))

(defn full-path-derived-name
  [file]
  (file->path-seq file))

(defn path-seq-parents
  [path-seq]
  (take-while identity (rest (iterate butlast path-seq))))

(def interpolation-re #"((\{(.+?)\})\$|\$(\{(.+?)\})|%)")

(defmacro ^:private <<
  [s & args]
  `(format
     ~(string/replace s interpolation-re "%s")
     ~@(map (fn [[_ _ _ prefix _ suffix] x]
              `(if-let [x# ~x]
                 (str ~prefix x# ~suffix)
                 ""))
            (re-seq interpolation-re s)
            args)))

(defn- escape-version
  [version]
  (string/escape version {\/ \_
                          \\ \_}))

(defn versioned-lib
  [{:keys [lib] :as all}]
  (<< "%{__}${__}$"
      (namespace lib)
      (name lib)
      (some-> ((some-fn :mvn/version :sha) all)
              escape-version)))

(defn directory-unique
  [{:keys [path deps/root]}]
  (string/join
    "-"
    (path->path-seq
      (.relativize (str->abs-path root)
                   (str->abs-path path)))))

(defn jar-name
  [all]
  (str (versioned-lib all) ".jar"))

(defn directory-name
  [all]
  (str (versioned-lib all) "-" (directory-unique all)))
