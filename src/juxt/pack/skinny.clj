(ns ^:no-doc juxt.pack.skinny
  (:require
    [clojure.tools.deps.util.dir :refer [canonicalize]]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [juxt.pack.impl.elodin :as elodin]
    [juxt.pack.impl.lib-map :as lib-map]
    [juxt.pack.impl.vfs :as vfs]))

(defn write-paths
  [basis output-path output-target]
  (io/make-parents (io/file output-path))
  (let [paths (keep
                #(when (:path-key (val %))
                   (canonicalize (io/file (key %))))
                (:classpath basis))]
    (case output-target
      :jar (vfs/write-vfs
             {:type :jar
              :stream (io/output-stream output-path)}
             (mapcat
               (fn [path]
                 (vfs/files-path
                   (file-seq (io/file path))
                   (io/file path)))
               paths))

      :dir (vfs/write-vfs
             {:type :dir
              :root output-path}
             (mapcat
               (fn [path]
                 (map
                   (fn [x]
                     (update x :path (fn [spath]
                                       (cons
                                         (string/join
                                           "-"
                                           (elodin/path->path-seq
                                             (elodin/str->path path)))
                                         spath))))
                   (vfs/files-path (file-seq (io/file path)) (io/file path))))
               paths)))))

(defn write-libs
  [{lib-map :libs} lib-dir output-target]
  (let [root (io/file lib-dir)]
    (vfs/write-vfs
      {:type :dir
       :root root}
      (concat
        (map
          ;; TODO: Master elodin should be in charge of this
          (fn [{:keys [path] :as all}]
            {:path [(format "%s.jar" (elodin/versioned-lib all))]
             :input (io/input-stream path)})
          (lib-map/lib-jars lib-map))
        (case output-target
          :keep 
          (mapcat
            (fn [{:keys [lib path] :as all}]
              (map (fn prefix-paths [pat]
                     (update pat :path
                             #(cons
                                (format "%s-%s"
                                        (elodin/versioned-lib all)
                                        (elodin/directory-unique all))
                                %)))
                   (vfs/files-path (file-seq (io/file path)) (io/file path))))
            (lib-map/lib-dirs lib-map))
          :jar
          (map
            (fn [{:keys [lib path] :as all}]
              {:path
               [(format "%s-%s.jar"
                        (elodin/versioned-lib all)
                        (elodin/directory-unique all))]

               :paths (vfs/files-path (file-seq (io/file path))
                                      (io/file path))

               :type :jar})
            (lib-map/lib-dirs lib-map)))))))

(defn skinny
  [{:keys [basis libs lib-coerce path path-coerce]}]
  (when path
    (write-paths basis path (case path-coerce
                              :jar :jar
                              nil :dir)))
  (when libs
    (write-libs basis libs (case lib-coerce
                             :jar :jar
                             nil :keep))))
