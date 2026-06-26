; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.mount
  (:require [protomatter.protocols :as p]))

;; Mount implementations project ctrl-tree writes to external protocols.
;; mount-write! is always called after dosync commits — never inside a
;; transaction. Remote side effects (OSC, IPC) dispatch post-commit.

;; NullMount — captures calls without dispatching.
;; Used when a mount subtree's target is not connected or available,
;; or as the mount table configuration for disconnected-mode operation
;; (e.g. when building state from a checkpoint without live hardware).

(deftype NullMount [log-atom]
  p/IMount
  (mount-write! [_ path value]
    (when log-atom
      (swap! log-atom conj {:path path :value value})))
  (mount-recable! [_ changes]
    (when log-atom
      (swap! log-atom conj {:recable changes}))))

(defn null-mount
  "A mount that discards all writes. Pass an atom to capture them instead."
  ([]      (NullMount. nil))
  ([log-a] (NullMount. log-a)))

;; LocalStmMount — writes to the ctrl-tree's own tree-state ref.
;; No remote dispatch; all state is local STM.
;; The standard mount for cable nodes, value nodes, and local parameter state.

(deftype LocalStmMount [tree-state-ref]
  p/IMount
  (mount-write! [_ path value]
    ;; tree-state is already updated by ctrl-write! before mount dispatch.
    ;; LocalStmMount is a no-op here: the ref write happened in the dosync.
    nil)
  (mount-recable! [_ _changes]
    nil))

(defn local-stm-mount [tree-state-ref]
  (LocalStmMount. tree-state-ref))

;; TODO: OscMount      — translate path writes to OSC messages
;; TODO: KairosIpcMount — translate path writes to CLAP parameter events over IPC
;; TODO: DeviceMapMount — translate path writes via device EDN spec to MIDI/CV
