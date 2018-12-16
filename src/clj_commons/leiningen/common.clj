(ns clj-commons.leiningen.common
  (:require [leiningen.core.classpath :as classpath]
            [leiningen.core.main :as main]
            [com.walmartlabs.vizdeps.common :as common]))


(defn flatten-dependencies
  [project]
  "Resolves dependencies for the project and returns a map from artifact
  symbol to artifact coord vector."
  (-> (classpath/managed-dependency-hierarchy :dependencies :managed-dependencies
                                              project)
      common/build-dependency-map))
