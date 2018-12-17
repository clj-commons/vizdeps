(ns clj-commons.tools.deps.vizdeps
  (:require [clojure.tools.deps.alpha :as deps]
            [com.walmartlabs.vizdeps.common :as common]
            [dorothy.core :as d]
            [clojure.edn :as edn]
            [clojure.tools.deps.alpha.util.maven :as mvn]
            [clojure.set :as c-set]
            [dorothy.core :as dorothy-core]))


;;We need this one inside job.
(defn deps->tools-graph
  [deps-edn]
  (#'deps/expand-deps (:deps deps-edn)
                      nil nil {:mvn/repos mvn/standard-repos} false))

(def example-tools-graph
  '{dorothy {:paths {{:mvn/version "0.0.7"} #{[]}},
             :pin true,
             :select {:mvn/version "0.0.7"},
             :versions {{:mvn/version "0.0.7"} {:deps/manifest :mvn, :mvn/version "0.0.7"}}},
    medley {:paths {{:mvn/version "1.0.0"} #{[]}},
            :pin true,
            :select {:mvn/version "1.0.0"},
            :versions {{:mvn/version "1.0.0"} {:deps/manifest :mvn, :mvn/version "1.0.0"}}},
    com.stuartsierra/dependency {:paths {{:mvn/version "0.2.0"} #{[]}},
                                 :pin true,
                                 :select {:mvn/version "0.2.0"},
                                 :versions {{:mvn/version "0.2.0"} {:deps/manifest :mvn,
                                                                    :mvn/version "0.2.0"}}},
    org.clojure/clojure {:paths {{:mvn/version "1.9.0"} #{[]}},
                         :pin true,
                         :select {:mvn/version "1.9.0"},
                         :versions {{:mvn/version "1.9.0"} {:deps/manifest :mvn,
                                                            :mvn/version "1.9.0"}}},
    org.clojure/core.specs.alpha {:paths {{:mvn/version "0.1.24"} #{[org.clojure/clojure]}},
                                  :select {:mvn/version "0.1.24"},
                                  :versions {{:mvn/version "0.1.24"} {:deps/manifest :mvn,
                                                                      :mvn/version "0.1.24"}}},
    org.clojure/spec.alpha {:paths {{:mvn/version "0.1.143"} #{[org.clojure/clojure]}},
                            :select {:mvn/version "0.1.143"},
                            :versions {{:mvn/version "0.1.143"} {:deps/manifest :mvn,
                                                                 :mvn/version "0.1.143"}}},
    org.clojure/tools.cli {:paths {{:mvn/version "0.4.1"} #{[]}},
                           :pin true,
                           :select {:mvn/version "0.4.1"},
                           :versions {{:mvn/version "0.4.1"} {:deps/manifest :mvn,
                                                              :mvn/version "0.4.1"}}}})


(defn- expand-path
  [path->nodes path]
  (when-let [target-item (last path)]
    (let [target-path (vec (butlast path))]
      [target-item (get-in path->nodes [target-path target-item])])))


(defn formalize-graph
  [lookup-table graph maven-coord]
  (if-let [existing (get graph (first maven-coord))]
    graph
    (let [item (get lookup-table maven-coord)
          graph (assoc graph (first maven-coord)
                       (update item :deps
                               (fn [dep-list]
                                 (mapv #(-> (get lookup-table
                                                 (vec (drop-last %)))
                                            (dissoc :deps))
                                       dep-list))))]
      (reduce (partial formalize-graph lookup-table)
              graph
              (->> (:deps item)
                   (map (comp vec drop-last)))))))


(defn invert-tools-deps
  "We need to build a top-down graph of what is going on.  The tools.deps is built from
  the opposite perspective as the vizdeps graph; so we need to 'invert' it of sorts."
  [tools-graph]
  (let [path-seq
        (->> tools-graph
             (mapcat (fn [[proj-name {:keys [paths select]}]]
                       (->> paths
                            (mapcat
                             (fn [[item-version path-set]]
                               (let [conflict? (not= item-version select)]
                                 (map vector path-set (repeat [proj-name
                                                               (:mvn/version item-version)
                                                               conflict?])))))))))
        path->nodes (->> (group-by first path-seq)
                         (map (fn [[k v-seq]]
                                [k (->> (map (comp vec drop-last second) v-seq)
                                        (into {}))]))
                         (into {}))
        roots (get path->nodes [])
        lookup-table (->> path-seq
                          ;;This sort ensure that parents are added before deps
                          (sort-by (comp count first))
                          (reduce (fn [graph [path maven-coords]]
                                    (let [pure-coords (vec (drop-last maven-coords))
                                          graph (assoc graph pure-coords
                                                       (get graph pure-coords
                                                            {:artifact-name (first pure-coords)
                                                             :node-id (common/gen-node-id (first pure-coords))
                                                             :version (second pure-coords)
                                                             :conflict? (last maven-coords)}))]
                                      (if-let [parent-item (expand-path path->nodes path)]
                                        (update-in graph [parent-item :deps] (fn [dep-set]
                                                                               (if dep-set
                                                                                 (conj dep-set maven-coords)
                                                                                 #{maven-coords})))
                                        graph)))
                                  {}))
        root-symbol (symbol "root")
        root-version "1.0.0"
        lookup-table (assoc lookup-table [root-symbol root-version]
                            {:artifact-name root-symbol
                             :version root-version
                             :node-id (common/gen-node-id root-symbol)
                             :root? true
                             :deps (set (map #(conj % false) roots))})]
    (reduce (partial formalize-graph lookup-table) {} [[root-symbol root-version]])))


(defn build-dot
  [fname options]
  (let [project (edn/read-string (slurp fname))
        deps-type-tree (expand-deps project)]
    (-> (invert-tools-deps deps-type-tree)
        (common/filter-dependency-graph options)
        (common/node-graph options)
        dorothy-core/digraph
        dorothy-core/dot)))


(defn vizdeps
  [& args]
  (let [fname (first args)]
    (when-let [options (common/parse-cli-options "vizdeps" common/vizdeps-cli-options args)]
      (let [dot (build-dot fname options)]
        (common/write-files-and-view dot options)))))
