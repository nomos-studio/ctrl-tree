; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.patch
  (:require [protomatter.protocols :as p]
            [protomatter.port :as port]
            [protomatter.arbiters :as arb]
            [ctrl-tree.refs :as refs]))

;; A patch-spec is a live (non-serialisable) descriptor:
;;   :cables   — [{:in-port IPort :receiver IReceiver :branch :concurrent|:exclusive}]
;;   :mounts   — {prefix IMount}    additions/replacements to mount-table
;;   :unmounts — #{prefix}          removals from mount-table
;;   :routing  — {cable-path IPort} additions/replacements to routing
;;   :uncables — #{cable-path}      removals from routing
;;
;; The txlog op (serialisable) carries only :mounts, :unmounts, and path-keyed
;; :routing/:uncables — live IPort objects are stripped before logging.
;; Replaying from txlog restores structural refs but cannot reconstruct the
;; live cable interleave without a node registry (TODO).

;; install-patch! and teardown-receiver are mutually recursive:
;; teardown-receiver activates with the next patch-spec and calls install-patch!;
;; install-patch! builds the interleave with teardown-receiver as the teardown handler.
;; Clojure resolves vars at call time, so the forward declare is sufficient.
(declare teardown-receiver)

(defn install-patch!
  "Build and register an interleave arbiter for patch-spec.
  Called for initial patch setup and by teardown-receiver on each transition.
  After this returns, the old interleave is gone and the new one is active."
  [patch-spec]
  (let [cables           (:cables patch-spec [])
        concurrent-pairs (into []
                               (comp (filter #(= :concurrent (:branch %)))
                                     (map (juxt :in-port :receiver)))
                               cables)
        exclusive-pairs  (into []
                               (comp (filter #(= :exclusive (:branch %)))
                                     (map (juxt :in-port :receiver)))
                               cables)
        td-port          (port/make-port [:ctrl-tree :teardown])]
    ;; interleave-arb attaches to all ports in dosync internally
    (arb/interleave-arb concurrent-pairs exclusive-pairs [td-port teardown-receiver])
    (dosync
      (ref-set refs/current-patch {:teardown-port td-port}))))

;; Generic teardown receiver. Receives the incoming patch-spec as its activation value.
;; By the time activate! runs, the old interleave has already been torn down:
;; InterleaveArbiter.notify! committed the :done transition and detached all ports
;; before calling activate!. The incoming patch's ports are therefore free to attach.
(def teardown-receiver
  (reify p/IReceiver
    (activate! [_ new-patch-spec]
      ;; Apply structural changes from the incoming patch-spec.
      (dosync
        (doseq [[prefix descriptor] (:mounts new-patch-spec)]
          (alter refs/mount-table assoc prefix descriptor))
        (doseq [prefix (:unmounts new-patch-spec)]
          (alter refs/mount-table dissoc prefix))
        (doseq [[cable-path out-port] (:routing new-patch-spec)]
          (alter refs/routing assoc cable-path out-port))
        (doseq [cable-path (:uncables new-patch-spec)]
          (alter refs/routing dissoc cable-path)))
      ;; Install the new interleave. Ports freed by the teardown are now available.
      (install-patch! new-patch-spec))))

;; --- Cable receiver constructors ---
;;
;; Cable receivers read their output destination from the routing ref at activation
;; time — not at construction time. recable! changes routing; the receiver doesn't
;; need to be rebuilt. A cable's computational identity (its step-fn and state-atom)
;; is separate from its routing, and survives patch transitions.

(defn make-cable-receiver
  "IReceiver for a pure (stateless) cable.
  step-fn: value → output-value"
  [cable-path step-fn]
  (reify p/IReceiver
    (activate! [_ value]
      (when-let [out-port (get @refs/routing cable-path)]
        (p/post! out-port (step-fn value))))))

(defn make-stateful-cable-receiver
  "IReceiver for a stateful cable.
  step-fn: [state value] → [state' output-value]
  state-atom is owned by the cable node and survives patch transitions —
  the cable's computational trajectory is preserved across recabling."
  [cable-path step-fn state-atom]
  (reify p/IReceiver
    (activate! [_ value]
      (let [[state' out] (step-fn @state-atom value)]
        (reset! state-atom state')
        (when-let [out-port (get @refs/routing cable-path)]
          (p/post! out-port out))))))

;; --- Cable entry helpers ---

(defn concurrent-cable
  "Cable entry for the concurrent branch of an interleave arbiter."
  [in-port receiver]
  {:in-port in-port :receiver receiver :branch :concurrent})

(defn exclusive-cable
  "Cable entry for the exclusive branch of an interleave arbiter.
  Use for cables that must serialise with respect to each other."
  [in-port receiver]
  {:in-port in-port :receiver receiver :branch :exclusive})
