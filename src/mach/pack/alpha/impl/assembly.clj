(ns mach.pack.alpha.impl.assembly
  "Assemble archives from a description of the filesystem"
  (:require
   [clojure.java.io :as io])
  (:import
   java.io.File
   [java.util.jar Attributes$Name JarEntry JarOutputStream Manifest]
   [java.util.zip ZipEntry ZipException ZipOutputStream]))

(defn- write! [stream file]
  (let [buf (byte-array 1024)]
    (with-open [in (io/input-stream file)]
      (loop [n (.read in buf)]
        (when-not (= -1 n)
          (.write stream buf 0 n)
          (recur (.read in buf)))))))

(defn- dupe? [t]
  (and (instance? ZipException t)
       (.startsWith (.getMessage t) "duplicate entry:")))

(defn spit-zip! [zippath files]
  (let [zipfile (io/file zippath)]
    (io/make-parents zipfile)
    (with-open [s (ZipOutputStream. (io/output-stream zipfile))]
      (doseq [[^String zippath ^String srcpath] files :let [f (io/file srcpath)]]
        (when-not (.isDirectory f)
          (let [entry (doto (ZipEntry. zippath) (.setTime (.lastModified f)))]
            (try
              (doto s (.putNextEntry entry) (write! srcpath) .closeEntry)
              (catch Throwable t
                (if-not (dupe? t)
                  (throw t)
                  (println (.getMessage t)))))))))))

(defn- create-manifest [main ext-attrs]
  (let [manifest (Manifest.)]
    (let [attributes (.getMainAttributes manifest)]
      (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
      (when-let [m (and main (.replaceAll (str main) "-" "_"))]
        (.put attributes Attributes$Name/MAIN_CLASS m))
      (doseq [[k v] ext-attrs]
        (.put attributes (Attributes$Name. (name k)) v)))
    manifest))

(defprotocol ILastModified
  "Implementation details.  Subject to change."
  (last-modified [self]))

(extend-protocol ILastModified
  (Class/forName "[B")
  (last-modified [self] (System/currentTimeMillis))
  java.io.File
  (last-modified [self] (.lastModified self))
  java.net.URL
  (last-modified [self] (.getLastModified (.openConnection self))))

(defn- jarentry [path f & [dir?]]
  (doto (JarEntry. (str (.replaceAll path "\\\\" "/") (when dir? "/")))
    (.setTime (last-modified f))))

(defn- write-jar
  [output-stream files & [attr main]]
  (let [manifest (when (or (seq attr) main)
                   (create-manifest main attr))
        dirs (atom #{})
        parents* #(iterate (comp (memfn getParent) io/file) %)
        parents #(->> %
                      parents*
                      (drop 1)
                      (take-while (complement empty?))
                      (remove (partial contains? @dirs)))]
    (with-open [s (if manifest
                    (JarOutputStream. output-stream manifest)
                    (JarOutputStream. output-stream))]
      (doseq [[jarpath srcpath] files]
        (let [e (jarentry jarpath srcpath)]
          (try (doseq [d (parents jarpath)
                       :let [f (io/file d)]]
                 (swap! dirs conj d)
                 (doto s (.putNextEntry (jarentry d f true)) .closeEntry))
               (doto s
                 (.putNextEntry e)
                 (write! (io/input-stream srcpath))
                 .closeEntry)
               (catch Throwable t
                 (if-not (dupe? t)
                   (throw t)
                   (println (format " warning: %s\n"
                                    (.getMessage t)))))))))))

(defn spit-jar!
  [jarpath & args]
  (let [jarfile (io/file jarpath)]
    (io/make-parents jarfile)
    (apply write-jar (io/output-stream jarfile) args)))

(defn in-memory-jar
  [& args]
  (let [ba (java.io.ByteArrayOutputStream.)]
    (apply write-jar ba args)
    (.toByteArray ba)))
