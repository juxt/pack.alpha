(ns ^:no-doc juxt.pack.library
  (:require
    [clojure.tools.deps.util.dir :refer [canonicalize]]
    [clojure.java.io :as io]
    [juxt.pack.impl.vfs :as vfs]))

(defn write-paths
  [basis output-path extra-paths]
  (io/make-parents (io/file output-path))
  (vfs/write-vfs
    {:type :jar
     :stream (io/output-stream output-path)}
    (concat extra-paths
            (mapcat
              (fn [path]
                (vfs/files-path
                  (file-seq (io/file path))
                  (io/file path)))
              (keep
                #(when (:path-key (val %))
                   (canonicalize (io/file (key %))))
                (:classpath basis))))))

(defn library
  [{:keys [basis path pom lib]}]
  (write-paths basis path
               (when pom
                 [{:path ["META-INF" "maven" (namespace lib) (name lib) "pom.xml"]
                   :input (io/input-stream pom)}])))
