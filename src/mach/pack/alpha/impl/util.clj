(ns mach.pack.alpha.impl.util
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]))

(defn system-edn
  []
  (-> "mach/pack/alpha/system_deps.edn"
      io/resource
      slurp
      edn/read-string))
