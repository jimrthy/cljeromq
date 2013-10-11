(ns cljeromq.core-test
  (:require [cljeromq.core :as core])
  (:use midje.sweet))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

(facts "Basic message exchange"
       (core/with-context [ctx 1]
         (with-socket [receiver ctx (-> core/const :socket-type :rep)]
           ;; TODO: Don't hard-code this port number
           (let [url  "tcp://localhost:10101"]
             (bind receiver url)
             (with-socket [sender ctx (-> core/const :socket-type :req)]
               (connect sender url)

               (fact "Transmit string"
                     (let [msg "abcxYz1"]
                       (core/send sender msg)
                       (let [result (core/recv receiever)]
                         msg => result)))

               (fact "Transmit keyword"
                     (let [msg :something]
                       (core/send sender msg)
                       (let [result (core/recv receiever)]
                         msg => result))))))))
