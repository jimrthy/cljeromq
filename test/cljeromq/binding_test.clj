(ns cljeromq.binding-test
  (:import [clojure.lang ExceptionInfo]
           [org.zeromq.jni ZMQ])
  (:require [clojure.test :refer :all]
            [cljeromq.core :as cljeromq]
            [cljeromq.curve :as curve]))

(deftest req-rep-inproc-unencrypted-handshake
  (testing "Basic inproc req/rep handshake test"
    (let [uri "inproc://a-test-1"
          ctx (cljeromq/context 1)]
      (println "Checking req/rep unencrypted inproc")
      (try
        (let [req (cljeromq/socket! ctx :req)]
          (try
            (cljeromq/bind! req uri)
            (try
              (let [rep (cljeromq/socket! ctx :rep)]
                (try
                  (cljeromq/connect! rep uri)
                  (try
                    (let [client (future (cljeromq/send! req "HELO")
                                         (String. (cljeromq/recv! req)))]
                      (let [greet (cljeromq/recv! rep)]
                        (is (= "HELO" (String. greet))))
                      (cljeromq/send! rep "kthxbye")
                      (is (= @client "kthxbye")))
                    (finally
                      (cljeromq/disconnect! rep uri)))
                  (finally
                    (cljeromq/close! rep))))
              (finally
                ;; Can't unbind inproc socket
                ;; This is actually Bug #949 in libzmq.
                ;; It should be fixed in 4.1.0, but backporting to 4.0.x
                ;; has been deemed not worth the effort
                (try
                  (cljeromq/unbind! req uri)
                  (println "The unbind inproc socket bug's been fixed")
                  (catch ExceptionInfo _
                    (is true "This matches current, albeit incorrect, behavior")))))
            (finally (cljeromq/close! req))))
        (finally
          (cljeromq/terminate! ctx))))))

(deftest simplest-tcp-test
  (testing "TCP REP/REQ handshake"
    (let [uri "tcp://127.0.0.1:8592"
          ctx (cljeromq/context 1)]
      (println "Basic rep/req unencrypted TCP test")
      (try
        (let [req (cljeromq/socket! ctx :req)]
          (try
            (cljeromq/connect! req uri)
            (try
              (let [rep (cljeromq/socket! ctx :rep)]
                (try
                  (cljeromq/bind! rep uri)
                  (try
                    (let [client (future (cljeromq/send! req "HELO")
                                         (String. (cljeromq/recv! req)))]
                      (let [greet (cljeromq/recv! rep)]
                        (is (= "HELO" (String. greet))))
                      (cljeromq/send! rep "kthxbye")
                      (println "Waiting on unencrypted response")
                      (is (= @client "kthxbye"))
                      (println "Plain TCP OK"))
                    (finally
                      (cljeromq/unbind! rep uri)))
                  (finally
                    (cljeromq/close! rep))))
              (finally
                (cljeromq/disconnect! req uri)))
            (finally (cljeromq/close! req))))
        (finally
          (cljeromq/terminate! ctx))))))

(deftest create-curve-sockets-test
  (testing "Slap together basic low level server socket options. Mostly to see how it works under the covers"
    (let [server-keys (curve/new-key-pair)
          server-public (:public server-keys)
          server-secret (:private server-keys)
          client-keys (curve/new-key-pair)
          client-public (:public client-keys)
          client-secret (:private client-keys)
          ctx (cljeromq/context 1)]
      (println "Encrypted Router-Dealer test")
      (try
        (let [router (cljeromq/socket! ctx :router)]
          (try
            ;; python unit tests treat this as read-only
            (cljeromq/set-socket-option! router 47 1)   ; server?
            ;; Most unit tests I see online set this.
            ;; The official suite doesn't.
            ;(.setBytesSockopt router 50 server-public) ; curve-server-key
            ;; Definitely don't need client keys
            ;(.setBytesSockopt router 48 client-public) ; curve-public-key
            ;(.setBytesSockopt router 49 client-secret) ; curve-secret-key
            (cljeromq/set-socket-option! router 49 server-secret)
            (cljeromq/identify! router "SOMETHING")  ; IDENT...doesn't seem to matter
            (let [dealer (cljeromq/socket! ctx :dealer)]
              (try
                (cljeromq/set-socket-option! dealer 47 0)
                ;(.set-socket-option! dealer 49 server-secret)
                ;; Q: Do I actually need to set this?
                (cljeromq/set-socket-option! dealer 50 server-public) ; curve-server-key
                (cljeromq/set-socket-option! dealer 48 client-public) ; curve-public-key
                (cljeromq/set-socket-option! dealer 49 client-secret) ; curve-secret-key
                ;; Note that just getting this far is a fairly significant
                ;; victory
                (finally (cljeromq/close! dealer))))
            (finally (cljeromq/close! router))))
        (finally (cljeromq/terminate! ctx))))))

(deftest test-encrypted-req-rep
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :req)]
    (try
      (println "Encrypted req/rep inproc test")
      (curve/prepare-client-socket-for-server! in client-keys (:public server-keys))
      (cljeromq/bind! in "inproc://reqrep")

      (try
        (let [out (cljeromq/socket! ctx :rep)]
          (try
            (curve/make-socket-a-server! out (:private server-keys))
            (cljeromq/connect! out "inproc://reqrep")

            (try
              (dotimes [n 10]
                (let [req (.getBytes (str "request" n))
                      rep (.getBytes (str "reply" n))]
                  (comment (println n))
                  (let [success (cljeromq/send! in req 0)]
                    (when-not success
                      (println "Sending request returned:" success)
                      ;; Q: Is there any point to this approach now?
                      (is false "Should have thrown an exception on failure")))
                  (let [response (cljeromq/recv! out 0)]
                    (is (= (String. req) (String. response))))

                  (let [success (cljeromq/send! out (String. rep))]
                    (when-not success
                      (println "Error sending Reply: " success)
                      ;; Another absolutely meaningless test
                      (is (or false true))))
                  (let [response (cljeromq/recv! in 0)]
                    (is (= (String. rep) (String. response))))))
              (finally (cljeromq/disconnect! out "inproc://reqrep")))
            (finally (cljeromq/close! out))))
        (finally
          ;; Don't do this if we're using anything earlier than 4.1.0
          ;; Which I should really quit trying to use very soon
          (comment (cljeromq/unbind! in "inproc://reqrep"))))
      (finally
        (cljeromq/close! in)
        (cljeromq/terminate! ctx)))))

(deftest test-encrypted-push-pull
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :push)]
    (try
      (curve/prepare-client-socket-for-server! in client-keys (:public server-keys))
      ;; TODO: Make sure this gets unbound
      (cljeromq/bind! in "inproc://encrypted-push<->pull")

      (let [out (cljeromq/socket! ctx :pull)]
        (try
          (curve/make-socket-a-server! out (:private server-keys))
          (cljeromq/connect! out "inproc://encrypted-push<->pull")
          (try
            (dotimes [n 10]
              (let [req (.getBytes (str "request" n))
                    rep (.getBytes (str "reply" n))]
                (let [success (cljeromq/send! in req 0)]
                  (when-not success
                    (println "Sending request returned:" success)
                    (is false)))
                (let [response (cljeromq/recv! out 0)]
                  (is (= (String. req) (String. response))))))
            (finally (cljeromq/disconnect! out "inproc://encrypted-push<->pull")))
          (finally (cljeromq/close! out))))
      (finally
        (cljeromq/close! in)
        (cljeromq/terminate! ctx)))))

(defn run-router-dealer-test
  "Make sure I'm doing apples-to-apples comparing inproc to tcp
and, probably more important, encrypted vs. plain text
In the previous incarnation, everything except tcp seemed to work"
  [in out]
  (dotimes [n 10]
    (let [req (.getBytes (str "request" n))
          rep (.getBytes (str "reply" n))]
      (comment (println n))
      (let [_ (cljeromq/send-more! in nil)
            success (cljeromq/send! in req 0)]
        ;; Actually, as long as it didn't throw an exception,
        ;; the send should have been a success
        (when-not success
          (println "Sending request returned:" success)
          (is false)))

      (let [id (cljeromq/recv! out 0)
            delimeter (cljeromq/recv! out 0)
            response (cljeromq/recv! out 0)
            s-req (String. req)
            s-rsp (String. response)]
        (when-not (= s-req s-rsp)
          (println "Sent '" s-req "' which is " (count req) " bytes long.\n"
                   "Received '" s-rsp "' which is " (count response)
                   " from " (String. id)))
        (is (= s-req s-rsp))

        (comment) (cljeromq/send-more! out (String. id)))
      (let [_ (cljeromq/send-more! out "")
            success (cljeromq/send! out (String. rep))]
        (when-not success
          (println "Error sending Reply: " success)
          (is false)))
      (println "Inproc dealer waiting on encrypted response from router")
      (comment (let [_ (cljeromq/recv! in 0)
                     response (cljeromq/recv! in 0)]
                 (is (= (String. rep) (String. response)))))
      (is false "Uncomment that form and get it to work"))))

(deftest test-encrypted-router-dealer
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :dealer)
        id-string "basic router/dealer encryption check"]
    (println "Encrypted router-dealer inproc test")
    (try
      (curve/prepare-client-socket-for-server! in client-keys (:private server-keys))
      (cljeromq/identify! in id-string)
      (cljeromq/bind! in "inproc://encrypted-router<->dealer")
      (try
        (let [out (cljeromq/socket! ctx :router)]
          (try
            (curve/make-socket-a-server! out (:private server-keys))
            (cljeromq/connect! out "inproc://encrypted-router<->dealer")  ; Much more realistic for this to bind.
            (try
              (run-router-dealer-test in out)
              (finally (cljeromq/disconnect! out "inproc://encrypted-router<->dealer")))
            (finally (cljeromq/close! out))))
        (finally
          ;; TODO: Really do this after upgrading to 0mq 4.1
          (comment (cljeromq/unbind! in "inproc://encrypted-router<->dealer"))))
      (finally
        (cljeromq/close! in)
        (cljeromq/terminate! ctx))))
  (println "Dealer<->Router CURVE not checked"))

(deftest test-encrypted-router-dealer-over-tcp
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 2)
        in (cljeromq/socket! ctx :dealer)]
    (try
      (println "(not) Encrypted router-dealer TCP test")
      (curve/prepare-client-socket-for-server! in client-keys (:private server-keys))
      (cljeromq/bind! in "tcp://*:54398")
      (try
        (let [out (cljeromq/socket! ctx :router)]
          (try
            (curve/make-socket-a-server! out (:private server-keys))
            (cljeromq/connect! out "tcp://127.0.0.1:54398")
            (println "Sockets bound/connected. Beginning test")
            (try
              (run-router-dealer-test in out)
              (finally
                (cljeromq/disconnect! out "tcp://127.0.0.1:54398")))
            (finally
              (cljeromq/close! out))))
        (finally (cljeromq/unbind! in "tcp://*:54398")))
      (finally
        (cljeromq/close! in)
        (cljeromq/terminate! ctx)))))

(deftest test-unencrypted-router-dealer-over-tcp
  "Translated directly from the jzmq unit test"
  (let [ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :dealer)]
    (try
      (println "Unencrypted router/dealer over TCP")
      (cljeromq/bind! in "tcp://*:54398")

      (try
        (let [out (cljeromq/socket! ctx :router)]
          (is out "Router creation failed")
          (cljeromq/set-router-mandatory! out)
          (cljeromq/connect! out "tcp://127.0.0.1:54398")
          (try
            (run-router-dealer-test in out)
            (finally
              (cljeromq/disconnect! out "tcp://127.0.0.1:54398")
              (cljeromq/close! out))))
        (finally
          (cljeromq/unbind! in "tcp://*:54398")))
      (finally
        (cljeromq/close! in)
        (cljeromq/terminate! ctx)))))

(deftest minimal-curveless-communication-test
  (testing "Because communication is boring until the principals can swap messages"
    ;; Both threads block at receiving. Have verified that this definitely works in python
    (let [ctx (cljeromq/context 1)]
      (try
        (let [router (cljeromq/socket! ctx :rep)]
          (try
            (let [localhost "tcp://127.0.0.1"
                  port (cljeromq/bind-random-port! router localhost)
                  url (str localhost ":" port)]
              (try
                (let [dealer (cljeromq/socket! ctx :dealer)]
                  (try
                    (cljeromq/connect! dealer url)
                    (try
                      (let [resp (future (is (cljeromq/send-more! dealer ""))
                                         (is (cljeromq/send! dealer "OLEH"))
                                         (println "Dealer: greeting sent")
                                         (let [null-separator (String. (cljeromq/recv! dealer))]
                                           ;; Then the payload we care about
                                           (String. (cljeromq/recv! dealer))))]
                        (let [greet (cljeromq/recv! router)]  ;; TODO: Add a timeout
                          (is (= "OLEH" (String. greet)))
                          (is (cljeromq/send! router "cool"))
                          (is (= @resp "cool"))))
                      (finally (cljeromq/disconnect! dealer url)))
                    (finally (cljeromq/close! dealer))))
                (finally (cljeromq/unbind! router url))))
            (finally (cljeromq/close! router))))
        (finally (cljeromq/terminate! ctx))))))

(comment
  ;;; This just became a lot more obsolete
  ;;; It was really me sorting out the way I think things should work,
  ;;; based on the low-level implementation details.
  ;;; I'm tempted to just scrap it, but I don't have another example of
  ;;; using ZAuth anywhere, and that piece is vital
  (deftest minimal-curve-communication-test
    (testing "Because communication is boring until the principals can swap messages"
      ;; Both threads block at receiving. Have verified that this definitely works in python
      (let [server-keys (ZCurveKeyPair/Factory)
            server-public (.publicKey server-keys)
            server-secret (.privateKey server-keys)
            client-keys (ZCurveKeyPair/Factory)
            client-public (.publicKey client-keys)
            client-secret (.privateKey client-keys)]
        (println "Trying to connect from " (String. client-public) "/" (String. client-secret) "\n(that's "
                 (count client-public) " and " (count client-secret) " bytes) to\n"
                 (String. server-public) "/" (String. server-secret) "\nwith " (count server-public)
                 " and " (count server-secret) " bytes")
        (let [ctx (ZMQ/context 1)]
          (try
            (let [zap-handler (.socket ctx ZMQ/REP)]
              (try
                ;; See if jzmq is using ZAuth behind my back in some sort of
                ;; sneaky way.
                ;; I honestly don't believe this is the case.
                ;; And adding this doesn't seem to make any difference
                (.bind zap-handler "inproc://zeromq.zap.01")
                (try
                  (let [zap-future (future
                                     ;; TODO: Honestly, this should really be using something
                                     ;; like ZAuth's ZAPRequest and ZAuthAgent inner classes.
                                     ;; Assuming I need it at all.
                                     ;; (Imperical evidence implies that I do. Docs imply
                                     ;; that I shouldn't.
                                     (loop [version (.recv zap-handler)]
                                       (println "ZAP validation request received")
                                       (throw (RuntimeException. "Got here"))
                                       (let [sequence (.recv zap-handler)
                                             domain (.recv zap-handler)
                                             address (.recv zap-handler)
                                             identity (.recv zap-handler)
                                             mechanism (.recv zap-handler)]
                                         (println "ZAP:\n" (String. version) "\n"
                                                  (String. sequence) "\n"
                                                  (String. domain) "\n"
                                                  (String. address) "\n"
                                                  (String. identity) "\n"
                                                  (String. mechanism "\n"))
                                         ;; Need to respond with the sequence, b"200 and b"OK"
                                         ;; (based on the python handler)
                                         (.sendMore zap-handler "1.0")
                                         (.sendMore zap-handler sequence)
                                         (.sendMore zap-handler "200")
                                         (.sendMore zap-handler "OK")
                                         (.sendMore zap-handler "user id")
                                         (.send zap-handler "metadata isn't used"))
                                       (recur (.recv zap-handler))))]
                    (let [server (.socket ctx ZMQ/REP)]
                      (try
                        (.makeIntoCurveServer server server-secret)
                        ;; python unit tests treat this as read-only

                        (let [localhost "tcp://127.0.0.1"
                              port (.bindToRandomPort server localhost)
                              url (str localhost ":" port)]
                          (try
                            (println "Doing comms over '" url "'")
                            (let [client (.socket ctx ZMQ/DEALER)]
                              (try
                                (.makeIntoCurveClient client client-keys server-public)
                                (try
                                  (.connect client url)
                                  (try
                                    (let [server-thread
                                          (future
                                            (println "Server: Waiting on encrypted message from dealer")
                                            (let [greet (.recv server)]  ;; TODO: Add a timeout so we don't block everything
                                              (is (= "OLEH" (String. greet)))
                                              (println "Server: Encrypted greeting decrypted")
                                              (is (.send server "cool"))
                                              (println "server: Response sent")))]
                                      (try

                                        (println "Client: sending greeting")
                                        (is (.sendMore client ""))
                                        (is (.send client "OLEH"))
                                        (println "Client: greeting sent")
                                        (let [identity (String. (.recv client))]
                                          (is (.hasReceiveMore client))
                                          (let [result (String. (.recv client))]
                                            (println "Client: received " result
                                                     " from " identity)
                                            (is (= result "cool"))))
                                        (finally
                                          (when-not (realized? server-thread)
                                            (future-cancel server-thread)))))
                                    (finally (.disconnect client url)))
                                  (finally (.close client)))
                                (finally (.unbind server url))))))
                        (finally (.close server))))
                    (when-not (realized? zap-future)
                      (future-cancel zap-future)))
                  (finally
                    ;; Don't try to unbind inproc sockets
                    ))
                (finally (.close zap-handler))))
            (finally (.term ctx))))))))
