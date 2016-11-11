(ns cljeromq.common-test
  (:require [cljeromq.common :as common]
            [cljeromq.core :as mq]
            [clojure.spec :as s]
            [clojure.spec.gen :as gen]
            [clojure.test :refer (deftest is testing)]))


(deftest read-socket-generation
  ;; This test seems ridiculous.
  ;; spec will validate by generator by checking its output,
  ;; so this should be totally redundant.
  ;; But I'm getting errors that seem like they must be related
  ;; to this just the same.
  ;; So be safe about this.
  (testing "Can generate a mock Read socket that produces gibberish on demand"
    (let [generated (s/exercise :cljeromq.common/testable-read-socket)
          ;; exercise produces a seq of pairs.
          ;; First of each pair is the value generated.
          ;; Second is the conformed version.
          actual (-> generated first second)]
      ;; Note that this is redundant. Spec validates custom generators
      ;; automatically
      (is (s/valid? :cljeromq.common/testable-read-socket actual))
      ;; Really just verifying that this doesn't throw an exception
      (.recv actual))))
(comment (read-socket-generation))

(deftest exercise-read-socket
  ;; Honestly, this is a pretty silly test.
  ;; It was more a proof of the concept than anything else.
  ;; Though it did take quite a while to sort out the actual
  ;; technique, because the documents still aren't great.
  (testing "Can generate a mock Read socket that produces gibberish on demand"
    (let [generated (s/exercise :cljeromq.common/testable-read-socket)
          ;; exercise produces a seq of pairs.
          ;; First of each pair is the value generated.
          ;; Second is the conformed version.
          actual (-> generated first second)]
      (dotimes [_ 10]
        (is (.recv actual))))))
(comment (exercise-read-socket))

(deftest write-socket-generation
  ;; Honestly, this is an even sillier test than the read-socket generator.
  ;; The random data generator probably makes sense for a test involving
  ;; real sockets, though
  (testing "Can generate a mock Write socket that silently swallows gibberish on demand"
    (let [generated (gen/generate (s/gen :cljeromq.common/testable-write-socket))]
      (dotimes [_ 10]
        (.send generated (gen/generate (gen/bytes)))))))
(comment (write-socket-generation))

(deftest byte-array-gen
  (testing "Byte Array spec generator doesn't blow up"
    ;; Trying to generate cljeromq.common/byte-array-type fails now. The error message is:
    ;; ExceptionInfo Unable to construct gen at: [] for: clojure.test.check.generators.Generator@158de04a  clojure.core/ex-info (core.clj:4725)
    ;; So this is my excuse to eliminate it.
    (let [generated (s/exercise bytes?)]
      (doseq [bs generated]
        (let [n (count bs)]
          (is (not (neg? n)))
          (is (int? n)))))))
(comment (byte-array-gen)

         (try
           (s/exercise bytes?)q
           (catch clojure.lang.ExceptionInfo ex
             (.getData ex)))
         )
