(ns clj-commons.leiningen.vizdeps
  "Graphviz visualization of project dependencies."
  (:require
    [com.walmartlabs.vizdeps.common :as common
     :refer [gen-node-id]]
    [clj-commons.leiningen.common :as lein-common]
    [leiningen.core.main :as main]
    [dorothy.core :as d]
    [leiningen.core.user :as user]
    [leiningen.core.classpath :as classpath]
    [leiningen.core.project :as project]
    [clojure.string :as str]
    [cemerick.pomegranate.aether :as aether]
    [clojure.java.io :as io]
    [clojure.stacktrace :as stacktrace])
  (:gen-class))


(defn- get-dependencies
  [project dependency]
  (-> (#'classpath/get-dependencies
       :dependencies nil
       (assoc project :dependencies [dependency]))
      (get dependency)))


(defn ^:private build-dot
  "Builds a map from artifact name (symbol) to an artifact record, with keys
  :artifact-name, :version, :node-id, :highlight?, :focus?, :conflict?, :root? and
  :deps.

  Each :dep has keys :artifact-name, :version, :conflict?, and :highlight?."
  [project options]
  (let [profiles (if-not (:dev options)
                   [:user]
                   [:user :dev])
        project' (project/set-profiles project profiles)
        root-artifact-name (symbol (-> project :group str) (-> project :name str))
        root-dependency [root-artifact-name (:version project)]
        dependency-map (lein-common/flatten-dependencies project')
        root-dependencies (->> project'
                               :dependencies
                               (map common/normalize-artifact))]
    (common/build-dot (partial get-dependencies project)
                      dependency-map root-artifact-name
                      (:version project)
                      root-dependencies
                      options)))


(defn vizdeps
  "Visualizes dependencies using Graphviz.

  Normally, this will generate an image and raise a frame to view it.
  Command line options allow the image to be written to a file instead."
  {:pass-through-help true}
  [project & args]
  (when-let [options (common/parse-cli-options "vizdeps" common/vizdeps-cli-options args)]
    (let [dot (build-dot project options)]
      (common/write-files-and-view dot options)
      (main/info "Wrote dependency chart to:" (:output-path options)))))



(defn- insecure-http-abort [& _]
  (let [repo (promise)]
    (reify org.apache.maven.wagon.Wagon
      (getRepository [this])
      (setTimeout [this _])
      (setInteractive [this _])
      (addTransferListener [this _])
      (^void connect [this
                      ^org.apache.maven.wagon.repository.Repository the-repo
                      ^org.apache.maven.wagon.authentication.AuthenticationInfo _
                      ^org.apache.maven.wagon.proxy.ProxyInfoProvider _]
       (deliver repo the-repo) nil)
      (get [this resource _]
        (main/abort "Tried to use insecure HTTP repository without TLS:\n"
                    (str (.getId @repo) ": " (.getUrl @repo) "\n " resource) "\n"
                    "\nThis is almost certainly a mistake; for details see"
                    "\nhttps://github.com/technomancy/leiningen/blob/master/doc/FAQ.md")))))


(defn- configure-http
  "Set Java system properties controlling HTTP request behavior."
  []
  (System/setProperty "aether.connector.userAgent" (main/user-agent))
  (when-let [{:keys [host port non-proxy-hosts]} (classpath/get-proxy-settings)]
    (System/setProperty "http.proxyHost" host)
    (System/setProperty "http.proxyPort" (str port))
    (when non-proxy-hosts
      (System/setProperty "http.nonProxyHosts" non-proxy-hosts)))
  (when-let [{:keys [host port]} (classpath/get-proxy-settings "https_proxy")]
    (System/setProperty "https.proxyHost" host)
    (System/setProperty "https.proxyPort" (str port))))


(defn leiningen
  "Command-line entry point."
  [& raw-args]
  (try
    (project/ensure-dynamic-classloader)
    (aether/register-wagon-factory! "http" insecure-http-abort)
    (let [project (if (.exists (io/file main/*cwd* "project.clj"))
                    (project/read (str (io/file main/*cwd* "project.clj")))
                    (throw (ex-info "Failed to find project" {})))]
      (configure-http)
      (vizdeps project raw-args))
    (catch Exception e
      (if (or main/*debug* (not (:exit-code (ex-data e))))
        (stacktrace/print-cause-trace e)
        (when-not (:suppress-msg (ex-data e))
          (println (.getMessage e))))
      (flush))))
