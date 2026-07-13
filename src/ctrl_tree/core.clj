; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.core
  (:require [ctrl-tree.ops   :as ops]
            [ctrl-tree.apply :as apply]
            [ctrl-tree.patch :as patch]
            [ctrl-tree.refs  :as refs]
            [ctrl-tree.txlog :as txlog]))

;; Public API. Each function applies the op and emits to the txlog sink
;; (no-op if no sink installed via ctrl-tree.txlog/install!).
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
  "Write value to path. Emits :ctrl/write to the txlog sink.
  Returns the op result map."
  [path value]
  (let [result (apply/apply-op! (ops/write-op path value))]
    (txlog/emit-op! result)
    result))

(defn ctrl-read
  "Read the value at path from tree-state. Returns nil when absent.

  Symmetric counterpart to ctrl-write! and the single read entry point for
  application code — prefer this over dereferencing ctrl-tree.refs/tree-state
  directly. A single deref is a consistent snapshot read, so no dosync is
  needed. Reads are pure: no txlog emission, no mount dispatch.

  Note: every ctrl-write! is persisted to the SQLite txlog (values serialised
  via pr-str), so only values that round-trip through read-string belong on the
  tree. Heavy/derived objects live in caches keyed by a serialisable descriptor,
  not on the tree itself."
  [path]
  (get @refs/tree-state path))

(defn recable!
  "Atomically reroute cable outputs. changes is {cable-path output-port}.
  Emits one :ctrl/recable entry per cable path to the txlog sink.
  Returns the op result map."
  [changes]
  (let [result (apply/apply-op! (ops/recable-op changes))]
    (txlog/emit-op! result)
    result))

(defn init-patch!
  "Establish the initial patch interleave. Must be called before any
  apply-surface-patch! transitions. patch-spec: see ctrl-tree.patch/install-patch!."
  [patch-spec]
  (patch/install-patch! patch-spec))

(defn apply-surface-patch!
  "Transition to a new patch via the current interleave's teardown port.
  patch-spec is a live descriptor — see ctrl-tree.patch/install-patch! for shape.
  Emits a :ctrl/surface-patch entry (serialisable structural diff) to the txlog sink.
  Returns the op result map."
  [patch-spec]
  (let [result (apply/apply-op! {:op :ctrl/surface-patch :patch patch-spec})]
    (txlog/emit-op! result)
    result))

(defn restore-checkpoint!
  "Restore structural state from a checkpoint op.
  Bypasses mount dispatch and arbiter notification.
  Emits a :ctrl/checkpoint entry to the txlog sink.
  Returns the op result map."
  [state]
  (let [result (apply/apply-op! (ops/checkpoint-op state))]
    (txlog/emit-op! result)
    result))
