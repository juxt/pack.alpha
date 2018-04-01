(ns mach.pack.alpha.inject
  (:require
    [rewrite-clj.custom-zipper.core :as cz]
    [rewrite-clj.parser :as rewrite.parser]
    [rewrite-clj.node :as rewrite.node]
    [rewrite-clj.zip :as z]
    [rewrite-clj.zip.whitespace :as z.whitespace]))

;; Add a key, inserting a newline if necessary
;; if not empty, will match the position of the current keys
(defn add-key
  [zloc k]
  (if (= (z/sexpr zloc) {})
    (z/append-child zloc k)
    (let [first-k-x (some-> zloc z/down z/position second)]
      (-> zloc
          (z/down)
          (z/rightmost)
          (z.whitespace/insert-newline-right)
          cz/right
          (z.whitespace/insert-space-right (dec first-k-x))
          cz/right
          (z/insert-right k)
          z/up))))

(defn separator
  [zloc]
  (->> zloc
       (cz/right)
       (iterate cz/right)
       (take-while some?)
       (take-while (complement z/end?))
       (take-while z.whitespace/whitespace?)
       (map z/node)))

(defn add-value
  [zloc v]
  (if (not (-> zloc z/down z/right)) ;; <-- only 1 key inside
    (z/append-child zloc v)
    (let [first-k (-> zloc z/down z/leftmost)
          sep (separator first-k)]
      (z/append-child (reduce cz/append-child zloc sep) v))))

;; enter just-added value of map
(defn added-value
  [zloc]
  (-> zloc
      z/down
      z/rightmost))

(defn find-or-create-aliases
  [zloc]
  (if-let [aliases (z/find-value (z/down zloc) :aliases)]
    (-> aliases z/right)
    (-> zloc
        (add-key :aliases)
        (add-value {})
        (added-value))))

(defn inject-pack
  [zloc sha]
  (-> zloc
      (find-or-create-aliases)

      ;; Add pack key
      (add-key :pack)
      (add-value {})

      ;; Go to the pack alias
      (added-value)

      ;; Add extra-deps
      (add-key :extra-deps)
      (add-value {}) ;; Don't add directly, needs whitespace manipulation
      (added-value)
      (add-key 'pack/pack.alpha)
      (add-value {})
      (added-value)
      (add-key :git/url)
      (add-value "https://github.com/juxt/pack.alpha.git")
      (add-key :sha)
      (add-value sha)
      (z/up) ;; into extra-deps value
      (z/up) ;; into aliases value

      (add-key :main-opts)
      (z/append-child ["-m"])))

(comment
  (-> (z/of-file "deps.edn" {:track-position? true})
      (inject-pack)
      (z/->root-string)))

(defn -main
  [& [sha f]]
  (assert sha)
  (let [f (clojure.java.io/file (or f "deps.edn"))]
    (spit f (-> (z/of-string (slurp f) {:track-position? true})
                (inject-pack sha)
                (z/->root-string)))))
