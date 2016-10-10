(ns cljeromq.core-test
  "This is almost a poster child for the value of generative testing.

TODO: Convert to doing that."
  (:import [org.zeromq
            ZMQ
            ZMQException])
  (:require [cljeromq.common :as common]
            [cljeromq.core :as mq]
            [clojure.test :refer [deftest testing is]]))

(defn setup
  [uri client-type server-type]
  (let [ctx (ZMQ/context 2)]
    (try
      (let [client (.socket ctx client-type)]
        (println "Client socket created. Trying to connect to" uri)
        ;; TODO: Switch to using a random port instead
        (.connect client uri)
        (let [server (.socket ctx server-type)]
          (.bind server uri)
          [ctx client server]))
      (catch java.lang.ClassCastException ex
        (print (str ex) "\ntrying to set up sockets for"
               client-type "(client) and"
               server-type "(server)")
        (throw ex)))))

(defn teardown
  ([{:keys [context client server uri unbind-server?]}]
   (when unbind-server?
     (.unbind server uri))
   (.close server)
   (.disconnect client uri)
   (.close client)
   (try
     (.term context)
     (catch Exception ex
       (println "Teardown failed:" ex))))
  ([ctx client server uri]
   (teardown {:context ctx
              :client client
              :server server
              :uri uri
              :unbind-server? true})))

;; Even though I'm not using JNI at all any more.
;; At least, I think it's gone.
;; (that's in a different branch.
;; The entire point to my big tests branch was to
;; get something that's at least mostly working so I
;; can validate that approach
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
     (try
       (mq/send! req msg 0)
       (catch Exception ex
         (is false (str "Sending failed:" ex))))
     (let [received (mq/recv! rep 0)]
       (is (= msg received)))
     (finally
       (teardown ctx req rep uri)))))

(deftest transmit-string
  (testing "String transmission"
    (req-rep-wrapper "xbcAzy")))

(deftest transmit-keyword
  (testing "Keyword transmission"
    (req-rep-wrapper :message)))

(deftest transmit-sequence
  (testing "Sequence transmission"
    (req-rep-wrapper (list :a 3 "abc"))))

(deftest transmit-integer
  (testing "Integer transmission"
    (req-rep-wrapper 1000)))

(deftest transmit-float
  (testing "Float transmission"
    (req-rep-wrapper Math/PI)))

(deftest transmit-bigdecimal
  (testing "Big Decimal transmission"
    (req-rep-wrapper 1000000M)))

(deftest transmit-bigint
  (testing "Big Int transmission"
    (req-rep-wrapper 2000000N)))

(comment
  ;; Q: What would this look like?
  (deftest transmit-multiple-sequences
    (testing "Transmitting multiple sequences"
      (req-rep-wrapper ))))

(deftest basic-macros
  (testing "Basic message exchange with macros"
         (println "Setting up context")
         (mq/with-context [ctx 1]
           (println "Setting up receiver")
           (mq/with-socket! [receiver ctx :rep]
             (testing "Macro created local"
               (is (= receiver receiver)))
             (println "Receiver: " receiver)

             ;; TODO: Don't hard-code this port number
             (let [url  #:cljeromq.common{:zmq-protocol :tcp
                                          :zmq-address "127.0.0.1"
                                          :port 10102}]
               (println "Binding receiver")
               (mq/bind! receiver url)
               (println "Setting up sender")
               (mq/with-socket! [sender ctx :req]
                 (println "Connecting sender")
                 (mq/connect! sender url)

                 (testing "Connected"
                   (is (= 0 0)))

                 (testing "Transmit string"
                   (let [msg "abcxYz1"]
                     (mq/send! sender msg 0)
                     (let [result (mq/recv! receiver 0)]
                       (is (=  msg result)))))

                 (testing "Transmit keyword"
                   (let [msg :something]
                     (mq/send! receiver msg 0)
                     (let [result (mq/recv! sender 0)]
                       (is (= msg result)))))))))))

(deftest req-rep-inproc-unencrypted-handshake
  (testing "Basic inproc req/rep handshake test"
    (let [uri "inproc://a-test-2"
          ctx (ZMQ/context 1)]
      (println "Checking req/rep unencrypted inproc")
      (try
        (let [req (.socket ctx ZMQ/REQ)]
          (try
            (.bind req uri)
            (try
              (let [rep (.socket ctx ZMQ/REP)]
                (try
                  (.connect rep uri)
                  (try
                    (let [client (future (.send req "HELO")
                                         (String. (.recv req)))]
                      (let [greet (.recv rep)]
                        (is (= "HELO" (String. greet))))
                      (.send rep "kthxbye")
                      (is (= @client "kthxbye")))
                    (finally
                      (.disconnect rep uri)))
                  (finally
                    (.close rep))))
              (finally
                (.unbind req uri)))
            (finally (.close req))))
        (finally
          (.term ctx))))))

(deftest inproc-req-rep-handshake
  "This test looks like it was probably the fore-runner
to req-rep-inproc-unencrypted-handshake above

TODO: Make sure they work the same"
  []
  (let [uri "inproc://a-test-3"
        [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
    (try
      (let [client (future (.send req "HELO")
                           (String. (.recv req)))]
        (let [greet (.recv rep)]
          (is (= (String. greet) "HELO")
              "String transmission failed"))
        (.send rep "kthxbye")
        (is (= @client "kthxbye")))
      (finally
        ;; Can't unbind inproc socket
        ;; This is actually Bug #949 in libzmq.
        ;; It should be fixed in 4.1.0, but backporting to 4.0.x
        ;; has been deemed not worth the effort
        (try
          (.unbind rep uri)
          (catch ZMQException ex
            (is (= ex "No such file or directory"))))
        (teardown {:context ctx
                   :client req
                   :server rep
                   :uri uri
                   :unbind-server? false})))))

(deftest simplest-tcp-test
  (testing "TCP REP/REQ handshake"
    (let [uri "tcp://127.0.0.1:8592"
          ctx (ZMQ/context 1)]
      (println "Basic rep/req unencrypted TCP test")
      (try
        (let [req (.socket ctx ZMQ/REQ)]
          (try
            (.connect req uri)
            (try
              (let [rep (.socket ctx ZMQ/REP)]
                (try
                  (.bind rep uri)
                  (try
                    (let [client (future (.send req "HELO")
                                         (String. (.recv req)))]
                      (let [greet (.recv rep)]
                        (is (= "HELO" (String. greet))))
                      (.send rep "kthxbye")
                      (println "Waiting on unencrypted response")
                      (is (= @client "kthxbye"))
                      (println "Plain TCP OK"))
                    (finally
                      (.unbind rep uri)))
                  (finally
                    (.close rep))))
              (finally
                (.disconnect req uri)))
            (finally (.close req))))
        (finally
          (.term ctx))))))

(deftest tcp-req-req-handshake
  "This looks like a simplified equivalent of simplest-tcp-test
directly above

TODO: Verify it"
  []
  (let [uri "tcp://127.0.0.1:8709"
        [ctx req rep] (setup uri ZMQ/REQ ZMQ/REP)]
    (try
      (let [client (future (.send req "HELO")
                           (String. (.recv req)))]
        (let [greet (.recv rep)]
          (is (= "HELO" (String. greet))))
        (.send rep "kthxbye")
        (is (= @client "kthxbye")))
      (finally
        (teardown ctx req rep uri)))))

(deftest string->bytes->string []
  (let [s "The quick red fox jumped over the lazy brown dog"
        bs (mq/string->bytes s)
        round-tripped (mq/bytes->string bs)]
    (is (= round-tripped s) "Conversion failed")
    (is (= (class bs) common/byte-array-type))))

(deftest url-basics []
  (let [url #:cljeromq.common{:zmq-protocol :tcp
                              :zmq-address [0 0 0 0]
                              :port 7681}]
    (is (= "tcp://0.0.0.0:7681"
           (mq/connection-string url)))))

(defn push-unencrypted [ctx msg]
  (comment (println "Plain-text Push Server thread started"))
  (mq/with-socket! [pusher ctx :push]
    (mq/connect! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (comment (println "Push " (inc i)))
      (mq/send! pusher (str msg i) 0))))

(deftest basic-push-pull
       (println "Checking plain-text push/pull interaction")
       (mq/with-context [ctx 2]
         (mq/with-socket! [puller ctx :pull]
           (let [url "tcp://127.0.0.1:2101"]
             (mq/bind! puller url)
             (try
               (let [msg "Unencrypted push"
                     push-thread (future (push-unencrypted ctx msg))]
                 (testing "pulls what was pushed"
                       (comment (println "Checking pulls"))
                       (dotimes [i 10]
                         (is (= (str msg i) (mq/recv! puller)))))
                 (testing "What does msg/send return?"
                       (println "Waiting on push-thread exit")
                       (is (nil? @push-thread))
                       (println "Unencrypted PUSH thread exited")))
               (finally
                 (mq/unbind! puller url)))))))

(deftest minimal-curveless-communication-test
  (testing "Because communication is boring until the principals can swap messages"
    (let [ctx (ZMQ/context 1)]
      (println "Minimal rep/dealer unencrypted over TCP")
      (try
        (let [router (.socket ctx ZMQ/REP)]
          (try
            (let [localhost "tcp://127.0.0.1"
                  port (.bindToRandomPort router localhost)
                  url (str localhost ":" port)]
              (try
                (println "Doing unencrypted comms over '" url "'")
                (let [dealer (.socket ctx ZMQ/DEALER)]
                  (try
                    (.setIdentity dealer (.getBytes (str (gensym))))
                    (.connect dealer url)
                    (try
                      (let [resp (future (println "Dealer: sending greeting")
                                         (is (.sendMore dealer ""))
                                         (is (.send dealer "OLEH"))
                                         (println "Dealer: greeting sent")
                                         (let [separator (String. (.recv dealer))
                                               _ (println "Dealer received separator frame:" separator)
                                               result (String. (.recv dealer))]
                                           (println "Dealer: received " result)
                                           result))]
                        (println "REP: Waiting on message from dealer")
                        (let [greet (.recv router)]  ;; TODO: Add a timeout
                          (is (= "OLEH" (String. greet)))
                          (println "REP socket: greeting received")
                          (is (.send router "cool"))
                          (println "Router: Response sent")
                          (is (= @resp "cool"))
                          (println "Unencrypted Dealer<->REP: Handshook")))
                      (finally (.disconnect dealer url)))
                    (finally (.close dealer))))
                (finally (.unbind router url))))
            (finally (.close router))))
        (finally (.term ctx))))))

(deftest test-unencrypted-tcp-router-dealer
  "It's backwards, but seems worth testing
TODO: Replicate this in C or python to see if it really is this fragile"
  (let [ctx (ZMQ/context 1)
        in (.socket ctx ZMQ/DEALER)
        server-url "tcp://*:54398"]
    (try
      (println "Unencrypted router/dealer over TCP")
      ;; Note that this step is vital, even though it seems like a default
      ;; should work.
      ;; TODO: Duplicate this test but bind the router the way it will work
      ;; in real life. See whether this step is required in than direction
      (.setIdentity in (.getBytes "insecure router/dealer over TCP"))
      (.bind in server-url)
      (try
        (let [out (.socket ctx ZMQ/ROUTER)
              client-url "tcp://127.0.0.1:54398"]
          (try
            (.connect out client-url)
            (try
              (dotimes [n 10]
                (let [s-req (str "request" n)
                      req (.getBytes  s-req)
                      rep (.getBytes (str "reply" n))]
                  (.sendMore in "")
                  (let [success (.send in req 0)]
                    (when-not success
                      (throw (ex-info (str "Sending request returned:" success) {}))))
                  (let [id (.recv out 0)
                        delimeter (.recv out 0)
                        response (.recv out 0)
                        s-rsp (String. response)]
                    (when-not (= s-req s-rsp)
                      (throw (ex-info (str "Sent '" s-req "' which consists of " (count req) " bytes\n"
                                           "Received '" s-rsp "' which is " (count response)
                                           " bytes long from " (String. id)))))
                    (.sendMore out (String. id)))
                  (.sendMore out "")
                  (let [success (.send out (String. rep))]
                    (when-not success
                      (throw (ex-info (str "Error sending Reply: " success) {}))))
                  (let [delimeter (.recv in 0)
                        response (.recv in 0)
                        s-response (String. response)]
                    (is (= (String. rep) s-response)))))
              (finally
                (.disconnect out client-url)))
            (finally
              (.close out))))
        (finally
          (println "Unbinding the Dealer socket from" server-url)
          (try
            (.unbind in server-url)
            (println "Dealer socket unbound")
            (catch ZMQException _
              ;; This is throwing an exception.
              ;; Q: Why?
              ;; A: Well...there's an old issue about unbinding inproc and wildcard
              ;; connections. That supposedly never affected 4.1 and has been back-ported
              ;; to 4.0.
              ;; According to my 4.1 built-in tests, it isn't an issue there, either
              ;; Q: Could I possibly still have an obsolete 4.0 version lurking around
              ;; being used?
              (println "Failed to unbind the Dealer socket. Annoying.")))))
      (finally
        (.close in)
        (.term ctx)))))

(deftest test-unencrypted-tcp-dealer-router
  "Just like the router-dealer equivalent above, but w/ dealer as 'client'"
  (let [ctx (ZMQ/context 1)
        server (.socket ctx ZMQ/ROUTER)
        server-url "tcp://*:54398"]
    (try
      ;; Q: Does this step still matter?
      ;; A: No.
      (comment (.setIdentity server (.getBytes "insecure router/dealer over TCP")))
      (.bind server server-url)
      (try
        (let [client (.socket ctx ZMQ/DEALER)
              client-url "tcp://127.0.0.1:54398"]
          (try
            ;; Q: Does this step make any difference?
            ;; A: Absolutely. This actually seems like a Very Bad Thing.
            (.setIdentity client (.getBytes "insecure dealer over TCP"))
            (.connect client client-url)
            (try
              (dotimes [n 10]
                (let [s-req (str "request" n)
                      req (.getBytes  s-req)
                      rep (.getBytes (str "reply" n))]
                  (.sendMore client "")
                  (let [success (.send client req 0)]
                    (when-not success
                      (throw (ex-info (str "Sending request returned:" success) {}))))
                  (let [id (.recv server 0)
                        delimeter (.recv server 0)
                        response (.recv server 0)
                        s-rsp (String. response)]
                    (when-not (= s-req s-rsp)
                      (throw (ex-info (str "Sent '" s-req "' which consists of " (count req) " bytes\n"
                                           "Received '" s-rsp "' which is " (count response)
                                           " bytes long from " (String. id)))))
                    (.sendMore server (String. id)))
                  (.sendMore server "")
                  (let [success (.send server (String. rep))]
                    (when-not success
                      (throw (ex-info (str "Error sending Reply: " success) {}))))
                  (let [delimeter (.recv client 0)
                        response (.recv client 0)
                        s-response (String. response)]
                    (is (= (String. rep) s-response)))))
              (finally
                (.disconnect client client-url)))
            (finally
              (.close client))))
        (finally
          (try
            (.unbind server server-url)
            (catch ZMQException _
              (println "Unbinding Router socket failed. Annoying.")))))
      (finally
        (.close server)
        (.term ctx)))))
