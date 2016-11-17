(ns cljeromq.const-test
  "Should be ridiculously simple, but I'm running out of ideas"
  (:require [cljeromq.constants :as K]
            [clojure.test :refer :all]))

(deftest verify-straight-int
  (testing "Can I specify an int flag and get back what I meant?"
    (let [wait (K/flags->const 0)]
      (is (= 0 wait)))))
(comment (verify-straight-int))

(deftest plain-keyword-flag
  (testing "Convert single keyword to a flag"
    (let [dont-wait (K/flags->const :dont-wait)]
      (is (= dont-wait 1)))))
(comment (plain-keyword-flag))

(deftest multiple-keyword-flags
  (testing "Convert seq of keywords to a bitmask"
    (let [flags [:dont-wait :send-more]]
      (is (= (K/flags->const flags) 3)))))
(comment (multiple-keyword-flags))
