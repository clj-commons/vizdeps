(ns com.walmartlabs.vizdeps.common
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [dorothy.jvm :as d]
    [com.stuartsierra.dependency :as dep]
    [clojure.java.browse :refer [browse-url]]
    [clojure.tools.cli :refer [parse-opts]]
    [dorothy.core :as dorothy-core])
  (:import (java.io File)))

(defn gen-node-id
  "Create a unique string, based on the provided node-name (keyword, symbol, or string)."
  [node-name]
  (str (gensym (str (name node-name) "-"))))

(defn ^:private allowed-extension
  [path]
  (let [x (str/last-index-of path ".")
        ext (subs path (inc x))]
    (#{"png" "pdf"} ext)))

(defn graph-attrs
  [options]
  {:rankdir (if (:vertical options) :TD :LR)})

(def cli-help ["-h" "--help" "This usage summary."])

(def cli-save-dot ["-s" "--save-dot" "Save the generated GraphViz DOT file well as the output file."])

(def cli-no-view
  ["-n" "--no-view" "If given, the image will not be opened after creation."
   :default false])

(defn cli-output-file
  [default-path]
  ["-o" "--output-file FILE" "Output file path. Extension chooses format: pdf or png."
   :id :output-path
   :default default-path
   :validate [allowed-extension "Supported output formats are 'pdf' and 'png'."]])

(def cli-vertical
  ["-v" "--vertical" "Use a vertical, not horizontal, layout."])

(defn conj-option
  "Used as :assoc-fn for an option to conj'es the values together."
  [m k v]
  (update m k conj v))

(defn ^:private usage
  [command summary errors]
  (->> [(str "Usage: lein " command " [options]")
        ""
        "Options:"
        summary]
       (str/join \newline)
       println)

  (when errors
    (println "\nErrors:")
    (doseq [e errors] (println " " e)))

  nil)


(def vizdeps-cli-options
  [["-d" "--dev" "Include :dev dependencies in the graph."]
   ["-f" "--focus ARTIFACT" "Excludes artifacts whose names do not match a supplied value. Repeatable."
    :assoc-fn conj-option]
   ["-H" "--highlight ARTIFACT" "Highlight the artifact, and any dependencies to it, in blue. Repeatable."
    :assoc-fn conj-option]
   cli-no-view
   (cli-output-file "target/dependencies.pdf")
   ["-p" "--prune" "Exclude artifacts and dependencies that do not involve version conflicts."]
   cli-save-dot
   cli-vertical
   cli-help])


(defn parse-cli-options
  "Parses the CLI options; handles --help and errors (returning nil) or just
  returns the parsed options."
  [command cli-options args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (if (or (:help options) errors)
      (usage command summary errors)
      options)))

(defn write-files-and-view
  "Given a Graphviz document string (dot) and CLI options, write the file(s) and, optionally,
  open the output file."
  [dot options]
  (let [{:keys [output-path no-view]} options
        ^File output-file (io/file output-path)
        output-format (-> output-path allowed-extension keyword)
        output-dir (.getParentFile output-file)]

    (when output-dir
      (.mkdirs output-dir))

    (when (:save-dot options)
      (let [x (str/last-index-of output-path ".")
            dot-path (str (subs output-path 0 x) ".dot")
            ^File dot-file (io/file dot-path)]
        (spit dot-file dot)))

    (d/save! dot output-file {:format output-format})

    (when-not no-view
      (browse-url output-path)))
  nil)

(defn build-dependency-map
  "Consumes a hierarchy and produces a map from artifact to version, used to identify
  which dependency linkages have had their version changed."
  ([hierarchy]
   (build-dependency-map {} hierarchy))
  ([version-map hierarchy]
   (reduce-kv (fn [m dep sub-hierarchy]
                (-> m
                    (assoc (first dep) dep)
                    (build-dependency-map sub-hierarchy)))
              version-map
              hierarchy)))

(defn matches-any
  "Creates a predicate that is true when the provided elem (string, symbol, keyword)
  matches any of the provided string names."
  [names]
  (fn [elem]
    (let [elem-name (name elem)]
      (some #(str/includes? elem-name %) names))))


(defn artifact->label
  [artifact]
  {:pre [artifact]}
  (let [{:keys [artifact-name version]} artifact
        ^String group (some-> artifact-name namespace name)
        ^String module (name artifact-name)]
    (str group
         (when group "/\n")
         module
         \newline
         version)))


(defn normalize-artifact
  [dependency]
  (let [artifact (first dependency)]
    (if-not (= (namespace artifact) (name artifact))
      dependency
      (assoc dependency 0 (symbol (name artifact))))))


(defn dependency-order
  "Returns the artifact names in dependency order."
  [artifacts]
  (let [tuples (for [artifact (vals artifacts)
                     dep (:deps artifact)]
                 [(:artifact-name artifact)
                  (:artifact-name dep)])
        graph (reduce (fn [g [artifact-name dependency-name]]
                        (dep/depend g artifact-name dependency-name))
                      (dep/graph)
                      tuples)]
    (dep/topo-sort graph)))

(defn prune-artifacts
  "Navigates the nodes to identify dependencies that include conflicts.
  Marks nodes that are referenced with conflicts, then marks any nodes that
  have a dependency to that node as well. The root node is always kept;
  other unmarked nodes are culled."
  [artifacts]
  (let [order (dependency-order artifacts)
        mark-graph (fn [artifacts artifact-name]
                     (assoc-in artifacts [artifact-name :conflict?] true))
        get-transitives (fn [artifacts artifact]
                          (->> artifact
                               :deps
                               (filter #(->> %
                                             :artifact-name
                                             artifacts
                                             ;; May be nil here, when an earlier processed artifact
                                             ;; was culled.  Otherwise, check if the :conflict? flag
                                             ;; was set on the artifact.
                                             :conflict?))))
        marked-graph (reduce (fn [artifacts-1 artifact-name]
                               (->> (artifacts-1 artifact-name)
                                    :deps
                                    (filter :conflict?)
                                    (map :artifact-name)
                                    (reduce mark-graph artifacts-1)))
                             artifacts
                             order)]
    (reduce (fn [artifacts-1 artifact-name]
              (let [artifact (artifacts-1 artifact-name)
                    ;; Get transitive dependencies to conflict artifacts (dropping
                    ;; dependencies to non-conflict artifacts, if any).
                    transitives (get-transitives artifacts-1 artifact)
                    keep? (or (:conflict? artifact)
                              (:root? artifact)
                              (seq transitives))]
                (if keep?
                  (assoc artifacts-1 artifact-name
                         (assoc artifact
                                :conflict? true
                                :deps transitives))
                  ;; Otherwise we don't need this artifact at all
                  (dissoc artifacts-1 artifact-name))))
            marked-graph
            order)))


(defn highlight-artifacts
  [artifacts highlight-terms]
  (let [highlight-set (->> artifacts
                           keys
                           (filter (matches-any highlight-terms))
                           set)
        artifacts-highlighted (reduce (fn [m artifact-name]
                                        (assoc-in m [artifact-name :highlight?] true))
                                      artifacts
                                      highlight-set)
        ;; Now, find dependencies that target highlighted artifacts
        ;; and mark them as highlighted as well.
        add-highlight (fn [dep]
                        (if (-> dep :artifact-name highlight-set)
                          (assoc dep :highlight? true)
                          dep))]
    (reduce-kv (fn [artifacts-3 artifact-name artifact]
                 (assoc artifacts-3 artifact-name
                        (update artifact :deps
                                #(map add-highlight %))))
               {}
               artifacts-highlighted)))


(defn apply-focus
  "Identify a number of artifacts that match a focus term.  Only keep such artifacts, and those
  that transitively depend on them."
  [artifacts focus-terms]
  (let [focus-set (->> artifacts
                       keys
                       (filter (matches-any focus-terms))
                       set)
        keep-focus (fn [artifacts dep]
                     (-> artifacts
                         (get (:artifact-name dep))
                         :focused?))
        reducer (fn [m artifact-name]
                  (let [artifact (get artifacts artifact-name)
                        focus-deps (->> artifact
                                        :deps
                                        (filter #(keep-focus m %)))]
                    (if (or (focus-set artifact-name)
                            (seq focus-deps))
                      (assoc m artifact-name
                             (assoc artifact :focused? true
                                    :deps focus-deps))
                      m)))]
    (reduce reducer
            {}
            (dependency-order artifacts))))


(def ^:dynamic *get-dependencies*)


(defn ^:private immediate-dependencies
  [dependency]
  (if (some? dependency)
    (->> (*get-dependencies* dependency)
         (remove #(= 'org.clojure/clojure (first %)))
         vec)
    []))


(declare ^:private add-dependency-tree)

(defn add-dependency-node
  [dependency-graph artifact-name artifact-version dependencies]
  (reduce (fn [g dep]
            (let [[dep-name dep-version] dep
                  g-1 (add-dependency-tree g dep-name dep-version)]
              (if-let [dep-artifact (get-in g-1 [:artifacts dep-name])]
                (let [dep-map {:artifact-name dep-name
                               :version dep-version
                               :conflict? (not= dep-version
                                                (:version dep-artifact))}]
                  (update-in g-1 [:artifacts artifact-name :deps] conj dep-map))
                ;; If the artifact is excluded, the dependency graph will
                ;; not contain the artifact.
                g-1)))
          ;; Start with a new node for the artifact
          (assoc-in dependency-graph
                    [:artifacts artifact-name]
                    {:artifact-name artifact-name
                     :version artifact-version
                     :node-id (gen-node-id artifact-name)
                     :deps []})
          dependencies))


(defn ^:private add-dependency-tree
  [dependency-graph artifact-name artifact-version]
  (let [[_ resolved-version :as resolved-dependency] (get-in dependency-graph [:dependencies artifact-name])
        ;; When using managed dependencies, the version (from :dependencies) may be nil,
        ;; so subtitute the version from the resolved dependency in that case.
        version (or artifact-version resolved-version)
        artifact (get-in dependency-graph [:artifacts artifact-name])]

    ;; (main/debug (format "Processing %s %s"
    ;;                     (str artifact-name) version))

    (cond

      (nil? resolved-dependency)
      dependency-graph

      ;; Has the artifact already been added?
      (some? artifact)
      dependency-graph

      :else
      (add-dependency-node dependency-graph
                           artifact-name
                           resolved-version
                           ;; Find the dependencies of the resolved (not requested) artifact
                           ;; and version. Recursively add those artifacts to the graph
                           ;; and set up dependencies.
                           (immediate-dependencies resolved-dependency)))))


(defn build-dependency-graph
  [get-deps-fn dependency-map root-name root-version root-dependencies options]
  (let [{:keys [prune highlight focus]} options]
    (with-bindings {#'*get-dependencies* get-deps-fn}
      (-> (add-dependency-node {:artifacts {}
                                :dependencies dependency-map}
                               root-name
                               root-version
                               root-dependencies)
          ;; Just need the artifacts from here out
          :artifacts
          ;; Ensure the root artifact is drawn properly and never pruned
          (assoc-in [root-name :root?] true)
          (cond->
              prune
            prune-artifacts

            (seq focus)
            (apply-focus focus)

            (seq highlight)
            (highlight-artifacts highlight))))))


(defn node-graph
  [artifacts options]
  (concat
    [(dorothy-core/graph-attrs (graph-attrs options))]
    ;; All nodes:
    (for [artifact (vals artifacts)]
      [(:node-id artifact)
       (cond-> {:label (artifact->label artifact)}
         (:root? artifact)
         (assoc :shape :doubleoctagon)

         (:highlight? artifact)
         (assoc :color :blue
                :penwidth 2
                :fontcolor :blue))])

    ;; Now, all edges:
    (for [artifact (vals artifacts)
          :let [node-id (:node-id artifact)]
          dep (:deps artifact)]
      [node-id
       (get-in artifacts [(:artifact-name dep) :node-id])
       (cond-> {}
         (:highlight? dep)
         (assoc :color :blue
                :penwidth 2
                :weight 100)

         (:conflict? dep)
         (assoc :color :red
                :penwidth 2
                :weight 500
                :label (:version dep)))])))


(defonce build-dot-example-arguments
  '{:dependency-map {dorothy [dorothy "0.0.7"],
                     medley [medley "1.0.0"],
                     com.stuartsierra/dependency [com.stuartsierra/dependency "0.2.0"],
                     org.clojure/clojure [org.clojure/clojure "1.9.0"],
                     org.clojure/core.specs.alpha [org.clojure/core.specs.alpha "0.1.24"],
                     org.clojure/spec.alpha [org.clojure/spec.alpha "0.1.143"],
                     org.clojure/tools.cli [org.clojure/tools.cli "0.4.1"]},
    :root-dependencies ([org.clojure/clojure "1.9.0"]
                        [org.clojure/tools.cli "0.4.1"]
                        [com.stuartsierra/dependency "0.2.0"]
                        [dorothy "0.0.7"]
                        [medley "1.0.0"]),
    :root-name clj-commons/vizdeps,
    :root-version "0.1.7-SNAPSHOT"})


(defn build-dot
  [get-deps-fn dependency-map root-name root-version root-dependencies options]
  (-> (build-dependency-graph get-deps-fn dependency-map root-name root-version root-dependencies options)
      (node-graph options)
      dorothy-core/digraph
      dorothy-core/dot))
