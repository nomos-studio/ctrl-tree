; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.ops)

;; The ctrl-tree operation vocabulary.
;; These are the "stuff" of txlog transactions: serialisable Clojure maps
;; that record what was done, not what the tree looks like afterward.
;; apply-op! in ctrl-tree.apply dispatches on :op.
;;
;; Replay is not history — it is new state created from history.
;; When ops are read from the txlog and applied forward, they produce
;; new transactions at current wall_ns. The historical record is read-only
;; material; what apply-op! produces is genuinely new.
;;
;; Materialisation is a transform over structural ops and cable state:
;; - Structural ops (recable, surface-patch) reconstruct topology
;; - Cable state maps restore computational trajectory (phasor phase, etc.)
;; - Leaf parameter values are not required — they are emergent from
;;   running the cables from restored state
;; A :ctrl/checkpoint op captures this minimal starting-point footprint.

(defn write-op
  "Write a value to a ctrl-tree path.
  value may be a scalar or a map for composite parameter nodes."
  [path value]
  {:op :ctrl/write :path path :value value})

(defn recable-op
  "Atomically reroute one or more cable outputs.
  changes is a map of cable-node-path → new output IPort.
  Receivers are never changed — only the output routing."
  [changes]
  {:op :ctrl/recable :changes changes})

(defn surface-patch-op
  "Atomic surface transition: mount changes + routing changes in one op.
  patch is a map with optional keys:
    :mounts   — {prefix descriptor} to add/replace
    :unmounts — #{prefix} to remove
    :cables   — {cable-path output-port} routing additions (same as recable)
    :uncables — #{cable-path} routing removals"
  [patch]
  {:op :ctrl/surface-patch :patch patch})

(defn checkpoint-op
  "Materialisation snapshot: minimal state to restart the system from this point.
  Captures structure (mounts, routing) and algorithmic cable state.
  Leaf parameter values are not captured — they are emergent.
  state is a map:
    :mounts       — current mount-table snapshot
    :routing      — current routing snapshot (cable-path → output port path)
    :cable-states — {cable-path state-map} for all stateful cable nodes"
  [state]
  {:op :ctrl/checkpoint :state state})
