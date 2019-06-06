(ns andel.intervals-test
  (:require [clojure.test.check.generators :as g]
            [clojure.test.check.properties :as prop]
            [clojure.test.check :as tc]
            [clojure.test :refer :all])
  (:import [andel Intervals Intervals$Interval]))

(defn assert-ids-are-sane [intervals tree]
  (doseq [{:keys [id] :as expected} intervals]
    (let [i (Intervals/getById tree id)
          actual {:from (.-from i)
                  :to (.-to i)
                  :greedy-left? (.closedLeft i)
                  :greedy-right? (.closedRight i)
                  :id (.-id i)
                  :data (.-data i)}]
      (assert (= expected actual)
              {:actual   actual
               :expected expected}))))

(defn tree->intervals [tree]
  (let [it (Intervals/query tree 0 (/ Long/MAX_VALUE 4))]
    (loop [r []]
      (if (.next it)
        (recur (conj r {:id (.id it)
                        :from (.from it)
                        :greedy-left? (.closedLeft it)
                        :greedy-right? (.closedRight it)
                        :to (.to it)
                        :data (.data it)}))
        r))))

(defn intervals-bulk-gen
  ([] (intervals-bulk-gen (g/sized (fn [size] (g/return (range size))))))
  ([ids-gen]
   (g/bind
    ids-gen
    (fn [ids]
      (let [cnt (count ids)]
        (g/let [a (g/vector (g/large-integer* {:min 0 :max 10000}) cnt)
                b (g/vector (g/large-integer* {:min 0 :max 10000}) cnt)
                g-l? (g/vector g/boolean cnt)
                g-r? (g/vector g/boolean cnt)]
          (->> (mapv (fn [a b g-l? g-r? id]
                       {:id id
                        :from (min a b)
                        :to (max a b)
                        :greedy-left? (or (= a b) g-l?)
                        :greedy-right? (or (= a b) g-r?)
                        :data nil})
                     a b g-l? g-r? ids)
               (sort-by :from)
               (into []))))))))

(defn ->interval [{:keys [id from to data greedy-left? greedy-right?]}]
  (Intervals$Interval. id from to (boolean greedy-left?) (boolean greedy-right?) data))

(def empty-tree (Intervals. 32))

(defn naive-type-in [intervals [offset length]]
  (if (< 0 length)
    (into []
          (map (fn [{:keys [from to greedy-left? greedy-right?] :as interval}]
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
              interval)))
          intervals)
    (let [length (- length)]
      (into []
            (comp (remove
                   (fn [{:keys [from to greedy-left? greedy-right?]}]
                     (and (< offset from) (< to (+ offset length)))
                     #_(or (and (< offset from) (< to (+ offset length)))
                         (and (not= from to)
                              (or (and greedy-left? (not greedy-right?) (< offset from) (<= to (+ offset length)))
                                  (and (not greedy-left?) greedy-right? (<= offset from) (< to (+ offset length))))))))
                  (map (fn [interval]
                         (let [update-point (fn [point] (if (< offset point)
                                                          (max offset (- point length))
                                                          point))]
                           (-> interval
                               (update :from update-point)
                               (update :to update-point)))))
                  (remove (fn [marker]
                            (and (= (:from marker) (:to marker))
                                 (not (and (:greedy-left? marker)
                                           (:greedy-right? marker)))))))
            intervals))))

(defn type-in [tree [offset size]]
  (if (< size 0)
    (Intervals/collapse tree offset (Math/abs size))
    (Intervals/expand tree offset size)))

(def type-in-prop
  (prop/for-all [[intervals typings] (g/bind (intervals-bulk-gen)
                                       (fn [bulk]
                                         (let [max-val (transduce (map :to) max 0 bulk)]
                                           (g/tuple (g/return bulk)
                                                    (g/vector (g/tuple (g/large-integer* {:min 0 :max max-val})
                                                                       g/int))))))]
    (let [tree (Intervals/insert empty-tree (into [] (map ->interval) intervals))]
      (= (reduce naive-type-in intervals typings)
         (tree->intervals (reduce type-in tree typings))))))

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
  (g/bind (intervals-bulk-gen)
          (fn [bulk] (g/tuple (g/return bulk)
                              (g/vector (query-gen (->> bulk
                                                        (map :to)
                                                        (apply max 0))))))))

(defn insert-step-gen [{:keys [ids generated]}]
  (g/sized
   (fn [size]
     (let [next-id (inc (reduce max 0 ids))
           new-ids (set (range next-id (+ next-id (inc (rand-int size)))))]
       (g/fmap
        (fn [bulk]
          {:ids (clojure.set/union ids new-ids)
           :generated (conj generated [:insert bulk])})
        (intervals-bulk-gen (g/return new-ids)))))))

(defn delete-step-gen [{:keys [ids generated]}]
  (g/fmap
   (fn [ids-to-delete]
     {:ids (clojure.set/difference ids ids-to-delete)
      :generated (conj generated [:delete ids-to-delete])})
    (g/not-empty (g/set (g/elements ids)))))

(defn type-in-step-gen [{:keys [ids generated]}]
  (g/fmap
    (fn [typings]
      {:ids ids
       :generated (conj generated [:type-in typings])})
   (g/tuple (g/large-integer* {:min 0 :max 10000})
            (g/such-that (fn [i] (not= 0 i)) g/int))))

(defn my-gen [s-gen]
  (g/bind s-gen
          (fn [{:keys [ids generated] :as state}]
            (g/sized
             (fn [size]
               (if (= 0 size)
                 (g/return state)
                 (let [next-gen (g/frequency (concat [[1 (insert-step-gen state)]]
                                                     (when (seq ids)
                                                       [[1 (delete-step-gen state)]
                                                        [3 (type-in-step-gen state)]])))]
                   (g/resize (dec size) (my-gen next-gen)))))))))

(def tree-actions-generator
  (g/fmap
   (fn [{:keys [ids generated]}] generated)
   (g/sized
    (fn [size]
      (my-gen (g/return {:ids #{}
                         :generated []}))))))

(defn naive-play-op [intervals-vec [op arg]]
  (case op
    :insert (sort-by :from (concat intervals-vec arg))
    :delete (into [] (remove (fn [i] (contains? arg (:id i)))) intervals-vec)
    :type-in (naive-type-in intervals-vec arg)))

(defn play-op [tree [op arg]]
  (case op
    :insert (Intervals/insert tree (into [] (map ->interval) arg))
    :delete (Intervals/remove tree arg)
    :type-in (let [[from len] arg]
               (if (< 0 len)
                 (Intervals/expand tree from len)
                 (Intervals/collapse tree from (- len))))))

(def intervals-prop
  (prop/for-all [ops tree-actions-generator]
                 (let [naive (reduce naive-play-op [] ops)
                       tree  (reduce play-op empty-tree ops)]
                   (assert-ids-are-sane naive tree)

                   ;;todo assert intervals are ordered
                   (= (set naive)
                      (set (tree->intervals tree))))))

(deftest intervals-test
  (is (:result (tc/quick-check 100 intervals-prop :max-size 1000))))



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

  (def fail
    [[[:insert
    [{:id 6,
      :from 1,
      :to 242,
      :greedy-left? false,
      :greedy-right? true,
      :data nil}
     {:id 8,
      :from 1,
      :to 5,
      :greedy-left? true,
      :greedy-right? false,
      :data nil}
     {:id 5,
      :from 2,
      :to 4,
      :greedy-left? false,
      :greedy-right? false,
      :data nil}
     {:id 9,
      :from 6,
      :to 44,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 7,
      :from 10,
      :to 11,
      :greedy-left? false,
      :greedy-right? true,
      :data nil}
     {:id 1,
      :from 49,
      :to 979,
      :greedy-left? false,
      :greedy-right? false,
      :data nil}
     {:id 2,
      :from 53,
      :to 73,
      :greedy-left? false,
      :greedy-right? true,
      :data nil}
     {:id 10,
      :from 55,
      :to 737,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 3,
      :from 88,
      :to 101,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 4,
      :from 1773,
      :to 1975,
      :greedy-left? true,
      :greedy-right? false,
      :data nil}]]
   [:type-in [6 8]]
   [:type-in [7 10]]
   [:type-in [2 -3]]
   [:insert
    [{:id 13,
      :from 0,
      :to 13,
      :greedy-left? false,
      :greedy-right? false,
      :data nil}
     {:id 14,
      :from 0,
      :to 1,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 16,
      :from 0,
      :to 11,
      :greedy-left? false,
      :greedy-right? false,
      :data nil}
     {:id 15,
      :from 1,
      :to 30,
      :greedy-left? true,
      :greedy-right? false,
      :data nil}
     {:id 17,
      :from 1,
      :to 3,
      :greedy-left? false,
      :greedy-right? true,
      :data nil}
     {:id 12,
      :from 1,
      :to 95,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 11,
      :from 3,
      :to 43,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}]]
   [:insert
    [{:id 20,
      :from 0,
      :to 4,
      :greedy-left? false,
      :greedy-right? false,
      :data nil}
     {:id 19,
      :from 1,
      :to 1,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 18,
      :from 2,
      :to 2,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}]]
   [:insert
    [{:id 21,
      :from 1,
      :to 21,
      :greedy-left? false,
      :greedy-right? true,
      :data nil}
     {:id 22,
      :from 2,
      :to 2,
      :greedy-left? true,
      :greedy-right? true,
      :data nil}
     {:id 23,
      :from 8,
      :to 15,
      :greedy-left? true,
      :greedy-right? false,
      :data nil}]]
   [:delete #{15 13 14 10 18}]
   [:delete #{6 12}]
  [:insert
    [{:id 24,
      :from 0,
      :to 1,
      :greedy-left? true,
      :greedy-right? false,
      :data nil}]]


   [:type-in [1 -2]]
   [:type-in [0 -1]]]])

  (def t (reduce play-op empty-tree (first fail)))

  (Intervals/insert t [(->interval {:id 24,
                          :from 0,
                          :to 1,
                          :greedy-left? true,
                          :greedy-right? false,
                          :data nil})
                       ])

  (let [[ops] fail
        naive (reduce naive-play-op [] ops)
        tree (reduce play-op empty-tree ops)
        actual (tree->intervals tree)]
    {:success? (= (set naive) (set actual))
     :naive naive
     :actual actual
     :diff-naive-vs-actual (clojure.data/diff (set naive) (set actual))
     :tree (tp tree)})

  *e

  )

