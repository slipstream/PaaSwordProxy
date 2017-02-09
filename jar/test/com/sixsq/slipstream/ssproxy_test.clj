(ns com.sixsq.slipstream.ssproxy-test
  (:require
    [clojure.test :refer :all]
    [com.sixsq.slipstream.ssproxy :as sp]))

(deftest test-assert-contians
  (is (nil? (sp/assert-contians {"a" 1} ["a"])))
  (is (thrown? Exception (sp/assert-contians {} ["a"])))
  )

(deftest test-res-comp-run-uuid
  (is (= "foo" (sp/res-comp-run-uuid {"status"  123
                                      "message" "failure"
                                      "comp1"   {"run-url" "foo"}}))))

