; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.patch-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [protomatter.protocols :as p]
            [protomatter.port :as port]
            [ctrl-tree.refs :as refs]
            [ctrl-tree.patch :as patch]
            [ctrl-tree.core :as core]))

;; Reset all refs between tests so state doesn't bleed across.
(use-fixtures :each
  (fn [f]
    (dosync
      (ref-set refs/mount-table {})
      (ref-set refs/routing     {})
      (ref-set refs/tree-state  {})
      (ref-set refs/current-patch nil))
    (f)))

(defn- log-receiver [log-atom tag]
  (reify p/IReceiver
    (activate! [_ v] (swap! log-atom conj [tag v]))))

;; --- init-patch! ---

(deftest cable-fires-in-initial-patch
  (let [log  (atom [])
        pt   (port/make-port [:test :in])
        rcvr (log-receiver log :a)]
    (core/init-patch! {:cables [(patch/concurrent-cable pt rcvr)]})
    (p/post! pt :hello)
    (is (= [[:a :hello]] @log))))

(deftest current-patch-registered-after-init
  (core/init-patch! {:cables []})
  (is (some? @refs/current-patch))
  (is (some? (:teardown-port @refs/current-patch))))

;; --- apply-surface-patch! ---

(deftest patch-transition-stops-old-cable-starts-new
  (testing "old cable stops firing after transition; new cable starts"
    (let [log   (atom [])
          pt-a  (port/make-port [:test :in-a])
          pt-b  (port/make-port [:test :in-b])
          rcvr-a (log-receiver log :a)
          rcvr-b (log-receiver log :b)]

      (core/init-patch! {:cables [(patch/concurrent-cable pt-a rcvr-a)]})

      (p/post! pt-a :before-transition)

      (core/apply-surface-patch! {:cables [(patch/concurrent-cable pt-b rcvr-b)]})

      ;; Old cable is detached — port-a posts are ignored
      (p/post! pt-a :after-transition)
      ;; New cable is active
      (p/post! pt-b :new-work)

      (is (= [[:a :before-transition] [:b :new-work]] @log)
          "old cable stops; new cable starts; no interleaving"))))

(deftest patch-transition-is-strongly-ordered
  (testing "new patch work cannot arrive before old patch is torn down"
    (let [log   (atom [])
          pt    (port/make-port [:test :shared])
          rcvr-a (log-receiver log :old)
          rcvr-b (log-receiver log :new)]

      (core/init-patch! {:cables [(patch/concurrent-cable pt rcvr-a)]})
      (p/post! pt :old-1)
      (p/post! pt :old-2)

      ;; Transition: same port, new receiver
      (core/apply-surface-patch! {:cables [(patch/concurrent-cable pt rcvr-b)]})

      (p/post! pt :new-1)

      (is (= [[:old :old-1] [:old :old-2] [:new :new-1]] @log)
          "strict ordering: all old work precedes all new work"))))

(deftest patch-transition-applies-structural-changes
  (testing "mounts and routing are updated during transition"
    (let [pt-a  (port/make-port [:test :in-a])
          pt-b  (port/make-port [:test :in-b])
          mount (reify p/IMount
                  (mount-write!   [_ _ _])
                  (mount-recable! [_ _]))]

      (core/init-patch! {:cables []})

      (core/apply-surface-patch!
       {:cables   [(patch/concurrent-cable pt-b (reify p/IReceiver (activate! [_ _])))]
        :mounts   {[:synth] mount}
        :routing  {[:cables :lfo] pt-a}
        :uncables #{[:cables :old]}})

      (is (= {[:synth] mount} @refs/mount-table)
          "mount added during transition")
      (is (= {[:cables :lfo] pt-a} @refs/routing)
          "routing updated during transition"))))

(deftest patch-transition-teardown-receiver-constructs-next-interleave
  (testing "each teardown fires exactly once and new interleave is functional"
    (let [log    (atom [])
          pt-a   (port/make-port [:test :in-a])
          pt-b   (port/make-port [:test :in-b])
          pt-c   (port/make-port [:test :in-c])
          rcvr-a (log-receiver log :a)
          rcvr-b (log-receiver log :b)
          rcvr-c (log-receiver log :c)]

      (core/init-patch! {:cables [(patch/concurrent-cable pt-a rcvr-a)]})
      (p/post! pt-a :a1)

      (core/apply-surface-patch! {:cables [(patch/concurrent-cable pt-b rcvr-b)]})
      (p/post! pt-b :b1)

      (core/apply-surface-patch! {:cables [(patch/concurrent-cable pt-c rcvr-c)]})
      (p/post! pt-c :c1)

      ;; All previous ports are silent
      (p/post! pt-a :a-late)
      (p/post! pt-b :b-late)

      (is (= [[:a :a1] [:b :b1] [:c :c1]] @log)
          "each patch transition is clean; only the current patch fires"))))

;; --- stateful cable ---

(deftest stateful-cable-state-survives-patch-transition
  (testing "cable state-atom is preserved across patch changes"
    (let [log        (atom [])
          state-atom (atom {:count 0})
          pt-a       (port/make-port [:test :in-a])
          pt-b       (port/make-port [:test :in-b])
          ;; Stateful cable: increments count on each activation
          step-fn    (fn [state _v]
                       (let [n (inc (:count state))]
                         [{:count n} n]))
          rcvr       (patch/make-stateful-cable-receiver
                      [:cables :counter] step-fn state-atom)
          sink       (port/make-port [:test :sink])]

      ;; Register the output route
      (dosync (alter refs/routing assoc [:cables :counter] sink))

      (core/init-patch! {:cables [(patch/concurrent-cable pt-a rcvr)]})

      ;; Fire twice on first patch
      (p/post! pt-a :tick)
      (p/post! pt-a :tick)

      ;; Transition to new patch — same receiver, new input port
      (core/apply-surface-patch! {:cables [(patch/concurrent-cable pt-b rcvr)]})

      ;; Fire once on second patch
      (p/post! pt-b :tick)

      ;; State-atom should reflect all three ticks (count = 3)
      (is (= 3 (:count @state-atom))
          "state-atom accumulates across patch transition"))))
