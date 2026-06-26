; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.refs)

;; The three refs that constitute the ctrl-tree fabric.
;; All structural mutations go through dosync over these refs.
;; They are always consistent with each other: no mutation touches fewer
;; than the refs it logically requires.
;;
;; mount-table — path-prefix → IMount implementation
;;   Longest-prefix-match: a write to any path resolves to its mount's protocol.
;;   Mounts: LocalStmMount, OscMount, KairosIpcMount, DeviceMapMount, NullMount.
;;
;; routing — cable-node-path → output IPort
;;   Holds the current output port for each cable node.
;;   This is what recable changes — never the cable's receiver binding.
;;   The cable's step-function receiver reads @(get @routing my-path) at
;;   invocation time to find its current output destination.
;;
;; tree-state — path → value
;;   The materialised flat map: current value at every node path.
;;   Derived state: rebuilt by replaying ops from the txlog.
;;   Written by ctrl-write! after mount resolution; read by the REPL,
;;   conductor arcs, and checkpoint generation.

(def mount-table (ref {}))
(def routing     (ref {}))
(def tree-state  (ref {}))
