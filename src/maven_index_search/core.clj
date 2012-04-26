(ns maven-index-search.core
  (:import (org.apache.maven.index DefaultNexusIndexer)))

(def ^{:dynamic true} *indexer* (DefaultNexusIndexer.))

(defn search
  "Search the given maven index with a query string."
  [index query]
  nil)
