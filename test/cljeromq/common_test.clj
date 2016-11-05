(ns cljeromq.common-test
  (:require [cljeromq.common :as common]
            [cljeromq.core :as mq]
            [clojure.spec :as s]
            [clojure.spec.gen :as gen]
            [clojure.test :refer (deftest is testing)]))

(deftest socket-spec
  (testing "Basic socket spec validation"
    ;; Automating this sort of thing is pretty much the entire
    ;; point to generative testing.
    ;; Q: isn't it?
    ;; A: Well, generally. But this test is specifically because
    ;; I'm having issues with the basic spec declaration and checking.
    ;; If one socket type passes, they all will.
    ;; This gets more interesting with higher-level abstractions that
    ;; track things like socket types, URLs, and actual interactions.
    (let [ctx (mq/context 1)
          sock (mq/socket! ctx :pub)]
      (is (s/valid? :cljeromq.common/socket sock)))))

(deftest socket-generation
  (testing "Do I have this generator spec'd correctly?"
    (let [generator (s/gen :cljeromq.common/testable-read-socket)]
      (is (s/exercise generator) "Just accomplishing that much is a win"))))
(comment (socket-generation)
         )

(comment
  (let [generated
        (s/exercise :cljeromq.common/testable-read-socket)]
    (println "Generated OK. Trying to use it...")
    (let [actual (-> generated first second)]
      (dotimes [_ 10]
        (println (String. (.read actual))))))
  ;; This is generating 10 pairs the same small integers
  ;; (mostly positives, a few 0's, and 1 or two negatives).
  ;; Haven't seen anything where (< 32 (abs %)) yet
  ;; Note that the pairs are identical.
  ;; That's because s/exercise returns a seq of [val conformed-val]
  ;; tuples.
  (let [generated (s/exercise integer?)]
    (println "Generated OK. Trying to print random ints")
    (map str generated))
  )
