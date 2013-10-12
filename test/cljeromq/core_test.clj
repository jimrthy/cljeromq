(ns cljeromq.core-test
  (:require [cljeromq.core :as core]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)])
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

               (info "Starting tests")
               ;; TODO: Really should split these up.
               ;; Configuring the context and sockets is part of setUp.
               ;; That would allow tests to proceed after a previous
               ;; one fails.
               ;; OTOH: Really should be using :dealer and :router...
               ;; except that, for this scenario, :req and :rep are
               ;; actually exactly what I want, once this actually works.

               (fact "Transmit string"
                     (let [msg "xbcAzy"]
                       (comment (trace "Sending " msg))
                       (core/send sender msg)
                       (comment (trace "Receiving"))
                       (let [received (core/recv receiver :wait)]
                         received => msg)))
               (comment (trace "String sent and received"))

               (fact "Transmit keyword"
                     (let [msg :message]
                       (trace "Sending: " msg)
                       (core/send receiver msg)
                       (trace msg " -- sent")
                       (let [received (core/recv sender)]
                         received => msg)))

               (fact "Transmit sequence"
                     (let [msg (list :a 3 "abc")]
                       (core/send sender msg)
                       (let [received (core/recv receiver)]
                         received => msg)))

               (future-fact "Transmit integer"
                     (let [msg 1000]
                       (core/send receiver msg)
                       (let [received (core/raw-recv sender)]
                         received => msg)))

               (future-fact "Transmit float"
                     (let [msg Math/PI]
                       (core/send sender msg)
                       (let [received (core/raw-recv receiver)]
                         received => msg)))

               (future-fact "Transmit big integer"
                     (let [msg 1000M]
                       (core/send receiver msg)
                       (let [received (core/raw-recv sender)]
                         received => msg)))

               (comment (future-fact "Transmit multiple sequences"
                                     ;; Q: What could this look like?
                                     ))
               (finally (core/close receiver)
                        (core/close sender))))
           (finally (core/terminate ctx)))))

(facts "Basic message exchange with macros"
       (trace "Setting up context")
       (core/with-context [ctx 1]
         (trace "Setting up receiver")
         (core/with-socket [receiver ctx :rep]
           (fact "Macro created local"
                 receiver => receiver)
           (trace "Receiver: " receiver)

           ;; TODO: Don't hard-code this port number
           (let [url  "tcp://localhost:10101"]
             (trace "Binding receiver")
             (core/bind receiver url)
             (trace "Setting up sender")
             (core/with-socket [sender ctx :req]
               (trace "Connecting sender")
               (core/connect sender url)

               (fact "Connected"
                     0 => 0)

               (fact "Transmit string"
                     (let [msg "abcxYz1"]
                       (core/send sender msg)
                       (let [result (core/recv receiver)]
                         msg => result)))

               (fact "Transmit keyword"
                     (let [msg :something]
                       (core/send receiver msg)
                       (let [result (core/recv sender)]
                         msg => result))))))))
