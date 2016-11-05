(ns cljeromq.common-test
  (:require [cljeromq.common :as common]
            [cljeromq.core :as mq]
            [clojure.spec :as s]
            [clojure.spec.gen :as gen]
            [clojure.test :refer (deftest is testing)]))


(deftest read-socket-generation
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
        (is (.read actual))))))
(comment (read-socket-generation))

(deftest write-socket-generation
  ;; Honestly, this is an even sillier test than the read-socket generator.
  ;; The random data generator probably makes sense for a test involving
  ;; real sockets, though
  (testing "Can generate a mock Write socket that silently swallows gibberish on demand"
    (let [generated (gen/generate (s/gen :cljeromq.common/testable-write-socket))]
      (dotimes [_ 10]
        (.write generated (gen/generate (gen/bytes)))))))
(comment (write-socket-generation))

(deftest byte-array-gen
  (testing "Byte Array spec generator doesn't blow up"
    (let [generated (s/exercise (s/gen :cljeromq.commn/byte-array-type))]
      (doseq [bs generated]
        ;; TODO: Come up with a better test than this
        (is bs)))))
(comment (byte-array-gen))
