; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.txlog-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [txlog.core   :as txlog]
            [protomatter.protocols :as p]
            [protomatter.port :as port]
            [ctrl-tree.refs  :as refs]
            [ctrl-tree.patch :as patch]
            [ctrl-tree.txlog :as ct-txlog]
            [ctrl-tree.core  :as core]))

(use-fixtures :each
  (fn [f]
    (dosync
      (ref-set refs/mount-table {})
      (ref-set refs/routing     {})
      (ref-set refs/tree-state  {})
      (ref-set refs/current-patch nil))
    (ct-txlog/uninstall!)
    (f)
    (ct-txlog/uninstall!)))

(defn- at-beat
  "Stub beat-fn returning a fixed beat."
  [b]
  (constantly (double b)))

;; --- install / uninstall ---

(deftest install-registers-sources-and-enables-sink
  (txlog/with-log [log ":memory:"]
    (ct-txlog/install! log (at-beat 0.0))
    (is (ct-txlog/installed?))
    (ct-txlog/uninstall!)
    (is (not (ct-txlog/installed?)))))

;; --- ctrl-write! ---

(deftest ctrl-write-emits-txlog-entry
  (txlog/with-log [log ":memory:"]
    (ct-txlog/install! log (at-beat 1.0))
    (core/ctrl-write! [:synth :cutoff] 0.7)
    (let [entries (txlog/read-all log)]
      (is (= 1 (count entries)))
      (let [e (first entries)]
        (is (= :ctrl-tree/write (:source e)))
        (is (= [:synth :cutoff] (:path e)))
        (is (nil?  (:before e)))
        (is (= 0.7 (:after e)))
        (is (= 1.0 (:beat e)))))))

(deftest ctrl-write-captures-before-value
  (txlog/with-log [log ":memory:"]
    (ct-txlog/install! log (at-beat 1.0))
    (core/ctrl-write! [:synth :cutoff] 0.3)
    (core/ctrl-write! [:synth :cutoff] 0.9)
    (let [entries (txlog/read-all log)]
      (is (= 2 (count entries)))
      (let [e (second entries)]
        (is (= 0.3 (:before e)))
        (is (= 0.9 (:after e)))))))

(deftest ctrl-write-no-emit-without-sink
  (core/ctrl-write! [:synth :cutoff] 0.5)
  (is (not (ct-txlog/installed?))))

;; --- recable! ---

(deftest recable-emits-per-cable-path
  (txlog/with-log [log ":memory:"]
    (ct-txlog/install! log (at-beat 2.0))
    (let [pt-a (port/make-port [:out :a])
          pt-b (port/make-port [:out :b])]
      ;; Establish an initial routing entry so we have a before value
      (dosync (alter refs/routing assoc [:cables :lfo] pt-a))
      (core/recable! {[:cables :lfo] pt-b})
      (let [entries (txlog/read-all log)]
        (is (= 1 (count entries)))
        (let [e (first entries)]
          (is (= :ctrl-tree/recable (:source e)))
          (is (= [:cables :lfo] (:path e)))
          (is (= [:out :a] (:before e)))
          (is (= [:out :b] (:after e))))))))

;; --- apply-surface-patch! ---

(deftest surface-patch-emits-structural-entry
  (txlog/with-log [log ":memory:"]
    (ct-txlog/install! log (at-beat 3.0))
    (let [pt (port/make-port [:cables :in])]
      (core/init-patch! {:cables []})
      (core/apply-surface-patch!
       {:cables  [(patch/concurrent-cable pt (reify p/IReceiver (activate! [_ _])))]
        :mounts  {[:synth] nil}
        :routing {[:cables :lfo] pt}})
      (let [entries (txlog/read-all log)]
        (is (= 1 (count entries)))
        (let [e (first entries)]
          (is (= :ctrl-tree/surface-patch (:source e)))
          (is (= [:ctrl-tree :surface-patch] (:path e)))
          (let [v (:after e)]
            (is (= #{[:synth]} (:mounts v)))
            (is (= {[:cables :lfo] [:cables :in]} (:routing v)))))))))

;; --- no-op without sink ---

(deftest ops-are-silent-without-sink
  (testing "all ops apply correctly and don't throw when no sink is installed"
    (let [pt (port/make-port [:test :in])]
      (core/ctrl-write! [:a :b] 42)
      (core/init-patch! {:cables []})
      (core/apply-surface-patch! {:cables []})
      (is (= 42 (get @refs/tree-state [:a :b]))))))
