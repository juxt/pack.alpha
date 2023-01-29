(ns ^:no-doc juxt.pack.one-jar
  (:require
    [clojure.tools.deps.util.dir :refer [canonicalize]]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [juxt.pack.impl.elodin :as elodin]
    [juxt.pack.impl.lib-map :as lib-map]
    [juxt.pack.impl.vfs :as vfs]
    [me.raynes.fs :as fs])
  (:import
   [java.nio.file Files]
   java.nio.file.attribute.FileAttribute
   java.util.Arrays
   [javax.tools Diagnostic$Kind DiagnosticCollector ToolProvider]))

(defn- javac
  "Compile java sources. Primitive version for building a self-contained bootstrap"
  [tgt
   options] ;; options passed to java compiler
  (.mkdirs tgt)
  (let [diag-coll (DiagnosticCollector.)
        compiler  (or (ToolProvider/getSystemJavaCompiler)
                      (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))]
    (with-open [file-mgr (.getStandardFileManager compiler diag-coll nil nil)]
      (let [opts (->> ["-d"  (.getPath tgt)]
                      (concat options)
                      (into-array String)
                      Arrays/asList)

            bootstrap
            (map
              #(let [file (io/resource %)]
                 (proxy [javax.tools.SimpleJavaFileObject]
                   [(java.net.URI. (str "string://" (string/replace % #"juxt/pack" "")))
                    javax.tools.JavaFileObject$Kind/SOURCE]
                   (getCharContent [ignoredEncodingErrors]
                     (slurp file))))
              #_(comment (into [] (comp (filter (memfn isFile)) (map #(string/replace % #"^src/" ""))) (file-seq (io/file "src/juxt/pack/bootstrap/onejar/src/"))))
              ["juxt/pack/bootstrap/onejar/src/com/simontuffs/onejar/IProperties.java" "juxt/pack/bootstrap/onejar/src/com/simontuffs/onejar/JarClassLoader.java" "juxt/pack/bootstrap/onejar/src/com/simontuffs/onejar/Handler.java" "juxt/pack/bootstrap/onejar/src/com/simontuffs/onejar/OneJarURLConnection.java" "juxt/pack/bootstrap/onejar/src/com/simontuffs/onejar/OneJarFile.java" "juxt/pack/bootstrap/onejar/src/com/simontuffs/onejar/Boot.java" "juxt/pack/bootstrap/onejar/src/OneJar.java"])]
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

(defn- write-jar
  [basis jar-location main & [args]]
  (let [bootstrap-p (create-bootstrap)]
    (vfs/write-vfs
      {:stream (io/output-stream jar-location)
       :type :jar
       :manifest {:main "com.simontuffs.onejar.Boot"
                  :ext-attrs
                  (concat
                    [["One-Jar-Main-Class" main]
                     ;; See https://dev.clojure.org/jira/browse/CLJ-971
                     ["One-Jar-URL-Factory" "com.simontuffs.onejar.JarClassLoader$OneJarURLFactory"]]
                    (when args
                      [["One-Jar-Main-Args" args]]))}}

      (concat
        (keep
          (fn [root]
            (let [{:keys [path-key lib-name]} (get-in basis [:classpath root])]
              (cond
                path-key
                (let [src-root (canonicalize (io/file root))]
                  {:path ["lib" (str "project-" root ".jar")]
                   :paths (vfs/files-path (file-seq src-root) src-root)})
                lib-name
                (let [coordinate (assoc (get-in basis [:libs lib-name])
                                        :lib lib-name
                                        :path root)]
                  (case (lib-map/classify root)
                    :jar {:input (io/input-stream root)
                          :path ["lib" (elodin/jar-name coordinate)]}
                    :dir {:paths (vfs/files-path (file-seq (io/file root)) (io/file root))
                          :path ["lib" (format "%s.jar" (elodin/directory-name coordinate))]}
                    :dne nil
                    (throw (ex-info "Cannot classify path as jar or dir" {:path root :lib lib-name})))))))
          (:classpath-roots basis))

        [{:path [".version"], :input (io/input-stream (io/resource "juxt/pack/bootstrap/onejar/resources/.version"))} {:path ["doc" "one-jar-license.txt"], :input (io/input-stream (io/resource "juxt/pack/bootstrap/onejar/resources/doc/one-jar-license.txt"))}]
        (let [root (.toFile bootstrap-p)]
          (vfs/files-path
            (filter #(.endsWith (.getName %) ".class") (file-seq root))
            root))))))

(defn one-jar
  [{:keys [basis jar-file main-class]
    :or {main-class "clojure.main"}}]
  (write-jar
    basis
    jar-file
    main-class
    ;; :main-opts are specified as for passing to clojure.main
    (when (= "clojure.main" main-class)
      (when-let [main-opts (-> basis :classpath-args :main-opts)]
        (string/join " " (map #(string/escape % {\space "\\ "}) main-opts))))))
