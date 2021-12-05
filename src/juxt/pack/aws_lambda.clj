(ns ^:no-doc juxt.pack.aws-lambda
  (:require
    [clojure.java.io :as io]
    [juxt.pack.impl.elodin :as elodin]
    [juxt.pack.impl.vfs :as vfs]
    [juxt.pack.impl.lib-map :as lib-map]))

(defn- write-zip
  [basis output]
  (vfs/write-vfs
    {:type :zip
     :stream (io/output-stream output)}
    (concat
      (map
        (fn [{:keys [path] :as all}]
          {:input (io/input-stream path)
           :path ["lib" (elodin/jar-name all)]})
        (lib-map/lib-jars (:libs basis)))

      (map
        (fn [{:keys [path lib] :as all}]
          {:paths (vfs/files-path
                    (file-seq (io/file path))
                    (io/file path))
           :path ["lib" (format "%s.jar" (elodin/directory-name all))]})
        (lib-map/lib-dirs (:libs basis)))

      (mapcat
        (fn [dir]
          (let [root (io/file dir)]
            (vfs/files-path
              (file-seq root)
              root)))
        (keep
          #(when (:path-key (val %))
             (key %))
          (:classpath basis))))))

(defn aws-lambda
  [{:keys [basis lambda-file]}]
  (write-zip basis lambda-file))
