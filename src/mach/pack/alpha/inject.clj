(ns mach.pack.alpha.inject
  (:require
    [rewrite-clj.custom-zipper.core :as cz]
    [rewrite-clj.parser :as rewrite.parser]
    [rewrite-clj.node :as rewrite.node]
    [rewrite-clj.zip :as z]
    [rewrite-clj.zip.whitespace :as z.whitespace]))

(defn find-key
  [zloc k]
  (z/find-value (z/down zloc)
                (comp z/right z/right)
                k))

;; Add a key, inserting a newline if necessary
;; if not empty, will match the position of the current keys
(defn add-key
  [zloc k]
  (cond
    (= (z/sexpr zloc) {})
    (-> zloc
        (z/append-child k))

    (find-key zloc k)
    zloc

    :else
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
  (let [first-k (-> zloc z/leftmost)
        sep (separator first-k)
        existing-v (z/right zloc)]
   (if existing-v
    (z/replace (z/right zloc) v)
    (z/insert-right (reduce (comp cz/right cz/insert-right) zloc sep) v))))

(defn add-kv
  [zloc k v]
  (-> zloc
      (add-key k)
      (find-key k)
      (add-value v)
      z/up))

;; enter just-added value of map
(defn added-value
  [zloc]
  (-> zloc
      z/down
      z/rightmost))

;; Find or create key, return location of the value of that k
(defn find-or-create
  [zloc k default-v]
  (if-let [k* (find-key zloc k)]
    (z/right k*)
    (-> zloc
        (add-kv k default-v)
        ;; New keys always are added at the end
        (z/down)
        (z/rightmost))))

;; Attempt to find alias based on the :git/url within
(defn find-or-create-pack-alias
  [zloc]
  (if-let [existing-alias (z/find-depth-first
                            zloc
                            #(some->>
                               %
                               z/sexpr
                               :git/url
                               (re-matches #".*github.com/juxt/pack.alpha.*")))]
    (-> existing-alias z/up z/up)
    (-> zloc
        ;; Add aliases key
        (find-or-create :aliases {})

        ;; Add pack key
        (find-or-create :pack {}))))

(defn inject-pack
  [zloc sha]
  (-> zloc
      (find-or-create-pack-alias)

      ;; Add extra-deps
      (find-or-create :extra-deps {})

      ;; Add pack dependency
      (find-or-create 'pack/pack.alpha {})

      (add-kv :git/url "https://github.com/juxt/pack.alpha.git")
      (add-kv :sha sha)
      (z/up) ;; into extra-deps value
      (z/up) ;; into aliases value

      (add-kv :main-opts ["-m"])))

(comment
  (-> (z/of-file "deps.edn" {:track-position? true})
      (inject-pack "somesha")
      (z/->root-string)))

(defn -main
  [& [sha f]]
  (assert sha)
  (let [f (clojure.java.io/file (or f "deps.edn"))]
    (spit f (-> (z/of-string (slurp f) {:track-position? true})
                (inject-pack sha)
                (z/->root-string)))))
