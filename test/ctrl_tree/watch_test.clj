; SPDX-License-Identifier: EPL-2.0
(ns ctrl-tree.watch-test
  "Tests for the ctrl-tree path-watch primitive — post-commit observers
  registered via ctrl-watch! / ctrl-watch-global!."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]))

;; No txlog sink and no mounts: ctrl-write! to an unmounted path just updates
;; tree-state and fires watchers. Reset all observer + state refs each test.
(defn- clean [f]
  (reset! refs/watchers {})
  (reset! refs/global-watchers {})
  (dosync (ref-set refs/tree-state {}))
  (try (f)
       (finally
         (reset! refs/watchers {})
         (reset! refs/global-watchers {})
         (dosync (ref-set refs/tree-state {})))))

(use-fixtures :each clean)

(deftest fires-on-write-to-watched-path
  (testing "watcher gets [path before after]; before is nil then the prior value"
    (let [calls (atom [])]
      (ct/ctrl-watch! [:a :b] ::k (fn [p before after] (swap! calls conj [p before after])))
      (ct/ctrl-write! [:a :b] 1)
      (ct/ctrl-write! [:a :b] 2)
      (is (= [[[:a :b] nil 1] [[:a :b] 1 2]] @calls)))))

(deftest fires-on-every-write-not-only-change
  (testing "writing the same value twice fires twice"
    (let [n (atom 0)]
      (ct/ctrl-watch! [:x] ::k (fn [_ _ _] (swap! n inc)))
      (ct/ctrl-write! [:x] 5)
      (ct/ctrl-write! [:x] 5)
      (is (= 2 @n) "not change-gated"))))

(deftest does-not-fire-for-other-paths
  (testing "a watcher on [:a] is silent for a write to [:b]"
    (let [calls (atom [])]
      (ct/ctrl-watch! [:a] ::k (fn [p _ _] (swap! calls conj p)))
      (ct/ctrl-write! [:b] 1)
      (is (= [] @calls)))))

(deftest ctrl-read-inside-callback-sees-after
  (testing "post-commit ordering — the value is committed before the watcher runs"
    (let [seen (atom ::unset)]
      (ct/ctrl-watch! [:p] ::k (fn [_ _ _] (reset! seen (ct/ctrl-read [:p]))))
      (ct/ctrl-write! [:p] 42)
      (is (= 42 @seen)))))

(deftest multiple-keys-all-fire-and-re-register-replaces
  (testing "distinct keys both fire; same key replaces"
    (let [a (atom 0) b (atom 0)]
      (ct/ctrl-watch! [:m] ::a (fn [_ _ _] (swap! a inc)))
      (ct/ctrl-watch! [:m] ::b (fn [_ _ _] (swap! b inc)))
      (ct/ctrl-write! [:m] 1)
      (is (= [1 1] [@a @b]) "both keys fired")
      ;; Re-register ::a with a no-op — replaces, does not add.
      (ct/ctrl-watch! [:m] ::a (fn [_ _ _] nil))
      (ct/ctrl-write! [:m] 2)
      (is (= 1 @a) "::a replaced, old fn gone")
      (is (= 2 @b) "::b still firing"))))

(deftest unwatch-stops-one-key
  (testing "ctrl-unwatch! removes just that key"
    (let [n (atom 0)]
      (ct/ctrl-watch! [:u] ::k (fn [_ _ _] (swap! n inc)))
      (ct/ctrl-write! [:u] 1)
      (ct/ctrl-unwatch! [:u] ::k)
      (ct/ctrl-write! [:u] 2)
      (is (= 1 @n))
      (is (nil? (ct/ctrl-unwatch! [:u] ::missing)) "unwatch of absent key is a no-op"))))

(deftest unwatch-all-drops-every-key-on-path
  (testing "ctrl-unwatch-all! clears the whole path"
    (let [n (atom 0)]
      (ct/ctrl-watch! [:v] ::a (fn [_ _ _] (swap! n inc)))
      (ct/ctrl-watch! [:v] ::b (fn [_ _ _] (swap! n inc)))
      (ct/ctrl-unwatch-all! [:v])
      (ct/ctrl-write! [:v] 1)
      (is (= 0 @n)))))

(deftest global-watcher-fires-for-every-path
  (testing "ctrl-watch-global! sees [path before after] for any write"
    (let [calls (atom [])]
      (ct/ctrl-watch-global! ::g (fn [p before after] (swap! calls conj [p before after])))
      (ct/ctrl-write! [:one] 10)
      (ct/ctrl-write! [:two :deep] 20)
      (is (= [[[:one] nil 10] [[:two :deep] nil 20]] @calls))
      (ct/ctrl-unwatch-global! ::g)
      (ct/ctrl-write! [:three] 30)
      (is (= 2 (count @calls)) "unwatch-global! stops further fires"))))

(deftest one-watcher-throwing-does-not-starve-others
  (testing "an exception in one callback is swallowed; the rest still fire"
    (let [ok (atom 0)]
      (ct/ctrl-watch! [:e] ::boom (fn [_ _ _] (throw (ex-info "boom" {}))))
      (ct/ctrl-watch! [:e] ::ok   (fn [_ _ _] (swap! ok inc)))
      (ct/ctrl-write! [:e] 1)   ; prints the boom to stderr, must not throw
      (is (= 1 @ok) "the healthy watcher still ran"))))
