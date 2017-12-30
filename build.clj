(require '[clojure.java.io :as io])
(require '[clojure.string :as string])
(require '[clojure.tools.deps.alpha :as tools.deps]
         '[clojure.tools.deps.alpha.makecp]
         '[clojure.tools.deps.alpha.reader :as tools.deps.reader])
(require '[me.raynes.fs :as fs])
(import '[java.util.jar JarEntry JarOutputStream Manifest Attributes$Name])
(import '[java.util.zip ZipException])
(import '[javax.tools ToolProvider DiagnosticCollector Diagnostic$Kind])
(import '[java.util Arrays])
(import '[java.io File])
(import '[java.nio.file Paths Path Files]
        '[java.nio.file.attribute FileAttribute])

;; code adapted from boot
(defn- create-manifest [main ext-attrs]
  (let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))
    manifest))

(defn- write! [stream file]
  (let [buf (byte-array 1024)]
    (with-open [in (io/input-stream file)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write stream buf 0 n)
          (recur (.read in buf)))))))

(defn dupe? [t]
  (and (instance? ZipException t)
       (.startsWith (.getMessage t) "duplicate entry:")))

(defn jarentry [path f & [dir?]]
  (doto (JarEntry. (str (.replaceAll path "\\\\" "/") (when dir? "/")))
    (.setTime (.lastModified f))))

(defn spit-jar! [jarpath files attr main]
  (let [manifest  (create-manifest main attr)
        jarfile   (io/file jarpath)
        dirs      (atom #{})
        parents*  #(iterate (comp (memfn getParent) io/file) %)
        parents   #(->> % parents* (drop 1)
                        (take-while (complement empty?))
                        (remove (partial contains? @dirs)))]
    (io/make-parents jarfile)
    (with-open [s (JarOutputStream. (io/output-stream jarfile) manifest)]
      (doseq [[jarpath srcpath] files :let [f (io/file srcpath)]]
        (let [e (jarentry jarpath f)]
          (try
            (doseq [d (parents jarpath) :let [f (io/file d)]]
              (swap! dirs conj d)
              (doto s (.putNextEntry (jarentry d f true)) .closeEntry))
            (doto s (.putNextEntry e) (write! (io/input-stream srcpath)) .closeEntry)
            (catch Throwable t
              (if-not (dupe? t) (throw t) (println " warning: %s\n" (.getMessage t))))))))))

(defn by-ext
  [f ext]
  (.endsWith (.getName f) (str "." ext)))

(defn javac
  "Compile java sources. Primitive version for building a self-contained bootstrap"
  [input-dir
   tgt
   options ;; options passed to java compiler
   ]
  (.mkdirs tgt)
  (let [throw?    (atom nil)
        diag-coll (DiagnosticCollector.)
        compiler  (or (ToolProvider/getSystemJavaCompiler)
                      (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))
        file-mgr  (.getStandardFileManager compiler diag-coll nil nil)
        opts      (->> ["-d"  (.getPath tgt)
                        "-cp" input-dir]
                       (concat options)
                       (into-array String)
                       Arrays/asList)
        ;; prn use a "warning prn"
        handler   {Diagnostic$Kind/ERROR prn
                   Diagnostic$Kind/WARNING prn
                   Diagnostic$Kind/MANDATORY_WARNING prn}
        srcs      (some->>
                    (io/file input-dir)
                    (file-seq)
                    (filter #(by-ext % "java"))
                    (into-array File)
                    Arrays/asList
                    (.getJavaFileObjectsFromFiles file-mgr))]
    (when (seq srcs)
      (println (format "Compiling %d Java source files...\n" (count srcs)))
      (-> compiler (.getTask *err* file-mgr diag-coll opts nil srcs) .call)
      (doseq [d (.getDiagnostics diag-coll) :let [k (.getKind d)]]
        (when (= Diagnostic$Kind/ERROR k) (reset! throw? true))
        (let [log (handler k prn)]
          (if (nil? (.getSource d))
            (log "%s: %s\n"
                 (.toString k)
                 (.getMessage d nil))
            (log "%s: %s, line %d: %s\n"
                 (.toString k)
                 (.. d getSource getName)
                 (.getLineNumber d)
                 (.getMessage d nil)))))
      (.close file-mgr)
      (when @throw? (throw (Exception. "java compiler error"))))))

(defn- create-bootstrap
  []
  (let [bootstrap-p (Files/createTempDirectory "fatjar-bootstrap"
                                               (into-array FileAttribute []))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (fs/delete-dir (.toFile bootstrap-p)))))
    (javac (.getPath (io/file "bootstrap"))
           (.toFile bootstrap-p)
           nil)
    bootstrap-p))

(defn classpath-string->jar
  [classpath-string jar-location]
  (let [classpath
        (map io/file
             (string/split
               classpath-string
               ;; re-pattern should be safe given the characters that can be separators, but could be safer
               (re-pattern File/pathSeparator)))
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
                  (map (juxt #(str (Paths/get "lib" (into-array String (map str (iterator-seq (.iterator (.toPath %)))))))
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

(let [[deps-edn jar-location build-dir] *command-line-args*]
  (classpath-string->jar
    (tools.deps/make-classpath
      (tools.deps/resolve-deps
        (tools.deps.reader/slurp-deps
          (io/file deps-edn))
        nil)
      (when build-dir
        [build-dir])
      nil)
    jar-location))
