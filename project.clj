(defproject clj-commons/lein-vizdeps "1.0"
  :description "Visualize Leiningen project dependencies using Graphviz."
  :url "https://github.com/walmartlabs/vizdeps"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.cli "1.0.214"]
                 ;; Note: 0.0.7 breaks api we depend on:
                 [dorothy "0.0.6"]
                 [medley "1.4.0"]
                 [com.stuartsierra/dependency "1.0.0"]]
  :eval-in-leiningen true)
