; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.apply
  (:require [protomatter.protocols :as p]
            [ctrl-tree.refs :as refs]
            [ctrl-tree.patch :as patch]))

;; apply-op! — the functional core shared by live operation and replay.
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
  Returns the op for txlog emission by the caller."
  :op)

(defmethod apply-op! :ctrl/write [{:keys [path value]}]
  (let [mount (dosync
                (alter refs/tree-state assoc path value)
                (some (fn [[prefix mnt]]
                        (when (= prefix (subvec path 0 (min (count prefix) (count path))))
                          mnt))
                      (sort-by (comp - count key) @refs/mount-table)))]
    (when mount
      (p/mount-write! mount path value))))

(defmethod apply-op! :ctrl/recable [{:keys [changes]}]
  (let [by-mount (dosync
                   (doseq [[cable-path output-port] changes]
                     (alter refs/routing assoc cable-path output-port))
                   (group-by (fn [[cable-path _]]
                               (some (fn [[prefix mnt]]
                                       (when (= prefix (subvec cable-path 0 (min (count prefix) (count cable-path))))
                                         mnt))
                                     (sort-by (comp - count key) @refs/mount-table)))
                             changes))]
    (doseq [[mount changes-for-mount] by-mount]
      (when mount
        (p/mount-recable! mount (into {} changes-for-mount))))))

(defmethod apply-op! :ctrl/surface-patch [{:keys [patch]}]
  ;; Surface patch transitions go through the current patch's teardown port.
  ;; The interleave arbiter tears down the old patch atomically, applies structural
  ;; changes via teardown-receiver, and installs the new interleave.
  ;;
  ;; patch here is a live patch-spec (with IPort/IReceiver objects).
  ;; When called from the public API (core/apply-surface-patch!), the caller
  ;; has already constructed the live spec.
  ;;
  ;; Txlog replay: the logged op carries only serialisable paths; live cable
  ;; objects are not present. Replaying a surface-patch op from the txlog can
  ;; restore mount-table and routing refs but cannot reconstruct the live cable
  ;; interleave without a node registry (TODO). For now, txlog replay of
  ;; surface-patch is structural-only.
  (if-let [{:keys [teardown-port]} @refs/current-patch]
    (p/post! teardown-port patch)
    (patch/install-patch! patch)))

(defmethod apply-op! :ctrl/checkpoint [{:keys [state]}]
  ;; Materialisation from a checkpoint. Restores structural refs and
  ;; cable state atoms directly. No mount dispatch. No arbiter notification.
  ;; The system is in a consistent structural state after this returns;
  ;; cables will produce values from their restored state on next tick.
  (let [{:keys [mounts routing cable-states]} state]
    (dosync
      (ref-set refs/mount-table (or mounts {}))
      (ref-set refs/routing     (or routing {})))
    ;; Cable state atoms are addressed by path in tree-state.
    ;; A checkpoint restores them by writing directly to the atom
    ;; rather than routing through ctrl-write! (which would dispatch to mounts).
    ;; TODO: INode registry needed to look up state atoms by path.
    ;; For now, cable-states is advisory; implement once node registry exists.
    (when (seq cable-states)
      (throw (ex-info "checkpoint cable-state restore not yet implemented — node registry required"
                      {:cable-states cable-states})))))

(defmethod apply-op! :default [op]
  (throw (ex-info "unknown ctrl-tree op" {:op (:op op)})))
