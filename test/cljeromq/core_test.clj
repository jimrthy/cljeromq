(ns cljeromq.core-test
  (:import [clojure.lang ExceptionInfo]
           [org.zeromq.jni ZMQ])
  (:require [cljeromq.core :as core]
            [clojure.test :refer (deftest is)]))

(defn setup
  [uri client-type server-type]
    (let [ctx (core/context 1)]
        (let [client (core/socket! ctx client-type)]
            (core/connect! client uri)
            (let [server (core/socket! ctx server-type)]
              (core/bind! server uri)
              [ctx client server]))))

(defn teardown
  ([{:keys [context client server uri unbind-server?]}]
     (when unbind-server?
       (core/unbind! server uri))
     (core/close! server)
     (core/disconnect! client uri)
     (core/close! client)
     (core/terminate! context))
  ([ctx client server uri]
     (teardown {:context ctx
                :client client
                :server server
                :uri uri
                :unbind-server? true})))

(deftest inproc-req-rep-handshake
  []
  (let [uri "inproc://a-test-1"
        [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
    (try
      (let [client (future (core/send! req "HELO")
                           (String. (core/recv! req)))]
        (let [greet (core/recv! rep)]
          (is (= (String. greet) "HELO")
              "String transmission failed"))
        (core/send! rep "kthxbye")
        (is (= @client "kthxbye")))
      (finally
        ;; Can't unbind inproc socket
        ;; This is actually Bug #949 in libzmq.
        ;; It should be fixed in 4.1.0, but backporting to 4.0.x
        ;; has been deemed not worth the effort
        (try
          (core/unbind! rep uri)
          (is false "0mq bug got fixed")
          (catch ExceptionInfo ex
            (is (= ex "No such file or directory"))))
        (teardown {:context ctx
                   :client req
                   :server rep
                   :uri uri
                   :unbind-server? false})))))

(deftest tcp-req-req-handshake
  []
  (let [uri "tcp://127.0.0.1:8709"
        [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
    (try
      (let [client (future (core/send! req "HELO")
                           (String. (core/recv! req)))]
        (let [greet (core/recv! rep)]
          (is (= "HELO" (String. greet))))
        (core/send! rep "kthxbye")
        (is (= @client "kthxbye")))
      (finally
        (teardown ctx req rep uri)))))

(deftest basic-send-receive
  []
  (let [ctx (core/context 1)]
    (try
      (let [url "tcp://localhost:10101"
            sender (core/socket! ctx :req)
            receiver (core/socket! ctx :rep)]
        (try
          (core/bind! receiver url)
          (core/connect! sender url)

          (println "Starting tests")
          ;; TODO: Really should split these up.
          ;; Configuring the context and sockets is part of setUp.
          ;; That would allow tests to proceed after a previous
          ;; one fails.
          ;; OTOH: Really should be using :dealer and :router...
          ;; except that, for this scenario, :req and :rep are
          ;; actually exactly what I want, once this actually works.

          (let [msg "xbcAzy"]
            (comment (println "Sending " msg))
            (core/send! sender msg)
            (comment (println "Receiving"))
            (let [received (core/recv! receiver :wait)]
              (is (= received msg) "Didn't receive what was sent")))
          (comment (println "String sent and received"))

          (let [msg :message]
            (println "Sending: " msg)
            (core/send! receiver msg)
            (println msg " -- sent")
            (let [received (core/recv! sender)]
              (is (= received msg)
                  "Transmitting keyword failed")))

          (let [msg (list :a 3 "abc")]
            (core/send! sender msg)
            (let [received (core/recv! receiver)]
              (is (= received msg) "Transmitting sequence")))

          (comment (future-fact "Transmit integer"
                                (let [msg 1000]
                                  (core/send! receiver msg)
                                  (let [received (core/raw-recv! sender)]
                                    received => msg))))

          (comment (future-fact "Transmit float"
                                (let [msg Math/PI]
                                  (core/send! sender msg)
                                  (let [received (core/raw-recv! receiver)]
                                    received => msg))))

          (comment (future-fact "Transmit big integer"
                                (let [msg 1000M]
                                  (core/send! receiver msg)
                                  (let [received (core/raw-recv sender)]
                                    received => msg))))

          (comment (future-fact "Transmit multiple sequences"
                                ;; Q: What could this look like?
                                ))
          (finally (core/unbind! receiver url)
                   (core/close! receiver)
                   (core/disconnect! sender url)
                   (core/close! sender))))
      (finally (core/terminate! ctx)))))

(deftest messaging-macros []
  (println "Setting up context")
  (core/with-context [ctx 1]
    (println "Setting up receiver")
    (core/with-socket [receiver ctx :rep]
      (is receiver "Macro didn't create local")
      (println "Receiver: " receiver)

      ;; TODO: Don't hard-code this port number
      (let [url  "tcp://localhost:10101"]
        (println "Binding receiver")
        (core/bind! receiver url)
        (println "Setting up sender")
        (core/with-socket [sender ctx :req]
          (println "Connecting sender")
          (core/connect! sender url)

          (is (= 0 0) "Connecting sockets broke reality")

          (let [msg "abcxYz1"]
            (core/send! sender msg)
            (let [result (core/recv! receiver)]
              (is (= msg result) "Echoing failed")))

          (let [msg :something]
            (core/send! receiver msg)
            (let [result (core/recv! sender)]
              (is (= msg result) "Echoing keyward failed"))))))))

(deftest check-unbinding []
  (core/with-context [ctx 1]
    (core/with-socket [nothing ctx :rep]
      (let [addr "tcp://*:5678"]
        (core/bind! nothing addr)
        (println "Have a socket bound")
        (core/unbind! nothing addr)))))

(deftest string->bytes->string []
  (let [s "The quick red fox jumped over the lazy brown dog"
        bs (core/string->bytes s)
        round-tripped (core/bytes->string bs)]
    (is (= round-tripped s) "Conversion failed")
    (is (= (class bs) core/byte-array-class))))

(deftest url-basics []
  (let [url {:protocol :tcp
             :address [0 0 0 0]
             :port 7681}]
    (is (= "tcp://0.0.0.0:7681"
           (core/connection-string url)))))
