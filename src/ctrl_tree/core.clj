; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.core
  (:require [ctrl-tree.ops :as ops]
            [ctrl-tree.apply :as apply]
            [ctrl-tree.patch :as patch]))

;; Public API. Each function constructs the appropriate op and calls apply-op!.
;; The op is returned so the caller (txlog integration) can emit it.
;;
;; ctrl-write! is inherently a dosync: it reads mount-table to resolve the
;; target and writes tree-state in the same STM snapshot. A concurrent remount
;; cannot change the target between resolution and delivery.
;;
;; recable! changes routing ref entries only. Receivers are never detached
;; or swapped. A cable's step-function receiver reads @(get @routing path)
;; at invocation time to find its current output port.
;;
;; apply-surface-patch! routes through the current patch's teardown port.
;; The interleave arbiter tears down the old patch atomically and installs
;; the new one. "Strongly after" sequencing: new cables cannot begin computing
;; until the old interleave is completely gone.
;; Use init-patch! to establish the first patch before any transitions.

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

(defn init-patch!
  "Establish the initial patch interleave. Must be called before any
  apply-surface-patch! transitions. patch-spec: see ctrl-tree.patch/install-patch!."
  [patch-spec]
  (patch/install-patch! patch-spec))

(defn apply-surface-patch!
  "Transition to a new patch via the current interleave's teardown port.
  patch-spec is a live descriptor — see ctrl-tree.patch/install-patch! for shape.
  Returns a serialisable op for txlog emission (live IPort/IReceiver objects stripped)."
  [patch-spec]
  (apply/apply-op! {:op :ctrl/surface-patch :patch patch-spec})
  ;; Return serialisable op for txlog — strip live objects
  (ops/surface-patch-op (select-keys patch-spec [:mounts :unmounts :routing :uncables])))

(defn restore-checkpoint!
  "Restore structural state from a checkpoint op.
  Bypasses mount dispatch and arbiter notification.
  Returns the checkpoint op."
  [state]
  (let [op (ops/checkpoint-op state)]
    (apply/apply-op! op)
    op))
