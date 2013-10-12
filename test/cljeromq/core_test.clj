(ns cljeromq.core-test
  (:require [cljeromq.core :as core])
  (:use midje.sweet))

(facts "Basic functionality"
       (let [ctx (core/context 1)]
         (try
           (let [url "tcp://localhost:10101"
                 sender (core/socket ctx :req)
                 receiver (core/socket ctx :rep)]
             (try
               (core/bind receiver url)
               (core/connect sender url)

               (println "Starting tests")

               (fact "Transmit string"
                     (let [msg "xbcAzy"]
                       (println "Sending " msg)
                       (core/send sender msg)
                       (println "Receiving")
                       (let [received (core/recv receiver :wait)]
                         received => msg)))
               (println "String sent and received")

               (fact "Transmit keyword"
                     (let [msg :message]
                       (println "Sending " msg)
                       (core/send receiver msg)
                       (println msg " sent")
                       (let [received (core/recv sender)]
                         msg => received)))

               (fact "Transmit sequence"
                     (let [msg (list :a 3 "abc")]
                       (core/send sender msg)
                       (let [received (core/recv receiver)]
                         msg => received)))

               (fact "Transmit integer"
                     (let [msg 1000]
                       (core/send receiver msg)
                       (let [received (core/raw-recv sender)]
                         msg => received)))

               (fact "Transmit float"
                     (let [msg Math/PI]
                       (core/send sender msg)
                       (let [received (core/raw-recv receiver)]
                         msg => received)))

               (fact "Transmit big integer"
                     (let [msg 1000M]
                       (core/send receiver msg)
                       (let [received (core/raw-recv sender)]
                         msg => received)))

               (future-fact "Transmit multiple sequences"
                            ;; Q: What could this look like?
                            )
               (finally (core/close receiver)
                        (core/close sender))))
           (finally (core/terminate ctx)))))

(facts "Basic message exchange with macros"
       (core/with-context [ctx 1]
         (core/with-socket [receiver ctx :rep]
           receiver => receiver

           ;; TODO: Don't hard-code this port number
           (let [url  "tcp://localhost:10101"]
             (core/bind receiver url)
             (core/with-socket [sender ctx :req]
               (core/connect sender url)

               (fact "Connected"
                     0 => 0)

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
