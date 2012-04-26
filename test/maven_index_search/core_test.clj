(ns maven-index-search.core-test
  (:use clojure.test
        maven-index-search.core))

(deftest core
  (testing "search fn"
    (is (not (nil? (search nil "test"))))))
