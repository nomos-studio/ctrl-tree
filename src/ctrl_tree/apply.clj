; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.apply
  (:require [protomatter.protocols :as p]
            [ctrl-tree.refs :as refs]
            [ctrl-tree.patch :as patch]))

;; apply-op! — the functional core shared by live operation and replay.
;;
;; Each method returns an enriched result map for txlog emission.
;; Callers (core.clj) pass the result to ctrl-tree.txlog/emit-op!.
;;
;; Replay is not re-running history. When ops are drawn from the txlog
;; and applied forward, apply-op! produces new state at current time.
;; The caller is responsible for op selection and sequencing; apply-op!
;; just applies one op atomically.
;;
;; Materialisation from a checkpoint bypasses mount dispatch and arbiter
;; notification: it restores structural refs (mount-table, routing) and
;; cable state atoms directly. This is not a "dry run" — it is a different
;; kind of operation that produces real state without the side effects that
;; would come from routing through the live fabric.

(defmulti apply-op!
  "Apply a single ctrl-tree operation. Dispatches on :op.
  Returns an enriched result map for txlog emission."
  :op)

;; Resolve the longest-prefix-matching mount for path. Must be called
;; within dosync (reads mount-table ref).
(defn- resolve-mount [path]
  (some (fn [[prefix mnt]]
          (when (= prefix (subvec path 0 (min (count prefix) (count path))))
            mnt))
        (sort-by (comp - count key) @refs/mount-table)))

(defmethod apply-op! :ctrl/write [{:keys [path value]}]
  (let [[before mount]
        (dosync
          (let [b (get @refs/tree-state path)]
            (alter refs/tree-state assoc path value)
            [b (resolve-mount path)]))]
    (when mount
      (p/mount-write! mount path value))
    {:op :ctrl/write :path path :before before :after value}))

(defmethod apply-op! :ctrl/recable [{:keys [changes]}]
  (let [[before-map by-mount]
        (dosync
          (let [before (into {}
                             (map (fn [[cp _]]
                                    [cp (some-> (get @refs/routing cp) p/port-path)]))
                             changes)]
            (doseq [[cable-path output-port] changes]
              (alter refs/routing assoc cable-path output-port))
            [before
             (group-by (fn [[cable-path _]] (resolve-mount cable-path)) changes)]))]
    (doseq [[mount changes-for-mount] by-mount]
      (when mount
        (p/mount-recable! mount (into {} changes-for-mount))))
    {:op      :ctrl/recable
     :changes (mapv (fn [[cp port]]
                      {:path   cp
                       :before (get before-map cp)
                       :after  (p/port-path port)})
                    changes)}))

(defmethod apply-op! :ctrl/surface-patch [{:keys [patch]}]
  ;; Surface patch transitions go through the current patch's teardown port.
  ;; p/post! is synchronous: by the time it returns, teardown-receiver has run,
  ;; structural refs are updated, and the new interleave is installed.
  ;;
  ;; Txlog replay: the logged op carries only serialisable paths; live cable
  ;; objects are not present. Replaying a surface-patch op from the txlog can
  ;; restore mount-table and routing refs but cannot reconstruct the live cable
  ;; interleave without a node registry (TODO).
  (if-let [{:keys [teardown-port]} @refs/current-patch]
    (p/post! teardown-port patch)
    (patch/install-patch! patch))
  ;; Build serialisable result for txlog. Port objects → their paths.
  {:op       :ctrl/surface-patch
   :mounts   (set (keys (:mounts patch)))
   :unmounts (:unmounts patch)
   :routing  (into {} (map (fn [[cp port]] [cp (p/port-path port)])) (:routing patch []))
   :uncables (:uncables patch)})

(defmethod apply-op! :ctrl/checkpoint [{:keys [state]}]
  ;; Materialisation from a checkpoint. Restores structural refs and
  ;; cable state atoms directly. No mount dispatch. No arbiter notification.
  ;; The system is in a consistent structural state after this returns;
  ;; cables will produce values from their restored state on next tick.
  (let [{:keys [mounts routing cable-states]} state]
    (dosync
      (ref-set refs/mount-table (or mounts {}))
      (ref-set refs/routing     (or routing {})))
    ;; TODO: INode registry needed to look up state atoms by path.
    (when (seq cable-states)
      (throw (ex-info "checkpoint cable-state restore not yet implemented — node registry required"
                      {:cable-states cable-states}))))
  {:op :ctrl/checkpoint :state state})

(defmethod apply-op! :default [op]
  (throw (ex-info "unknown ctrl-tree op" {:op (:op op)})))
