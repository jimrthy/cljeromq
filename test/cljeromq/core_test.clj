(ns cljeromq.core-test
  (:require [cljeromq.core :as core]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)]
            [midje.sweet :refer :all]))

(defn setup
  [uri client-type server-type]
    (let [ctx (ZMQ/context 1)]
        (let [client (.socket ctx client-type)]
            (.connect req uri)
            (let [server (.socket ctx server-type)]
              (.bind server uri)))))

(defn teardown
  ([ctx client server uri {:keys [unbind-server?]}]
     (when unbind-server?
       (.unbind server uri))
     (.close server)
     (.disconnect client uri)
     (.close client)
     (.term ctx))
  ([ctx client server uri]
     (teardown ctx client server uri {:unbind-server? true})))

;; Even though I'm not using JNI at all any more.
;; At least, I think it's gone.
(facts "JNI"
  (fact "Basic req/rep inproc handshake test"
        (let [uri "inproc://a-test-1"
              [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
          (try
            (let [client (future (.send req "HELO")
                                 (String. (.recv req)))]
              (let [greet (.recv rep)]
                (is ("HELO" => (String. greet))))
              (.send rep "kthxbye")
              @client => "kthxbye")
            (finally
              ;; Can't unbind inproc socket
              ;; This is actually Bug #949 in libzmq.
              ;; It should be fixed in 4.1.0, but backporting to 4.0.x
              ;; has been deemed not worth the effort
              (is (thrown-with-msg? ZMQException #"No such file or directory"
                                    (.unbind rep uri)))
              (teardown ctx req rep uri {:unbind-server? false})))))
  (fact "Basic req/rep TCP handshake test"
        (let [uri "tcp://127.0.0.1:8709"
              [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
          (try
            (let [client (future (.send req "HELO")
                                 (String. (.recv req)))]
              (let [greet (.recv rep)]
                (is ("HELO" => (String. greet))))
              (.send rep "kthxbye")
              @client => "kthxbye")
            (finally
              (teardown ctx req rep uri))))))

(facts "Basic functionality"
       (let [ctx (core/context 1)]
         (try
           (let [url "tcp://localhost:10101"
                 sender (core/socket ctx :req)
                 receiver (core/socket ctx :rep)]
             (try
               (core/bind! receiver url)
               (core/connect! sender url)

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
               (finally (core/unbind! receiver url)
                        (core/close! receiver)
                        (core/disconnect! sender url)
                        (core/close! sender))))
           (finally (core/terminate! ctx)))))

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


(facts "Check unbinding"
       (core/with-context [ctx 1]
         (core/with-socket [nothing ctx :rep]
           (let [addr "tcp://*:5678"]
             (core/bind! nothing addr)
             (trace "Have a socket bound")
             (core/unbind! nothing addr)))))
