(ns maven-index-search.core
  (:require [clj-http.client :as client])
  (:import (org.apache.maven.index IteratorSearchRequest MAVEN NexusIndexer)
           (org.apache.maven.index.creator
             JarFileContentsIndexCreator MavenPluginArtifactInfoIndexCreator MinimalArtifactInfoIndexCreator)
           (org.apache.maven.index.expr UserInputSearchExpression)
           (org.apache.maven.index.updater IndexUpdater IndexUpdateRequest ResourceFetcher)
           (org.codehaus.plexus DefaultPlexusContainer)))

(def ^{:dynamic true} *plexus* (DefaultPlexusContainer.))

(def ^{:dynamic true} *indexer* (.lookup *plexus* NexusIndexer))

(defn context [id]
  (.get (.getIndexingContexts *indexer*) id))

(def ^:private default-indexers
  (java.util.ArrayList.
    [(MinimalArtifactInfoIndexCreator.) (JarFileContentsIndexCreator.) (MavenPluginArtifactInfoIndexCreator.)]))

(defn add-context
  [id repository-url local-index
   & {:keys [repository-id local-cache update-url indexers] :or {repository-id repository-url indexers default-indexers}}]
  (if-let [context (context id)]
    context
    (.addIndexingContextForced *indexer* id repository-id local-cache local-index repository-url update-url indexers)))

(defn remove-context [id & {:keys [delete-files?] :or {delete-files? false}}]
  (when-let [context (context id)]
    (.removeIndexingContext *indexer* context delete-files?)))

(def ^:private http-resource-fetcher
  (let [base-url (ref nil)]
    (proxy [ResourceFetcher] []
      (connect [id url]
        (println "connect" id url)
        (dosync (ref-set base-url url)))
      (disconnect []
        (println "disconnect" @base-url))
      (retrieve [name]
        (println "retrieve" @base-url name)
        (:body (client/get (str @base-url "/" name) {:as :stream}))))))

(defn update-index [context-id]
  (.fetchAndUpdateIndex (.lookup *plexus* IndexUpdater) (IndexUpdateRequest. (context context-id) http-resource-fetcher)))

(defn search-repository
  [[id url] query page & {:keys [update?] :or {:update? false}}]
  (let [local-index (clojure.java.io/file "target/index" id)]
    (add-context id url local-index)
    (when update?
      (update-index id)))
  (let [search-expression (UserInputSearchExpression. query)
        artifact-id-query (.constructQuery *indexer* MAVEN/ARTIFACT_ID search-expression)
        context (context id)]
    (.lock context)
    (with-open [search-response (.searchIterator *indexer* (IteratorSearchRequest. artifact-id-query context))]
      (doseq [artifact-info search-response]
        (println (.groupId artifact-info) (.artifactId artifact-info) (.version artifact-info) (.description artifact-info))))
    (.unlock context))
  (remove-context id))
