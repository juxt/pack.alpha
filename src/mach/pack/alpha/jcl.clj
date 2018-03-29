(ns mach.pack.alpha.jcl
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.deps.alpha :as tools.deps]
   [clojure.tools.deps.alpha.reader :as tools.deps.reader]
   [clojure.tools.deps.alpha.script.make-classpath]
   [mach.pack.alpha.impl.assembly :refer [spit-jar!]]
   [mach.pack.alpha.impl.elodin :as elodin]
   [me.raynes.fs :as fs])
  (:import
   java.io.File
   [java.nio.file Files Paths]
   java.nio.file.attribute.FileAttribute
   java.util.Arrays
   [javax.tools Diagnostic$Kind DiagnosticCollector ToolProvider]))

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn javac
  "Compile java sources. Primitive version for building a self-contained bootstrap"
  [tgt
   options] ;; options passed to java compiler
  (.mkdirs tgt)
  (let [diag-coll (DiagnosticCollector.)
        compiler  (or (ToolProvider/getSystemJavaCompiler)
                      (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))]
    (with-open [file-mgr (.getStandardFileManager compiler diag-coll nil nil)]
      (let [file-mgr (.getStandardFileManager compiler diag-coll nil nil)
            opts (->> ["-d"  (.getPath tgt)]
                      (concat options)
                      (into-array String)
                      Arrays/asList)

            bootstrap
            (map
              #(let [file (io/resource %)]
                 (proxy [javax.tools.SimpleJavaFileObject]
                   [(java.net.URI. (str "string://" (string/replace % #"mach/pack" "")))
                    javax.tools.JavaFileObject$Kind/SOURCE]
                   (getCharContent [ignoredEncodingErrors]
                     (slurp file))))
              ["mach/pack/alpha/bootstrap/com/jdotsoft/jarloader/JarClassLoader.java"
               "mach/pack/alpha/bootstrap/ClojureMainBootstrapJarClassLoader.java"])]
        (-> compiler
            (.getTask *err* file-mgr diag-coll opts nil bootstrap)
            (.call))
        (let [diagnostics (.getDiagnostics diag-coll)]
          (doseq [d diagnostics
                  :let [k (.getKind d)]]
            (let [log #(println (apply format %&))]
              (if (nil? (.getSource d))
                (log "%s: %s\n"
                     (.toString k)
                     (.getMessage d nil))
                (log "%s: %s, line %d: %s\n"
                     (.toString k)
                     (.. d getSource getName)
                     (.getLineNumber d)
                     (.getMessage d nil)))))
          (when-first [_ (filter #(= Diagnostic$Kind/ERROR (.getKind %))
                                 diagnostics)]
            (throw (Exception. "java compiler error"))))))))

(defn- deleting-tmp-dir
  [prefix]
  (let [tmp-path (Files/createTempDirectory prefix
                                            (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile tmp-path)))))
    tmp-path))

(defn- create-bootstrap
  []
  (let [bootstrap-p (deleting-tmp-dir "pack-bootstrap")]
    (javac (.toFile bootstrap-p)
           nil)
    bootstrap-p))

(defn split-classpath-string
  [classpath-string]
  (string/split
    classpath-string
    ;; re-pattern should be safe given the characters that can be separators, but could be safer
    (re-pattern File/pathSeparator)))

(defn classpath-string->jar
  [classpath-string jar-location]
  (let [classpath
        (map io/file (split-classpath-string classpath-string))
        bootstrap-p (create-bootstrap)]
    (spit-jar!
      jar-location
      (concat ;; directories on the classpath
              (mapcat
                (fn [cp-dir]
                  (let [cp-dir-p (.toPath cp-dir)]
                    (map
                      (juxt #(str (.relativize cp-dir-p (.toPath %)))
                            identity)
                      (filter (memfn isFile) (file-seq cp-dir)))))
                (filter (memfn isDirectory) classpath))
              ;; jar deps
              (sequence
                (comp
                  (map file-seq)
                  cat
                  (filter (memfn isFile))
                  (filter #(by-ext % "jar"))
                  (map (juxt (comp
                               elodin/path-seq->str
                               #(cons "lib" %)
                               elodin/full-path-derived-name)
                             identity)))
                classpath)
              ;; compiled bootstrap files
              (sequence
                (comp
                  (filter (memfn isFile))
                  (filter #(by-ext % "class"))
                  (map (juxt #(str (.relativize bootstrap-p (.toPath %))) identity)))
                (file-seq (.toFile bootstrap-p))))
      []
      "ClojureMainBootstrapJarClassLoader")))

(defn paths-get
  [[first & more]]
  (Paths/get first (into-array String more)))

(defn -main
  [& args]
  (let [[deps-edn jar-location build-dir] args
        deps-map (tools.deps.reader/slurp-deps
                   (io/file deps-edn))]
    (classpath-string->jar
      (tools.deps/make-classpath
        (tools.deps/resolve-deps deps-map nil)
        (conj
          (map
            #(.resolveSibling (paths-get [deps-edn])
                              (paths-get [%]))
            (:paths deps-map))
          build-dir)
        nil)
      jar-location)))
