(ns cljeromq.core-test
  (:import [org.zeromq ZMQ ZMQException])
  (:require [cljeromq.core :as core]
            [clojure.test :refer [deftest testing is]]))

(defn setup
  [uri client-type server-type]
  (let [ctx (ZMQ/context 2)]
    (let [client (.socket ctx client-type)]
      (.connect client uri)
      (let [server (.socket ctx server-type)]
        (.bind server uri)
        [ctx client server]))))

(defn teardown
  ([{:keys [context client server uri unbind-server?]}]
     (when unbind-server?
       (.unbind server uri))
     (.close server)
     (.disconnect client uri)
     (.close client)
     (.term context))
  ([ctx client server uri]
     (teardown {:context ctx
                :client client
                :server server
                :uri uri
                :unbind-server? true})))

;; Even though I'm not using JNI at all any more.
;; At least, I think it's gone.
(deftest test-jni
  (testing "Basic req/rep inproc handshake test"
        (let [uri "inproc://a-test-1"
              [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
          (try
            (let [client (future (.send req "HELO")
                                 (String. (.recv req)))]
              (let [greet (.recv rep)]
                (is (= "HELO" (String. greet))))
              (.send rep "kthxbye")
              (is (= "kthxbye" @client)))
            (finally
              (.unbind rep uri)
              (teardown {:context ctx
                         :client req
                         :server rep
                         :uri uri
                         :unbind-server? false})))))
  (testing "Basic req/rep TCP handshake test"
        (let [uri "tcp://127.0.0.1:8709"
              [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
          (try
            (let [client (future (.send req "HELO")
                                 (println "HELO sent from client. Waiting on response from server")
                                 (let [client-result (String. (.recv req))]
                                   (println "Server response to handshake received")
                                   client-result))]
              (let [greet (.recv rep)]
                (println "Server received greeting from client")
                (is (= "HELO" (String. greet))))
              (println "Sending response from server to client")
              (let [response "kthxbye"]
                (.send rep response)
                (println "Verifying that client received the response we sent")
                (is (= response @client))))
            (finally
              (teardown ctx req rep uri))))))

(defn req-rep-wrapper
  [msg]
 (let [uri "tcp://127.0.0.1:10101"
       [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
   (try
     (println "Sending" msg)
     (core/send! req msg 0)
     (println "Waiting to receive")
     (let [received (core/recv! rep 0)]
       (is (= msg received)))
     (finally
       (println "Tearing down")
       (teardown ctx req rep uri)))))

(deftest transmit-string
  (req-rep-wrapper "xbcAzy"))

(deftest transmit-keyword
  (req-rep-wrapper :message))

(deftest transmit-sequence
  (req-rep-wrapper (list :a 3 "abc")))

(deftest transmit-integer
  (req-rep-wrapper 1000))

(deftest transmit-float
  (req-rep-wrapper Math/PI))

(deftest transmit-bigint
  (req-rep-wrapper 1000000M))

(comment
  ;; Q: What would this look like?
  (deftest transmit-multiple-sequences
    (req-rep-wrapper )))

(deftest basic-macros
  (testing "Basic message exchange with macros"
         (println "Setting up context")
         (core/with-context [ctx 1]
           (println "Setting up receiver")
           (core/with-socket! [receiver ctx :rep]
             (testing "Macro created local"
               (is (= receiver receiver)))
             (println "Receiver: " receiver)

             ;; TODO: Don't hard-code this port number
             (let [url  "tcp://127.0.0.1:10102"]
               (println "Binding receiver")
               (core/bind! receiver url)
               (println "Setting up sender")
               (core/with-socket! [sender ctx :req]
                 (println "Connecting sender")
                 (core/connect! sender url)

                 (testing "Connected"
                   (is (= 0 0)))

                 (testing "Transmit string"
                   (let [msg "abcxYz1"]
                     (core/send! sender msg 0)
                     (let [result (core/recv! receiver 0)]
                       (is (=  msg result)))))

                 (testing "Transmit keyword"
                   (let [msg :something]
                     (core/send! receiver msg 0)
                     (let [result (core/recv! sender 0)]
                       (is (= msg result)))))))))))

(deftest check-unbinding
  (core/with-context [ctx 1]
    (core/with-socket! [nothing ctx :rep]
      (let [addr "tcp://127.0.0.1:5678"]
        (core/bind! nothing addr)
        (println "Bound" nothing "to" addr)
        (is true)
        (core/unbind! nothing addr)
        (println "Unbound" nothing "from" addr)
        (is true)))))

(deftest check-unbinding-macro
  (core/with-context [ctx 1]
    (core/with-bound-socket! [nothing ctx :rep "tcp://127.0.0.1:5679"]
      (is true))))
