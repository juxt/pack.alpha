(ns mach.pack.alpha.impl.tools-deps
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

(def cli-spec
  [[:short-opt "-A" :required "ALIASES" :id ::aliases
    :desc "Concatenated aliases of any kind, ex: -A:dev:mem"
    :parse-fn parse-kws
    :assoc-fn (fn [m k v] (update m k concat v))]
   ["-e" "--extra-path STRING" "Add directory to classpath for building. Same as :extra-paths"
    :assoc-fn (fn [m k v] (update m k conj v))
    :id ::extra]
   [nil "--Sdeps EDN" "Deps data to use as the last deps map to be merged by tool.deps when pulling dependencies at build time. Equivalent to `clj -Sdeps EDN`."
    :id ::sdeps
    :parse-fn edn/read-string
    :default {}]
   #_["-d" "--deps STRING" "deps.edn file location"
      :default "deps.edn"
      :validate [(comp (memfn exists) io/file) "deps.edn file must exist"]
      :id ::deps-path]])

(comment
  (require '[clojure.tools.cli :as cli])

  (cli/parse-opts ["-A:foo:bar" "-A:definitely"] cli-spec))

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
