(ns maven-index-search.core
  (:require [clj-http.client :as client])
  (:import (org.apache.maven.index ArtifactInfo IteratorSearchRequest MAVEN NexusIndexer)
           (org.apache.maven.index.context IndexingContext)
           (org.apache.maven.index.creator
             JarFileContentsIndexCreator MavenPluginArtifactInfoIndexCreator MinimalArtifactInfoIndexCreator)
           (org.apache.maven.index.expr UserInputSearchExpression)
           (org.apache.maven.index.updater IndexUpdater IndexUpdateRequest ResourceFetcher)
           (org.codehaus.plexus DefaultPlexusContainer PlexusContainer)))

(def ^{:dynamic true} ^PlexusContainer *plexus* (DefaultPlexusContainer.))

(def ^{:dynamic true} ^NexusIndexer *indexer* (.lookup *plexus* NexusIndexer))

(defn context ^IndexingContext [id]
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

(defn ^:private percent
  [fraction total]
  (int (/ (* fraction 100) total)))

(defn ^:private report-progress
  [bytes-read previous-total size]
  (let [new-total (+ previous-total bytes-read)]
    (if (pos? size)
      (let [previous-percent (percent previous-total size)
            new-percent (percent new-total size)]
        (when (not= previous-percent new-percent)
          (print (str new-percent "%\r"))
          (flush)))
      (do
        (print new-total "bytes read\r")
        (flush)))
    new-total))

(defn ^:private progress-input-stream
  [^java.io.InputStream input-stream size]
  (let [total-read (atom 0)]
    (proxy [java.io.InputStream] []
      (available []
        (.available input-stream))
      (close []
        (.close input-stream))
      (read 
        ([]
         (let [data (.read input-stream)]
           (when (> data -1)
             (reset! total-read (report-progress 1 @total-read size)))
           data))
        ([b]
         (let [bytes-read (.read input-stream b)]
           (when (pos? bytes-read)
             (reset! total-read (report-progress bytes-read @total-read size)))
           bytes-read))
        ([b off len]
         (let [bytes-read (.read input-stream b off len)]
           (when (pos? bytes-read)
             (reset! total-read (report-progress bytes-read @total-read size)))
           bytes-read)))
      (skip [n]
        (let [bytes-skipped (.skip input-stream n)]
          (when (pos? bytes-skipped)
            (reset! total-read (report-progress bytes-skipped @total-read size)))
          bytes-skipped)))))

(def ^:private http-resource-fetcher
  (let [base-url (ref nil)]
    (proxy [ResourceFetcher] []
      (connect [id url]
        (println "connect" id url)
        (dosync (ref-set base-url url)))
      (disconnect []
        (println "disconnect" @base-url))
      (^java.io.InputStream retrieve [name]
        (println "retrieve" @base-url name)
        (let [response (client/get (str @base-url "/" name) {:as :stream})]
          (progress-input-stream (:body response) (Integer/parseInt (get (:headers response) "content-length"))))))))

(defn update-index [context-id]
  (.fetchAndUpdateIndex ^IndexUpdater (.lookup *plexus* IndexUpdater) (IndexUpdateRequest. (context context-id) http-resource-fetcher)))

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
      (let [offset (* (dec page) 25)
            total-hits (.getTotalHitsCount search-response)
            first-result-no (inc offset)
            last-result-no (min (* page 25) total-hits)
            results (drop offset (take last-result-no search-response))]
        (println (format "showing results %d-%d/%d" first-result-no last-result-no total-hits))
        (doseq [^ArtifactInfo artifact-info results]
          (println (.groupId artifact-info) (.artifactId artifact-info) (.version artifact-info) (.description artifact-info)))))
    (.unlock context))
  (remove-context id))
