(ns mach.pack.alpha.impl.tools-deps
  "Generic integration with tools-deps which was duplicated across
  implementations.  Provides support for tools.cli too."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]

    [clojure.tools.deps.alpha :as tools.deps]
    [clojure.tools.deps.alpha.reader :as tools.deps.reader]
    ;; Lazy way of loading extensions
    [clojure.tools.deps.alpha.script.make-classpath]))

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
  [[:short-opt "-A" :required "ALIASES" :id ::all
    :desc "Concatenated aliases of any kind, ex: -A:dev:mem"
    :parse-fn parse-kws
    :assoc-fn (fn [m k v]
                (-> m
                    (update ::resolve-aliases concat v)
                    (update ::makecp-aliases concat v)))]
   [:short-opt "-R" :required "ALIASES" :id ::resolve-aliases
    :desc "Concatenated resolve-deps aliases, ex: -R:bench:1.9"
    :parse-fn parse-kws
    :assoc-fn (fn [m k v] (update m k concat v))]
   [:short-opt "-C" :required "ALIASES" :id ::makecp-aliases
    :desc "Concatenated make-classpath aliases, ex: -C:dev"
    :parse-fn parse-kws
    :assoc-fn (fn [m k v] (update m k concat v))]
   ["-e" "--extra-path STRING" "Add directory to classpath for building. Same as :extra-paths"
    :assoc-fn (fn [m k v] (update m k conj v))
    :id ::extra]
   #_["-d" "--deps STRING" "deps.edn file location"
    :default "deps.edn"
    :validate [(comp (memfn exists) io/file) "deps.edn file must exist"]
    :id ::deps-path]])

(comment
  (require '[clojure.tools.cli :as cli])

  (cli/parse-opts ["-A:foo:bar" "-R:bug" "-C:blah/baz" "-A:definitely"] cli-spec))

(defn system-edn
  []
  (-> "mach/pack/alpha/system_deps.edn"
      io/resource
      slurp
      edn/read-string))

(comment
  (tools.deps/combine-aliases
    {:paths []
     :aliases
     {:dev {:extra-paths ["foo"]
            :extra-deps '{foo {:mvn/verson "bah"}}}
      :foo {:extra-paths ["blah"]}}}
    [:dev :foo]))

;; opts is return of cli-opts, except ::deps-path
(defn parse-deps-map
  [deps-map {::keys [resolve-aliases makecp-aliases extra]}]
  (let [deps-map (tools.deps.reader/merge-deps [(system-edn) deps-map])

        resolve-args (tools.deps/combine-aliases deps-map resolve-aliases)
        cp-args (tools.deps/combine-aliases deps-map makecp-aliases)]
    {::lib-map (tools.deps/resolve-deps deps-map resolve-args)
     ::paths (vec (distinct (concat (:paths deps-map)
                                    (:extra-paths cp-args)
                                    extra)))}))

(defn slurp-deps
  [_]
  (tools.deps.reader/slurp-deps (io/file "deps.edn")))

(defn make-classpath
  [{::keys [lib-map paths]}]
  (tools.deps/make-classpath lib-map paths nil))
