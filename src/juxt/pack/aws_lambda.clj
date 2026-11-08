(ns ^:no-doc juxt.pack.aws-lambda
  (:require
    [clojure.java.io :as io]
    [clojure.tools.deps.util.dir :refer [canonicalize]]
    [juxt.pack.impl.elodin :as elodin]
    [juxt.pack.impl.vfs :as vfs]
    [juxt.pack.impl.lib-map :as lib-map]))

(defn- write-zip
  [basis output]
  (vfs/write-vfs
    {:type :zip
     :stream (io/output-stream output)}
    (let [roots (:classpath-roots basis)
          width (count (str (count roots)))]
      (keep-indexed
        (fn [index root]
          (let [{:keys [path-key lib-name]} (get-in basis [:classpath root])
                prefix (format (str "%0" width "d-") index)]
            (cond
              path-key
              (let [file (canonicalize (io/file root))]
                (case (lib-map/classify root)
                  :jar {:input #(io/input-stream file)
                        :path ["lib" (str prefix "project.jar")]}
                  :dir {:paths (vfs/files-path (file-seq file) file)
                        :path ["lib" (str prefix "project.jar")]}
                  :dne nil
                  (throw (ex-info "Cannot classify path as jar or dir" {:path root}))))

              lib-name
              (let [coordinate (assoc (get-in basis [:libs lib-name])
                                      :lib lib-name
                                      :path root)]
                (case (lib-map/classify root)
                  :jar {:input #(io/input-stream root)
                        :path ["lib" (str prefix (elodin/jar-name coordinate))]}
                  :dir {:paths (vfs/files-path (file-seq (io/file root)) (io/file root))
                        :path ["lib" (str prefix (elodin/directory-name coordinate) ".jar")]}
                  :dne nil
                  (throw (ex-info "Cannot classify path as jar or dir" {:path root :lib lib-name})))))))
         roots))))

(defn aws-lambda
  [{:keys [basis lambda-file]}]
  (write-zip basis lambda-file))
