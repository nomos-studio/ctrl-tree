; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [ctrl-tree.ops :as ops]))

(deftest write-op-shape
  (let [op (ops/write-op [:synth :voice :filter :cutoff] 0.6)]
    (is (= :ctrl/write (:op op)))
    (is (= [:synth :voice :filter :cutoff] (:path op)))
    (is (= 0.6 (:value op)))))

(deftest recable-op-shape
  (let [changes {[:cables :phasor :out] ::some-port}
        op (ops/recable-op changes)]
    (is (= :ctrl/recable (:op op)))
    (is (= changes (:changes op)))))

(deftest surface-patch-op-shape
  (let [patch {:mounts   {[:instruments :synth] ::mount}
               :unmounts #{[:instruments :old-synth]}
               :cables   {[:cables :env :out] ::env-port}
               :uncables #{[:cables :old-cable]}}
        op (ops/surface-patch-op patch)]
    (is (= :ctrl/surface-patch (:op op)))
    (is (= patch (:patch op)))))

(deftest checkpoint-op-shape
  (let [state {:mounts       {[:synth] ::mount}
               :routing      {[:cables :phasor] ::port}
               :cable-states {[:cables :phasor] {:phase 0.73}}}
        op (ops/checkpoint-op state)]
    (is (= :ctrl/checkpoint (:op op)))
    (is (= state (:state op)))))

(deftest ops-are-plain-maps
  (testing "all ops are serialisable Clojure data"
    (let [ops [(ops/write-op [:a :b] 1.0)
               (ops/recable-op {})
               (ops/surface-patch-op {})
               (ops/checkpoint-op {:mounts {} :routing {} :cable-states {}})]]
      (is (every? map? ops))
      (is (every? #(contains? % :op) ops)))))
