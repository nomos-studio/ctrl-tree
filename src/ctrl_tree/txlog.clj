; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.txlog
  "Txlog integration for ctrl-tree. Optional — no-op until install! is called.
  The caller supplies a beat-fn (0-arity, returns current beat as double)
  from its own Link/transport context. ctrl-tree does not own beat."
  (:require [txlog.core :as txlog])
  (:import [java.util UUID]))

;; Source vocabulary for ctrl-tree ops.
;; :ctrl-tree/* follows the txlog open-extension convention.
(def ^:const source-write          :ctrl-tree/write)
(def ^:const source-recable        :ctrl-tree/recable)
(def ^:const source-surface-patch  :ctrl-tree/surface-patch)
(def ^:const source-checkpoint     :ctrl-tree/checkpoint)

(def ^:private sink (atom nil))

(defn install!
  "Wire ctrl-tree to a txlog. Registers ctrl-tree source kinds and installs
  the sink so subsequent ops are emitted automatically.
  log: txlog.core/Log (from txlog.core/open)
  beat-fn: 0-arity fn returning current beat as double — supplied by the
           Link/transport context (nous, kairos, or test stub)."
  [log beat-fn]
  (txlog/register-source log source-write         "ctrl-tree parameter write"    nil)
  (txlog/register-source log source-recable       "ctrl-tree cable reroute"      nil)
  (txlog/register-source log source-surface-patch "ctrl-tree surface-patch"      nil)
  (txlog/register-source log source-checkpoint    "ctrl-tree checkpoint"         nil)
  (reset! sink {:log log :beat-fn beat-fn}))

(defn uninstall!
  "Remove the txlog sink. Subsequent ops are not logged."
  []
  (reset! sink nil))

(defn installed? [] (some? @sink))

;; ---------------------------------------------------------------------------
;; Internal emission
;; ---------------------------------------------------------------------------

(defn- new-entry [beat source path before after]
  {:id      (UUID/randomUUID)
   :beat    (double beat)
   :wall-ns (System/nanoTime)
   :source  source
   :path    path
   :before  before
   :after   after
   :parent  nil})

(defn emit-op!
  "Translate an apply-op! result map to one or more txlog entries and emit.
  No-op if no sink installed. Called by core.clj after each successful op.

  op-result shapes by :op:
    :ctrl/write          — {:path :before :after}
    :ctrl/recable        — {:changes [{:path :before :after} ...]}
    :ctrl/surface-patch  — {:mounts :unmounts :routing :uncables} (serialisable paths)
    :ctrl/checkpoint     — {:state}"
  [{:keys [op] :as op-result}]
  (when-let [{:keys [log beat-fn]} @sink]
    (let [beat (beat-fn)]
      (case op
        :ctrl/write
        (txlog/emit log (new-entry beat source-write
                                   (:path op-result)
                                   (:before op-result)
                                   (:after op-result)))

        :ctrl/recable
        (doseq [{:keys [path before after]} (:changes op-result)]
          (txlog/emit log (new-entry beat source-recable path before after)))

        :ctrl/surface-patch
        (txlog/emit log (new-entry beat source-surface-patch
                                   [:ctrl-tree :surface-patch]
                                   nil
                                   (dissoc op-result :op)))

        :ctrl/checkpoint
        (txlog/emit log (new-entry beat source-checkpoint
                                   [:ctrl-tree :checkpoint]
                                   nil
                                   (:state op-result)))

        nil))))
