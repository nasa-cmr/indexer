(ns cmr.indexer.test.services.index-service
  "Tests for index service"
  (:require [clojure.test :refer :all]
            [cmr.indexer.services.index-service :as index-svc]))

(deftest index-concept-invalid-input-test
  (testing "invalid input"
    (are [concept-id revision-id]
         (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Concept-id .* and revisoin-id .* cannot be null"
           (index-svc/index-concept {} concept-id revision-id true))

         "C123-PROV1" nil
         nil 1
         nil nil)))
