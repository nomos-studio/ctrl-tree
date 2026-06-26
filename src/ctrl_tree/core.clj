; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.core
  (:require [ctrl-tree.ops :as ops]
            [ctrl-tree.apply :as apply]))

;; Public API. Each function constructs the appropriate op and calls apply-op!.
;; The op is returned so the caller (txlog integration) can emit it.
;;
;; ctrl-write! is inherently a dosync: it reads mount-table to resolve the
;; target and writes tree-state in the same STM snapshot. A concurrent remount
;; cannot change the target between resolution and delivery.
;;
;; recable changes routing ref entries only. Receivers are never detached
;; or swapped. A cable's step-function receiver reads @(get @routing path)
;; at invocation time to find its current output port.
;;
;; apply-surface-patch! is one dosync touching mount-table and routing.
;; After commit every subsequent read sees new mounts and new cable routing
;; simultaneously. No window in which a value arrives at a port that no longer
;; has the right mount behind it.

(defn ctrl-write!
  "Write value to path. Returns the op for txlog emission."
  [path value]
  (let [op (ops/write-op path value)]
    (apply/apply-op! op)
    op))

(defn recable!
  "Atomically reroute cable outputs. changes is {cable-path output-port}.
  Returns the op for txlog emission."
  [changes]
  (let [op (ops/recable-op changes)]
    (apply/apply-op! op)
    op))

(defn apply-surface-patch!
  "Atomic surface transition: mount changes + routing changes.
  Returns the op for txlog emission."
  [patch]
  (let [op (ops/surface-patch-op patch)]
    (apply/apply-op! op)
    op))

(defn restore-checkpoint!
  "Restore structural state from a checkpoint op.
  Bypasses mount dispatch and arbiter notification.
  Returns the checkpoint op."
  [state]
  (let [op (ops/checkpoint-op state)]
    (apply/apply-op! op)
    op))
