(defproject clj-commons/vizdeps "0.1.7-SNAPSHOT"
  :description "Visualize Leiningen project dependencies using Graphviz."
  :url "https://github.com/walmartlabs/vizdeps"
  :license {:name "Apache Sofware License 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.cli "0.4.1"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [dorothy "0.0.7"]
                 [medley "1.0.0"]]

  :profiles {:leiningen {:dependencies [[leiningen-core "2.8.2"]
                                        [org.apache.maven.wagon/wagon-http "3.0.0"]]}
             :tools.deps {:dependencies [[org.clojure/tools.deps.alpha "0.5.460"]]}}
  :aliases {"vizdeps" ["with-profile" "leiningen" "run" "-m" "clj-commons.leiningen.vizdeps/leiningen"]})
