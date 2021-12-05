(ns juxt.pack.impl.tools-deps
  "Generic integration with tools-deps which was duplicated across
  implementations.  Provides support for tools.cli too."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.deps.alpha :as tools.deps])
  (:import
    [java.io File]))

(defn- parse-kws
  "Parses a concatenated string of keywords into a collection of keywords
  Ex: (parse-kws \":a:b:c\") ;; returns: (:a :b :c)"
  [s]
  (->> (string/split (or s "") #":")
    (remove string/blank?)
    (map
      #(if-let [i (string/index-of % \/)]
         (keyword (subs % 0 i) (subs % (inc i)))
         (keyword %)))))

(defn config-edn
  []
  (let [config-file (io/file (tools.deps/user-deps-path))]
    (when (.exists config-file)
      (-> config-file
          slurp
          edn/read-string
          ;; These keys are ones a user can reasonably expect to apply when
          ;; assembling a project's build.  Notably, ones relating to a user's
          ;; credentials can be kept out.
          (select-keys [:mvn/repos])))))

;; opts is return of cli-opts, except ::deps-path
(defn parse-deps-map
  [deps-map {::keys [aliases extra sdeps]}]
  (let [basis
        (tools.deps/create-basis
          {:project (or deps-map :standard)
           :user (config-edn)
           :aliases (cons ::pack aliases)
           :extra (assoc-in sdeps [:aliases ::pack] {:extra-paths extra})})]
    {::lib-map (:libs basis)
     ::basis basis
     ::paths (vec
               (keep
                 #(when (:path-key (val %))
                    (key %))
                 (:classpath basis)))}))

(defn slurp-deps
  [_]
  (tools.deps/slurp-deps (io/file "deps.edn")))
