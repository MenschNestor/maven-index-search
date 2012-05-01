(ns maven-index-search.core
  (:require [clj-http.client :as client])
  (:import (org.apache.maven.index DefaultNexusIndexer)
           (org.apache.maven.index.creator
             JarFileContentsIndexCreator MavenPluginArtifactInfoIndexCreator MinimalArtifactInfoIndexCreator)
           (org.apache.maven.index.updater DefaultIndexUpdater IndexUpdateRequest ResourceFetcher)))

(def ^{:dynamic true} *indexer* (DefaultNexusIndexer.))

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

(defn remove-context [id & {:keys [delete-files] :or {delete-files false}}]
  (when-let [context (context id)]
    (.removeIndexingContext *indexer* context delete-files)))

(def ^:private http-resource-fetcher
  (let [base-url (ref nil)]
    (proxy [ResourceFetcher] []
      (connect [id url]
        (println "connect" id url)
        (dosync (ref-set base-url url)))
      (disconnect [])
      (retrieve [name]
        (println "retrieve" @base-url name)
        (client/get (str @base-url "/" name) {:as :stream})))))

(defn update-index [context-id]
  (.fetchAndUpdateIndex (DefaultIndexUpdater.) (IndexUpdateRequest. (context context-id) http-resource-fetcher)))

(defn search
  "Search the given maven index with a query string."
  [index query]
  nil)
