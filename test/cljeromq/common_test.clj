(ns cljeromq.common-test
  (:require [cljeromq.common :as common]
            [cljeromq.core :as mq]
            [clojure.spec :as s]
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
    (let [generator (s/gen :cljeromq.common/socket)]
      (is (generator) "Just accomplishing that much is a win"))))
(comment (socket-generation)
         )
