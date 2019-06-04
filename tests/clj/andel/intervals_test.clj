(ns andel.intervals-test
  (:require [clojure.test.check.generators :as g]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [clojure.test :refer :all])
  (:import [andel Intervals Intervals$Interval]))

(defn assert-ids-are-sane [intervals tree]
  (doseq [{:keys [id] :as interval} intervals]
    (let [i (Intervals/getById tree id)]
      (assert (= interval {:from (.-from i)
                           :to (.-to i)
                           :id (.-id i)
                           :data (.-data i)})))))

(defn tree->intervals [tree]
  (let [it (Intervals/query tree 0 Long/MAX_VALUE)]
    (loop [r []]
      (if (.next it)
        (recur (conj r {:id (.id it)
                        :from (.from it)
                        :to (.to it)
                        :data (.data it)}))
        r))))

(def intervals-bulk-gen
  (g/bind
   (g/large-integer* {:min 1 :max 1000})
   (fn [cnt]
     (g/let [a (g/vector (g/large-integer* {:min 0 :max 10000}) cnt)
             b (g/vector (g/large-integer* {:min 0 :max 10000}) cnt)
             g-l? (g/vector g/boolean cnt)
             g-r? (g/vector g/boolean cnt)
             ids (g/return (vec (range cnt)))]
       (->> (mapv (fn [a b g-l? g-r? id]
                    {:id id
                     :from (min a b)
                     :to (max a b)
                     ;      :greedy-left? g-l?
                     ;     :greedy-right? g-r?
                     :data nil})
                  a b g-l? g-r? ids)
            (sort-by :from)
            (into []))))))

(defn ->interval [{:keys [id from to data]}]
  (Intervals$Interval. id from to data))

(def empty-tree (Intervals. 4))

(def bulk-insertion-prop
  (prop/for-all [intervals intervals-bulk-gen]
    (let [tree (Intervals/insert empty-tree (into [] (map ->interval) intervals))]
      (assert-ids-are-sane intervals tree)
      (= (tree->intervals tree) intervals))))

(def bulk-of-bulks-prop
  (prop/for-all [bulk-of-bulks (g/fmap
                                (fn [[intervals nums]]
                                  (loop [intervals intervals
                                         [n & nums] nums
                                         result []]
                                    (if (or (nil? n) (<= (count intervals) n))
                                      (conj result (into [] intervals))
                                      (recur
                                        (drop n intervals)
                                        nums
                                        (conj result (into [] (take n) intervals))))))
                                (g/tuple intervals-bulk-gen
                                         (g/vector g/s-pos-int)))]
    (let [itree (reduce
                 (fn [t bulk]
                   (Intervals/insert t (into [] (map ->interval) bulk)))
                 empty-tree
                 bulk-of-bulks)]
      (= (sort-by :from (mapcat identity bulk-of-bulks))
         (tree->intervals itree)))))

(defn naive-type-in [intervals [offset length]]
  (if (< length 0)
    (into []
          (comp (map (fn [interval]
                       (let [update-point (fn [point offset length] (if (< offset point)
                                                                      (max offset (- point length))
                                                                      point))]
                         (-> interval
                             (update :from update-point offset length)
                             (update :to update-point offset length)))))
                (remove (fn [marker]
                          (and (= (:from marker) (:to marker))
                               (not (:greedy-left? marker))
                               (not (:greedy-right? marker))))))
          intervals)
    (mapv (fn [{:keys [from to greedy-left? greedy-right?] :as interval}]
            (cond
              (and greedy-left?
                   (= offset from))
              (assoc interval :to (+ to length))

              (and greedy-right?
                   (= offset to))
              (assoc interval :to (+ to length))

              (and (< from offset)
                   (< offset to))
              (assoc interval :to (+ to length))

              (<= offset from)
              (assoc interval
                     :to (+ to length)
                     :from (+ from length))

              :else
              interval))
          intervals)))

(comment

  (def t
    (let [intervals [{:id 0, :from 0, :to 0, :data nil}
                     {:id 1, :from 0, :to 0, :data nil}
                     {:id 2, :from 0, :to 0, :data nil}
                     {:id 3, :from 0, :to 0, :data nil}
                     {:id 4, :from 0, :to 0, :data nil}]
          t (Intervals/insert empty-tree (into [] (map ->interval) intervals))]
      t))

  (Intervals/getById t 0)


  )

(defn type-in [tree [offset size]]
  (if (< size 0)
    (Intervals/collapse tree offset (Math/abs size))
    (Intervals/expand tree offset size)))

(def type-in-prop
  (prop/for-all [[intervals typings] (g/bind intervals-bulk-gen
                                       (fn [bulk]
                                         (let [max-val (transduce (map :to) max 0 bulk)]
                                           (g/tuple (g/return bulk)
                                                    (g/vector (g/tuple (g/large-integer* {:min 0 :max max-val})
                                                                       g/int))))))]
    (let [tree (Intervals/insert empty-tree (into [] (map ->interval) intervals))]
      (= (reduce naive-type-in intervals typings)
         (tree->intervals (reduce type-in tree typings))))))

(deftest intervals-test
  (is (:result (tc/quick-check 1000 bulk-insertion-prop)))
  (is (:result (tc/quick-check 1000 bulk-of-bulks-prop)))
  (is (:result (tc/quick-check 1000 type-in-prop))))

(defn intersects? [^long from1 ^long to1 ^long from2 ^long to2]
  (if (<= from1 from2)
    (< from2 to1)
    (< from1 to2)))

(defn play-query [model {:keys [from to]}]
  (vec (filter (fn [m] (intersects? (:from m) (:to m) from to)) model)))

(defn query-gen [max-val]
  (g/fmap (fn [[x y]]
            {:from (min x y)
             :to   (max x y)})
          (g/tuple (g/large-integer* {:min 0 :max max-val})
                   (g/large-integer* {:min 0 :max max-val}))))

(def bulk-and-queries-gen
  (g/bind intervals-bulk-gen
          (fn [bulk] (g/tuple (g/return bulk)
                              (g/vector (query-gen (->> bulk
                                                        (map :to)
                                                        (apply max 0))))))))

#_(deftest query-test
  (is
   (:result
    (tc/quick-check
     1000
     (prop/for-all [[bulk queries] bulk-and-queries-gen]
                   (let [itree (bulk->tree bulk)]
                     (= (map play-query (repeat bulk) queries)
                        (map (fn [{:keys [from to]}]
                               (let [it (Intervals/query itree from to)]
                                 (loop [r []]
                                   (if (.next it)
                                     (recur (conj r {:from (.from it) :to (.to it) :id (.id it) :data (.data it)}))
                                     r))))
                             queries))))))))
;
;(def operation-gen
;  (g/tuple (g/one-of [(g/return [play-type-in type-in])
;                      (g/return [play-delete-range delete-range])])
;           (g/tuple (g/large-integer* {:min 0 :max 10000000})
;                    (g/large-integer* {:min 0 :max 10000000}))))
;
;(deftest type-and-delete-test
;  (is (:result
;       (tc/quick-check
;        1000
;        (prop/for-all
;         [bulk intervals-bulk-gen
;          ops (g/vector operation-gen)]
;         (let [[tree model] (reduce (fn [[tree model] [[play real] args]]
;                                      [(apply real tree args)
;                                       (play model args)])
;                                    [(bulk->tree bulk) bulk]
;                                    ops)]
;           (= (set (drop-dead-markers (tree->intervals tree)))
;              (set (drop-dead-markers model)))))))))
;
;(deftest gc-test
;  (is
;   (:result
;    (tc/quick-check
;     100
;     (prop/for-all [[bulk i] (g/bind intervals-bulk-gen
;                                     (fn [b]
;                                       (g/tuple (g/return b) (g/choose 0 (count b))))
;                                     )]
;                   (let [tree (bulk->tree bulk)
;                         to-delete (i/int-set (range i))
;                         tree' (gc tree to-delete)]
;                     (= (tree->intervals tree')
;                        (remove (fn [m] (to-delete (-> m (.-attrs) (.-id)))) bulk))
;                     ))))))

(comment

  (defn np [node]
    (if (instance? andel.Intervals$Node node)
      (let [^andel.Intervals$Node node node]
        {:starts (.-starts node)
         :ends (.-ends node)
         :ids (.-ids node)
         :children (mapv np (.-children node))})
      (str node)))

  (defn tp [^Intervals tree]
    {:open-root (np (.-openRoot tree))
     :closed-root (np (.-closedRoot tree))
     :mapping (.-parentsMap tree)})

  (defn sample [input]
    (reduce
      (fn [al {:keys [id from to data]}]
        (.add al
              (Intervals$Interval. id
                                   from
                                   to
                                   data))
        al)
     (java.util.ArrayList.)
     input))

  (sample (map (fn [i] {:from i :to (* 2 i) :data (str i "cm")}) (range 25)))



  (let [sample #_(sample (map (fn [i] {:from i :to (* 2 i) :data (str i "cm")}) (range 25)))
        (sample [
                  {:from 0, :to 0, :id 0, :data nil}
                 {:from 0, :to 0, :id 1, :data nil}
                 {:from 0, :to 0, :id 2, :data nil}
                 {:from 0, :to 0, :id 3, :data nil}
                 {:from 0, :to 0, :id 4, :data nil}
                 {:from 0, :to 0, :id 5, :data nil}
                 {:from 0, :to 1, :id 6 :data nil}
                 {:from 0, :to 1, :id 7, :data nil}])
        t (-> (Intervals. 4)
              (Intervals/insert sample)
              #_(Intervals/collapse 10 10)
              #_(Intervals/expand 1 1)
              )
        it (Intervals/query t 0 1)]
    (tp t)
    #_(loop [r []]
      (if (.next it)
        (recur (conj r {:from (.from it) :to (.to it) :id (.id it) :data (.data it)}))
        r))
    )

  )

