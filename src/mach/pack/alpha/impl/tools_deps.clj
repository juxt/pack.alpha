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
    [clojure.tools.deps.alpha.script.make-classpath])
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


   ["-D" "--sdeps EDN" "Deps data to use as the last deps file to be merged by tool.deps when pulling dependencies for image. Equivalent to `clj -Sdeps EDN`."
    :id ::sdeps
    :parse-fn edn/read-string
    :default "{}"]
   #_["-d" "--deps STRING" "deps.edn file location"
      :default "deps.edn"
      :validate [(comp (memfn exists) io/file) "deps.edn file must exist"]
      :id ::deps-path]])

(comment
  (require '[clojure.tools.cli :as cli])

  (cli/parse-opts ["-A:foo:bar" "-R:bug" "-C:blah/baz" "-A:definitely"] cli-spec))

(defn config-edn
  []
  (let [config-env (System/getenv "CLJ_CONFIG")
        xdg-env (System/getenv "XDG_CONFIG_HOME")
        home (System/getProperty "user.home")
        config-dir (cond config-env config-env
                         xdg-env (str xdg-env File/separator "clojure")
                         :else (str home File/separator ".clojure"))
        config-deps (str config-dir File/separator "deps.edn")
        config-file (io/file config-deps)]
    (when (.exists config-file)
      (-> config-file
          slurp
          edn/read-string
          ;; These keys are ones a user can reasonably expect to apply when
          ;; assembling a project's build.  Notably, ones relating to a user's
          ;; credentials can be kept out.
          (select-keys [:mvn/repos])))))

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
  [deps-map {::keys [resolve-aliases makecp-aliases extra sdeps]}]
  (let [deps-map (tools.deps.reader/merge-deps [sdeps (tools.deps.reader/install-deps) (config-edn) deps-map])

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
