(ns andel.parens
  (:require [clojure.data.int-map :as i]
            [andel.text :as text]
            [andel.core :as core]
            [andel.cursor :as cursor]
            [andel.intervals :as intervals])
  #?(:clj (:import [andel.cursor Cursor TransientCursor])))

(def closing? #{\) \} \]})

(def opening? #{\( \{ \[})

(def paren? (clojure.set/union closing? opening?))

(def opposite {\( \) \) \( \[ \] \] \[ \{ \} \} \{})

(defn paren-token? [{:keys [text lexer is-brace?] :as document}]
  (if (some? lexer)
    (fn [offset]
      (is-brace? lexer offset))
    (constantly true)))

(defn- find-matching-paren [text lexer-paren? offset should-push? should-pop? advance]
  (when (and (<= 0 offset)
             (< offset (text/text-length text)))
    (let [t-cursor ^TransientCursor (cursor/transient (cursor/make-cursor text offset))
          paren (.getChar t-cursor)]
      (when (and (paren? paren)
                 (lexer-paren? offset))
        (loop [s '()]
          (advance t-cursor)
          (let [c (.getChar t-cursor)
                o (.getOffset t-cursor)]
            (cond
              (.isExhausted t-cursor) nil
              (not (lexer-paren? o)) (recur s)
              (should-push? c) (recur (cons c s))
              (should-pop? c) (cond (= c (opposite (first s))) (recur (rest s))
                                    (= c (opposite paren)) o
                                    :else (recur s))
              :else (recur s))))))))

(defn find-matching-paren-forward [text lexer-paren? offset]
  (when-let [to (find-matching-paren text lexer-paren? offset opening? closing? #(.next! ^TransientCursor %))]
    [offset to]))

(defn find-matching-paren-backward [text lexer-paren? offset]
  (when-let [from (find-matching-paren text lexer-paren? offset closing? opening? #(.prev! ^TransientCursor %))]
    [from offset]))

(defn find-unbalanced-paren [text lexer-paren? offset should-push? should-pop? advance]
  (when (and (<= 0 offset)
             (< offset (text/text-length text)))
    (let [t-cursor ^TransientCursor (cursor/transient (cursor/make-cursor text offset))]
      (loop [s '()]
        (advance t-cursor)
        (when (not (.isExhausted t-cursor))
          (let [c (.getChar t-cursor)
                o (.getOffset t-cursor)]
            (cond
              (not (and (paren? c)
                        (lexer-paren? o))) (recur s)
              (should-push? c) (recur (cons c s))
              (should-pop? c) (if (= c (opposite (first s)))
                                (recur (rest s))
                                o)
              :else (recur s))))))))

(defn find-closing-paren [text lexer-paren? offset]
  (find-unbalanced-paren text lexer-paren? offset opening? closing? #(.next! ^TransientCursor %)))

(defn find-opening-paren [text lexer-paren? offset]
  (find-unbalanced-paren text lexer-paren? offset closing? opening? #(.prev! ^TransientCursor %)))

(defn find-parens-pair [text lexer-paren? offset]
  (let [len (text/text-length text)]
    (when (< 0 len)
      (let [prev-offset (max (dec offset) 0)
            offset (min offset (dec len))
            c0 (-> text (cursor/make-cursor prev-offset) (cursor/get-char))
            c1 (-> text (cursor/make-cursor offset) (cursor/get-char))]
        (cond
          (closing? c0) (find-matching-paren-backward text lexer-paren? prev-offset)
          (opening? c1) (find-matching-paren-forward text lexer-paren? offset)
          :else         nil)))))

(defn highlight-parens [{:keys [editor document marker-id-generator] :as state}]
  (let [caret-offset  (core/caret-offset state)
        text (:text document)
        lexer-paren?  (paren-token? document)
        [p-from p-to] (find-parens-pair text
                                        lexer-paren?
                                        caret-offset)
        old-paren-ids (:paren-ids editor)
        state (core/delete-markers state old-paren-ids)]
    (if (and p-from p-to)
      (let [from-id (marker-id-generator)
            to-id   (marker-id-generator)]
        (-> state
            (core/insert-markers [(intervals/>Marker :from p-from
                                                     :to (inc p-from)
                                                     :greedy-right? false
                                                     :greedy-left? false
                                                     :attrs (intervals/>Attrs :id from-id
                                                                              :attrs-keys ["MATCHED_BRACE_ATTRIBUTES"]))
                                  (intervals/>Marker :from p-to
                                                     :to (inc p-to)
                                                     :greedy-right? false
                                                     :greedy-left? false
                                                     :attrs (intervals/>Attrs :id to-id
                                                                              :attrs-keys ["MATCHED_BRACE_ATTRIBUTES"]))])
            (assoc-in [:editor :paren-ids] (i/int-set [from-id to-id]))))
      state)))


(defn enclosing-parens [text lexer-paren? offset]
  (let [opening (find-opening-paren text lexer-paren? offset)
        closing (find-closing-paren text lexer-paren? (dec offset))]
    (when (some? (and opening closing))
      [opening closing])))

(def whitespace? #{\newline \space \tab})

(defn not-whitespace-or-paren? [c]
  (and (not (whitespace? c))
       (not (paren? c))))

(defn find-next-form [text lexer-paren? offset]
  (when (< offset (text/text-length text))
    (let [cursor (cursor/make-cursor text offset)
          form-start-cursor (first (cursor/move-while cursor whitespace? :forward))
          form-start-offset (+ offset (cursor/distance cursor form-start-cursor))
          form-start-char   (cursor/get-char form-start-cursor)]
      (cond (paren? form-start-char) (find-matching-paren-forward text lexer-paren? form-start-offset)
            (= \" form-start-char) [form-start-offset (+ form-start-offset 1 (cursor/count-matching (cursor/next form-start-cursor) #(not= \" %) :forward))]
            (= \; form-start-char) [form-start-offset (+ form-start-offset 1 (cursor/count-matching (cursor/next form-start-cursor) #(not= \newline %) :forward))]
            :else (let [[form-end-cursor end?] (cursor/move-while form-start-cursor not-whitespace-or-paren? :forward)
                        form-end-offset (if end?
                                          (cursor/offset form-end-cursor)
                                          (dec (cursor/offset form-end-cursor)))]
                    [form-start-offset form-end-offset])))))

(defn find-prev-form [text lexer-paren? offset]
  (when (<= 0 offset)
    (let [cursor          (cursor/make-cursor text offset)
          form-end-cursor (first (cursor/move-while cursor whitespace? :backward))
          form-end-offset (- offset (cursor/distance cursor form-end-cursor))
          form-end-char   (cursor/get-char form-end-cursor)]
      (cond (paren? form-end-char) (find-matching-paren-backward text lexer-paren? form-end-offset)
            (= \" form-end-char) [(- form-end-offset 1 (cursor/count-matching (cursor/prev form-end-cursor) #(not= \" %) :backward)) form-end-offset]
            :else (let [[form-start-cursor end?] (cursor/move-while form-end-cursor not-whitespace-or-paren? :backward)
                        form-start-offset (if end?
                                            (cursor/offset form-start-cursor)
                                            (inc (cursor/offset form-start-cursor)))]
                    [form-start-offset form-end-offset])))))
