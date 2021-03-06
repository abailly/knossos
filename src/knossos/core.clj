(ns knossos.core
  (:require [clojure.math.combinatorics :as combo]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [clojure.tools.logging :refer :all]
            [interval-metrics.core :as metrics]
            [potemkin :refer [definterface+]]
            [knossos.prioqueue :as prioqueue]
            [knossos.util :as util]
            [knossos.op :as op]
            [knossos.history :as history]
            [clojure.pprint :refer [pprint]])
  (:import (org.cliffc.high_scale_lib NonBlockingHashMapLong)
           (java.util.concurrent.atomic AtomicLong
                                        AtomicBoolean)))

(def op op/op)

(def invoke-op  op/invoke)
(def ok-op      op/ok)
(def fail-op    op/fail)

(def invoke? op/invoke?)
(def ok?     op/ok?)
(def fail?   op/fail?)

(definterface+ Model
  (step [model op]
        "The job of a model is to *validate* that a sequence of operations
        applied to it is consistent. Each invocation of (step model op)
        returns a new state of the model, or, if the operation was
        inconsistent with the model's state, returns a (knossos/inconsistent
        msg). (reduce step model history) then validates that a particular
        history is valid, and returns the final state of the model."))

(defrecord Inconsistent [msg]
  Model
  (step [this op] this))

(defn inconsistent
  "Represents an invalid termination of a model; e.g. that an operation could
  not have taken place."
  [msg]
  (Inconsistent. msg))

(defn inconsistent?
  "Is a model inconsistent?"
  [model]
  (instance? Inconsistent model))

; A read-write register
(defrecord Register [value]
  Model
  (step [r op]
    (condp = (:f op)
      :write (Register. (:value op))
      :read  (if (or (nil? (:value op))     ; We don't know what the read was
                     (= value (:value op))) ; Read was a specific value
               r
               (inconsistent
                 (str "read " (pr-str (:value op))
                      " from register " value))))))

(defn world
  "A world represents the state of the system at one particular point in time.
  It comprises a known timeline of operations, and a set of operations which
  are pending. Finally, there's an integer index which indicates the number of
  operations this world has consumed from the history."
  [model]
  {:model   model
   :fixed   []
   :pending #{}
   :index   0})

(defn inconsistent-world?
  "Is the model for this world in an inconsistent state?"
  [world]
  (instance? Inconsistent (:model world)))

(defn advance-world
  "Given a world and a series of operations, applies those operations to the
  given world. The returned world will have a new model reflecting its state
  with the given operations applied, and its fixed history will have the given
  history appended. Those ops will also be absent from its pending operations."
  [world ops]
; (prn "advancing" world "with" ops)
  (-> world
      transient
      ; Unclear whether this is faster than transients via (into).
      (assoc! :fixed   (reduce conj (:fixed world) ops))
      (assoc! :model   (reduce step (:model world) ops))
      (assoc! :pending (persistent!
                         (reduce disj! (transient (:pending world)) ops)))
      persistent!))

(defn possible-worlds
  "Given a world, generates all possible future worlds consistent with the
  given world's pending operations. For instance, in the world

  {:fixed [:a :b]
   :pending [:c :d]}

  We *know* :a and :b have already happened, but :c and :d *could* happen, in
  either order, so long as they occur after :a and :b. Here are the possible
  worlds:

  None of them may have happened:

  {:fixed [:a :b]
   :pending [:c :d]}

  One of the two could have happened:

  {:fixed [:a :b :c]
   :pending [:d]}
  {:fixed [:a :b :d]
   :pending [:c]}

  Both could have happened:

  {:fixed [:a :b :c :d]
   :pending []}
  {:fixed [:a :b :d :c]
   :pending []}

  So: we are looking for the permutations of all subsets of all pending
  operations."
  [world]
  (let [worlds (->> world
                    :pending
                    combo/subsets                 ; oh no
                    (r/mapcat combo/permutations) ; dear lord no
                    ; For each permutation, advance the world with those
                    ; operations in order
                    (r/map (fn advance [ops] (advance-world world ops)))
                    ; Filter out null worlds
                    (r/remove nil?))

        ; Filter out inconsistent worlds
        consistent (->> worlds
                        (r/remove inconsistent-world?)
                        r/foldcat)]
    (cond
      ; No worlds at all
      (util/rempty? worlds) worlds

      ; All worlds were inconsistent
      (util/rempty? consistent) (throw (RuntimeException.
                                         (:msg (:model (first worlds)))))

      ; Return consistent worlds
      true consistent)))

(defn fold-invocation-into-world
  "Given a world and a new invoke operation, adds the operation to the pending
  set for the world, and yields a collection of all possible worlds from that.
  Increments world index."
  [world invocation]
  (possible-worlds
    (assoc world
           :index   (inc  (:index world))
           :pending (conj (:pending world) invocation))))

(defn fold-invocation-into-worlds
  "Given a sequence of worlds and a new invoke operation, adds this operation
  to the pending set for each, and projects out all possible worlds from
  those."
  [worlds invocation]
  (r/mapcat #(fold-invocation-into-worlds % invocation) worlds))

(defn fold-completion-into-world
  "Given a world and a completion operation, returns world if the operation
  took place in that world, else nil. Advances the world index by one."
  [world completion]
  (let [p (:process completion)]
    (when-not (some #(= p (:process %)) (:pending world))
      (assoc world :index (inc (:index world))))))

(defn fold-completion-into-worlds
  "Given a sequence of worlds and a completion operation, returns only those
  worlds where that operation took place; e.g. is not still pending.

  TODO: replace the corresponding element in the history with the completion."
  [worlds completion]
  (->> worlds
       (r/map #(fold-completion-into-world % completion))
       (r/remove nil)))

(defn fold-failure-into-world
  "Given a world and a failed operation, returns world if the operation did
  *not* take place, and removes the operation from the pending ops in that
  world. Advances world index by one.

  Note that a failed operation is an operation which is *known* to have failed;
  e.g. the system *guarantees* that it did not take place. This is different
  from an *indeterminate* failure."
  [world failure]
  (let [process (:process failure)
        pending (:pending world)]
    ; Find the corresponding invocation
    (when-let [inv (some #(when (= process (:process %)) %) pending)]
      ; In this world, we have not yet applied the operation.
      (assoc world :index   (inc (:index world))
                   :pending (disj pending inv)))))

(defn fold-failure-into-worlds
  "Given a sequence of worlds and a failed operation, returns only those worlds
  where that operation did not take place, and removes the operation from
  the pending ops in those worlds."
  [worlds failure]
  (->> worlds
       (r/map #(fold-failure-into-world % failure))
       (r/remove nil?)))

(defn fold-info-into-world
  "Given a world and an info operation, returns a subsequent world. Info
  operations don't appear in the fixed history; only the index advances."
  [world info]
  (assoc world :index (inc (:index world))))

(defn fold-op-into-world
  "Given a world and any type of operation, folds that operation into the world
  and returns a sequence of possible worlds. Increments the world index."
  [world op]
  (condp = (:type op)
    :invoke (fold-invocation-into-world world op)
    :ok     (util/maybe-list (fold-completion-into-world world op))
    :fail   (util/maybe-list (fold-failure-into-world    world op))
    :info   (list            (fold-info-into-world       world op))))

(defn fold-op-into-worlds
  "Given a set of worlds and any type of operation, folds that operation into
  the set and returns a new set of possible worlds."
  [worlds op]
  (->> worlds
       (r/mapcat #(fold-op-into-world % op))
       util/foldset))

(defn linearizations
  "Given a model and a history, returns all possible worlds where that history
  is linearizable. Brute-force, expensive, but deterministic, simple, and
  useful for short histories."
  [model history]
  (assert (vector? history))
  (reduce fold-op-into-worlds
          (list (world model))
          history))

(defn next-op
  "The next operation from the history to be applied to a world, based on the
  world's index, or nil when out of bounds."
  [history world]
  (try
    (nth history (:index world))
    (catch NullPointerException e
      (info world)
      (throw e))
    (catch IndexOutOfBoundsException e
      nil)))

(defn prune-world
  "Given a history and a world, advances the world through as many operations
  in the history as possible, without splitting into multiple worlds. Returns a
  new world (possibly the same as the input), or nil if the world was found to
  be inconsistent."
  [history stats world]
  (when world
    (metrics/update! (:visited-worlds stats) 1)
    (if-let [op (next-op history world)]
      (condp = (:type op)
        :ok   (recur history stats (fold-completion-into-world world op))
        :fail (recur history stats (fold-failure-into-world    world op))
        :info (recur history stats (fold-info-into-world       world op))
        world)

      ; No more ops
      world)))

(defn explode-then-prune-world
  "Given a history and a world, generates a reducible sequence of possible
  subseqeuent worlds, obtained in two phases:

  1. If the next operation in the history for this world is an invocation, we
     find all possible subsequent worlds as a result of that invocation.

  2. For all immediately subsequent ok, fail, and info operations, use those
     operations to prune and further advance the worlds from phase 1."
  [history stats world]
  (if-let [op (next-op history world)]
    ; Branch out for all invocations
    (->> (if (op/invoke? op)
           (fold-invocation-into-world world op)
           (list world))
         ; Prune completions
         (r/map (fn shears [world] (prune-world history stats world)))
         (r/remove nil?))
    ; No more ops
    (list world)))

(defn degenerate-world-key
  "An object which uniquely identifies whether or not a world is linearizable.
  If two worlds have the same degenerate-world-key (in the context of a
  history), their linearizability is equivalent."
  [world]
  ; What kind of social studies IS this?
  (dissoc world :history))

(defn seen-world!?
  "Given a mutable hashmap of seen worlds, ensures that an entry exists for the
  given world, and returns truthy iff that world had already been seen."
  [^NonBlockingHashMapLong seen world]
  (let [k  (degenerate-world-key world)
        ; Constrain the number of possible elements in the cache
        h (bit-and 0xffffff (hash k))
        seen-key (.get seen h)]
    (if (= k seen-key)
      ; We've already visited this node.
      true
      ; Null or collision. Replace the existing value.
      (do
        ; We want to avoid hitting shared state for cheap operations, so we
        ; only write to the cache if this world is sufficiently expensive to
        ; visit.
        (when (< 0 (count (:pending world)))
          (.put seen h k))
        false))))

(defn short-circuit!
  "If we've reached a world with an index as deep as the history, we can
  abort all threads immediately."
  [history ^AtomicBoolean running? world]
  (when (= (count history) (:index world))
;    (info "Short-circuiting" world)
    (.set running? false)))

(defn update-deepest-world!
  "If this is the deepest world we've seen, add it to the deepest list."
  [deepest world]
  (when (<= (:index (first @deepest)) (:index world))
    (swap! deepest (fn update [deepest]
                     (let [index  (:index (first deepest))
                           index' (:index world)]
                       (cond (< index index') [world]
                             (= index index') (conj deepest world)
                             :else            deepest))))))

(defn explore-world!
  "Explores a world's direct successors, reinjecting each into `leaders`.
  Returns the number of worlds reinserted into leaders. Uses the `seen` cache
  to avoid exploring worlds already visited. Updates `deepest` with new worlds
  at the highest index in the history. Guarantees that by return time, all
  worlds reinjected into leaders will be known to be extant."
  [history ^AtomicBoolean running? leaders seen deepest stats world]
  (->> world
       (explode-then-prune-world history stats)
       (reduce
         (fn reinjector [reinserted world]
           ; Jacques-Yves Cousteau could be thrilled
           (update-deepest-world! deepest world)

           ; Done?
           (short-circuit! history running? world)

           (if (seen-world!? seen world)
             ; Definitely been here before
             (do (metrics/update! (:skipped-worlds stats) 1)
                 ; (info "Skipping\n" (with-out-str (pprint world)))
                 reinserted)

             ; O brave new world, that hath such operations in it!
             (do (.incrementAndGet ^AtomicLong (:extant-worlds stats))
                 ; (info "reinjecting\n" (with-out-str (pprint world)))
                 (prioqueue/put! leaders world)
                 (inc reinserted))))
         0)))

(defn explorer
  "Pulls worlds off of the leader atom, explores them, and pushes resulting
  worlds back onto the leader atom."
  [history ^AtomicBoolean running? leaders seen deepest stats i]
  (future
    (util/with-thread-name (str "explorer-" i)
      (try
        (while (and (.get running?)
                    (pos? (.get ^AtomicLong (:extant-worlds stats))))
          (when-let [world (prioqueue/poll! leaders 10)]
            ; Explore world, possibly creating new ones
            (explore-world! history running? leaders seen deepest stats world)

            ; We're done with this world now.
            (.decrementAndGet ^AtomicLong (:extant-worlds stats))))

        ; We've exhausted all possible worlds
;        (info "worker" i "exiting")
        (.set running? false)

      (catch Throwable t
        (warn t "explorer" i "crashed!")
        (throw t))))))

(def awfulness-comparator
  "Which one of these worlds should we explore first?"
  (reify java.util.Comparator
    (compare [this a b]
      (cond (< (count (:pending a)) (count (:pending b))) -1
            (< (:index a)           (:index b))            1
            :else                                          0))))

(defn linearizable-prefix-and-worlds
  "Returns a vector consisting of the longest linearizable prefix and the
  worlds just prior to exhaustion.

  If you think about a lightning strike, where the history stretches from the
  initial state in the thundercloud to the final state somewhere in the ground,
  we're trying to find a path--any path--for a lightning bolt to jump from
  cloud to ground.

  Given a world at the tip of the lightning bolt, we can reach out to several
  nearby worlds just slightly ahead of ours, using fold-op-into-world. If there
  are no worlds left, we've struck a dead end and that particular fork of the
  lightning bolt terminates. If there *are* worlds left, we want to explore
  them--but it's not clear in what order.

  Moreover, some paths are more expensive to traverse than others. Worlds with
  a high number of pending operations, for instance, are particularly expensive
  because each step explores n! operations. If we can find a *quicker* path to
  ground, we should take it.

  We do this by keeping a set of all incomplete worlds, and following the
  worlds that seem to be doing the best. We leave the *hard* worlds for later.
  Each thread pulls a world off of the incomplete set, explodes it into several
  new worlds, and pushes those worlds back into the set. We call this set
  *leaders*.

  If we reach a world which has no future operations--whose index is equal to
  the length of the history--we've found a linearization and can terminate.

  If we ever run *out* of leaders, then we know no linearization is possible.
  Disproving linearizability can be much more expensive than proving it; we
  have to keep trying and trying until every possible option has been
  exhausted."
  [model history]
  (assert (vector? history))
  (if (empty? history)
    [history (world model)]
    (let [world    (world model)
          threads  (+ 2 (.. Runtime getRuntime availableProcessors))
          leaders  (prioqueue/prioqueue awfulness-comparator)
          seen     (NonBlockingHashMapLong.)
          running? (AtomicBoolean. true)
          stats    {:extant-worlds  (AtomicLong. 1)
                    :skipped-worlds (metrics/rate)
                    :visited-worlds (metrics/rate)}
          deepest (atom [world])
          workers  (->> (range threads)
                        (map (partial explorer history running? leaders
                                      seen deepest stats))
                        doall)
          reporter (future
                     (util/with-thread-name "reporter"
                       (while (.get running?)
                         (Thread/sleep 5000)
                         (let [visited    (metrics/snapshot!
                                            (:visited-worlds stats))
                               skipped    (metrics/snapshot!
                                            (:skipped-worlds stats))
                               total      (+ visited skipped)
                               hitrate    (if (zero? total) 1 (/ skipped total))
                               depth      (:index (first @deepest))
                               depth-frac (/ depth (count history))]
                           (info (str "[" depth " / " (count history) "]")
                                 (.get (:extant-worlds stats)) "extant worlds,"
                                 (long visited) "visited/s,"
                                 (long skipped) "skipped/s,"
                                 "hitrate" (format "%.3f" hitrate)
                                 "cache size" (.size seen))))))]

      ; Start with a single world containing the initial state
      (prioqueue/put! leaders world)

      ; Wait for workers
      (->> workers (map deref) dorun)
      (future-cancel reporter)

;      (info "Final queue was"
;            (take-while identity
;                        (repeatedly #(prioqueue/poll! leaders 0))))

;      (info "Final stats:" (with-out-str (pprint stats)))

      ; Return prefix and deepest world
      (let [deepest @deepest]
        [(take (:index (first deepest)) history) deepest]))))

(defn linearizable-prefix
  "Computes the longest prefix of a history which is linearizable."
  [model history]
  (first (linearizable-prefix-and-worlds model history)))

(defn analysis
  "Returns a map of information about the linearizability of a history.
  Completes the history and searches for a linearization."
  [model history]
  (let [history+            (history/complete history)
        [lin-prefix worlds] (linearizable-prefix-and-worlds model history+)
        valid?              (= (count history+) (count lin-prefix))
        evil-op             (when-not valid?
                              (nth history+ (count lin-prefix)))
        ; Remove worlds with equivalent states
        worlds              (->> worlds
                                 (r/map (juxt identity degenerate-world-key))
                                 (into {})
                                 vals)]
    (if valid?
      {:valid?              true
       :linearizable-prefix lin-prefix
       :worlds              worlds}
      {:valid?                   false
       :linearizable-prefix      lin-prefix
       :last-consistent-worlds   worlds
       :inconsistent-op          evil-op
       :inconsistent-transitions (map (fn [w]
                                      [(:model w)
                                       (-> w :model (step evil-op) :msg)])
                                      worlds)})))
