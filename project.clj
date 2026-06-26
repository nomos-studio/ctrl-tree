; SPDX-License-Identifier: EPL-2.0
(defproject nomos-studio/ctrl-tree "0.1.0-SNAPSHOT"
  :description "ctrl-tree — transactional control surface fabric for nomos-studio"
  :url "https://github.com/nomos-studio/ctrl-tree"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [nomos-studio/protomatter "0.1.0-SNAPSHOT"]]
  :source-paths ["src"]
  :test-paths   ["test"]
  :target-path  "target/%s"
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "1.5.0"]]}})
