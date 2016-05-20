(ns cljeromq.binding-test
  (:import [clojure.lang ExceptionInfo]
           [org.zeromq.jni ZMQ])
  (:require [clojure.pprint :refer (pprint)]
            [clojure.test :refer :all]
            [cljeromq.core :as cljeromq]
            [cljeromq.curve :as curve]))

(deftest req-rep-inproc-unencrypted-handshake
  (println "REQ/REP inproc handshake")
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
                  (catch ExceptionInfo _
                    (is true "This matches current, albeit incorrect, behavior")))))
            (finally (cljeromq/close! req))))
        (finally
          (cljeromq/terminate! ctx))))))

(deftest simplest-tcp-test
  (testing "TCP REP/REQ handshake"
    (println "REP/REQ handshake over TCP")
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
    (println "Low-level CURVE creation")
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
            ;; But czmq definitely sets this.
            ;; Is that an implementation detail?
            ;; Which way should this work?
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

(defn send-in-future
  [src msg]
  (future
    ;; Note that this should very definitely block
    (let [success (cljeromq/send! src msg 0)]
      (when (< success 0)
        (println "Sending request returned:" success)
        ;; Q: Is there any point to this approach now?
        (is false "Should have thrown an exception on failure"))
      success)))

(deftest test-not-really-encrypted-req-rep
  "TODO: Restore encryption"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        client (cljeromq/socket! ctx :req)]
    (try
      (println "[not] Encrypted req/rep inproc test")
      (comment (curve/prepare-client-socket-for-server! client client-keys (:public server-keys)))

      (try
        (let [server (cljeromq/socket! ctx :rep)]
          (try
            (comment (curve/make-socket-a-server! server server-keys))
            ;; Should be able to bind/connect in either direction/order...right?
            ;; TODO: Switch back to binding the client first and then connecting the server
            (cljeromq/bind! server "tcp://*:6001")
            (cljeromq/connect! client "tcp://localhost:6001")

            (try
              (dotimes [n 10]
                (let [req (.getBytes (str "request" n))
                      rep (.getBytes (str "reply" n))]
                  (comment (println n))
                  ;; TODO: Move this call into a future and have it block.
                  ;; Or something along those lines
                  (let [send-future (send-in-future client req)]
                    (try
                      ;; This should block until we receive something...right?
                      ;; (A: Yes. DONTWAIT == 1)
                      ;; Note that we just successfully sent the initial req directly above.
                      ;; Q: Are we really?
                      ;; Note that this call should now be throwing a RuntimeException
                      ;; when it fails.
                      (let [response (cljeromq/recv! server 0)]
                        (is (= (String. req) (String. response))))
                      ;; TODO: Verify that send-future has been realized
                      (is (>= @send-future 0))
                      (try
                        (let [send-future (send-in-future server (String. rep))]
                          (try
                            (let [response (cljeromq/recv! client 0)]
                              (is (= (String. rep) (String. response))))
                            (catch ExceptionInfo ex
                              (is (not (.getData ex)))))
                          ;; TODO: Verify that send-future has been realized
                          (is (>= @send-future 0))))
                      (catch ExceptionInfo ex
                        ;; Lots of things could go wrong there.
                        ;; This should not be one of them.
                        ;; However, it is.
                        ;; Worse, the loop keeps trying to run after the test has failed.
                        ;; Although that probably makes sense since the failure probably
                        ;; isn't raising an exception
                        (is false (.getData ex)))))))
              (finally (cljeromq/unbind! server "tcp://*:6001")))
            (finally (cljeromq/close! server))))
        (finally
          (cljeromq/disconnect! client "tcp://localhost:6001")))
      (finally
        (cljeromq/close! client)
        (cljeromq/terminate! ctx)))))

(deftest test-encrypted-push-pull
  "Translated directly from my java unit test"
  (println "Testing encrypted push-pull")
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        pull (cljeromq/socket! ctx :pull)
        address "tcp://127.0.0.1:52711"]
    (try
      ;; This doesn't seem to work.
      ;; Q: Why not?
      (cljeromq/set-time-out! pull 200)
      (curve/make-socket-a-server! pull server-keys)
      (cljeromq/bind! pull address)
      (try
        (let [push (cljeromq/socket! ctx :push)]
          (try
            (curve/prepare-client-socket-for-server! push client-keys (:public server-keys))
            (cljeromq/connect! push address)
            (try
              (dotimes [n 10]
                (testing (str "Encrypted push/pull #" n)
                  (let [req (.getBytes (str "request" n))
                        rep (.getBytes (str "reply" n))]
                    (println "Encrypted Push #" n)
                    (is (cljeromq/send! push req 0) (str "Pushing failed: " (cljeromq/last-error)))
                    ;; Just to make sure there's plenty of time for that to get through the buffers
                    (Thread/sleep 15)
                    (let [response (cljeromq/recv! pull [:dont-wait])]
                      (is (= (String. req) (String. response)))))))
              (finally (cljeromq/disconnect! push address)))
            (finally (cljeromq/close! push))))
        (finally (cljeromq/unbind! pull address)))
      (finally
        (cljeromq/close! pull)
        (cljeromq/terminate! ctx)))))

(defn run-router-dealer-test
  "Make sure I'm doing apples-to-apples comparing inproc to tcp
and, probably more important, encrypted vs. plain text
In the previous incarnation, everything except tcp seemed to work"
  [dealer router]
  (dotimes [n 10]
    (let [s-req (str "request" n)
          req (.getBytes s-req)
          rep (.getBytes (str "reply" n))]
      (comment (println "Router/Dealer test exchange " n))
      (let [_ (cljeromq/send-more! dealer nil)
            success (cljeromq/send! dealer req 0)]
        ;; As long as it didn't throw an exception,
        ;; the send should have been a success
        (is success
          (str "Sending request returned:" success)))

      (let [dealer-thread
            (future
              (comment (println "Dealer waiting on response from router"))
              (let [null-separator (cljeromq/recv! dealer 0)
                    response (cljeromq/recv! dealer 0)]
                (is (= (String. rep) (String. response)))
                (comment (println "All's well with the Dealer ACK"))
                true))]

        ;; Router read
        (comment (println "Reading what the dealer sent"))
        (let [address (cljeromq/raw-recv! router 0)   ;  <---- N.B.
              delimeter (cljeromq/recv! router 0)
              from-dealer (cljeromq/recv! router 0)
              s-rsp (String. from-dealer)]
          (is (= 0 (count delimeter)))
          (is (= s-req s-rsp)
              (str "Sent '" s-req "' which is " (count req) " bytes long.\n"
                   "Received '" s-rsp "' which is " (count from-dealer)
                   " from " (String. address)))

          (cljeromq/send-more! router address))
        (let [_ (cljeromq/send-more! router nil)
              success (cljeromq/send! router rep 0)]
          (is success
              (str "Error sending Reply: " success)))
        (is @dealer-thread "Failed at the end")))))

(deftest test-not-really-encrypted-router-dealer
  "No encryption applied. This is pretty much useless except as a baseline"
  (println "Encrypted (not really) router-dealer inproc test")
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        _ (println "Keys generated")
        ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :dealer)
        id-string "basic router/dealer encryption check"]
    (println "Basic environment set up")
    (try
      ;; Note that, at least in 4.0, CURVE is very specifically only applied to TCP sockets
      (curve/prepare-client-socket-for-server! in client-keys (byte-array (range 40)))
      (cljeromq/identify! in id-string)
      (cljeromq/bind! in "inproc://encrypted-router<->dealer")
      (try
        (let [out (cljeromq/socket! ctx :router)]
          (try
            (curve/make-socket-a-server! out server-keys)
            (cljeromq/connect! out "inproc://encrypted-router<->dealer")  ; Much more realistic for this to bind.
            (try
              (run-router-dealer-test in out)
              (finally (cljeromq/disconnect! out "inproc://encrypted-router<->dealer")))
            (finally (cljeromq/close! out))))
        (finally
          (cljeromq/unbind! in "inproc://encrypted-router<->dealer")))
      (finally
        (cljeromq/close! in)
        (cljeromq/terminate! ctx)))))

(deftest test-encrypted-router-dealer-over-tcp
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 2)
        address "tcp://127.0.0.1:54398"
        dealer (cljeromq/socket! ctx :dealer)]
    (try
      (println "Encrypted router-dealer TCP test")
       (curve/prepare-client-socket-for-server! dealer client-keys (:public server-keys))
       ;; Important note:
      (println "Binding the dealer")
      (cljeromq/bind! dealer address)
      (try
        (let [router (cljeromq/socket! ctx :router)]
          (try
            (cljeromq/set-router-mandatory! router)
            (println "Making the router a CURVE server")
            (curve/make-socket-a-server! router server-keys)
            (cljeromq/connect! router address)
            (println "Sockets bound/connected. Beginning test")
            (try
              (run-router-dealer-test dealer router)
              (finally
                (cljeromq/disconnect! router address)))
            (finally
              (cljeromq/close! router))))
        (finally (cljeromq/unbind! dealer address)))
      (finally
        (cljeromq/close! dealer)
        (cljeromq/terminate! ctx)))))

(deftest test-unencrypted-router-dealer-over-tcp
  "Translated directly from the jzmq unit test"
  (let [ctx (cljeromq/context 1)
        dealer (cljeromq/socket! ctx :dealer)]
    (try
      (println "\nUnencrypted router/dealer over TCP")
      (cljeromq/bind! dealer "tcp://*:54398")

      (try
        (let [router (cljeromq/socket! ctx :router)]
          (is router "Router creation failed")
          (cljeromq/set-router-mandatory! router)
          (cljeromq/connect! router "tcp://127.0.0.1:54398")
          (try
            (run-router-dealer-test dealer router)
            (finally
              (cljeromq/disconnect! router "tcp://127.0.0.1:54398")
              (cljeromq/close! router))))
        (finally
          (try
            ;; This is still failing
            ;; Q: Why?
            (cljeromq/unbind! dealer "tcp://*:54398")
            (catch ExceptionInfo ex
              (let [details (.getData ex)]
                (println "Unbinding the unencrypted dealer failed:\n")
                (pprint details)
                (throw ex))))))
      (finally
        (cljeromq/close! dealer)
        (cljeromq/terminate! ctx)))))

(deftest minimal-curveless-communication-test
  (testing "Because communication is boring until the principals can swap messages"
    ;; Both threads block at receiving. Have verified that this definitely works in python
    (println "Minimal unencrypted router/dealer test")
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

;;; TODO: Get this working
(comment
  (deftest check-zap
    (testing "Principals should have a clue who's whom"
      (println "Checking ZAP")
      (let [server-keys (curve/new-key-pair)
            server-public (:public server-keys)
            server-private (:private server-keys)
            client-keys (curve/new-key-pair)
            client-public (:public client-keys)
            client-private (:private client-keys)
            ctx (cljeromq/context 1)]
        (try
          (let [zap-handler (cljeromq/socket! ctx :rep)]
            (try
              (cljeromq/set-time-out! zap-handler 5000)
              (cljeromq/bind! zap-handler "inproc://zeromq.zap.01")
              (try
                (let [zap-future
                      (future
                        (try
                          (loop [version (cljeromq/recv! zap-handler)]  ; TODO: Really needs a timeout
                            (println "ZAP validation request received")
                            (let [sequence (cljeromq/recv! zap-handler)
                                  domain (cljeromq/recv! zap-handler)
                                  address (cljeromq/recv! zap-handler)
                                  identity (cljeromq/recv! zap-handler)
                                  mechanism (cljeromq/recv! zap-handler)]
                              (println "ZAP:\n" (String. version) "\n"
                                       (String. sequence) "\n"
                                       (String. domain) "\n"
                                       (String. address) "\n"
                                       (String. identity) "\n"
                                       (String. mechanism "\n"))
                              ;; Need to respond with the sequence, b"200 and b"OK"
                              ;; (based on the python handler)
                              (cljeromq/send-more! zap-handler "1.0")
                              (cljeromq/send-more! zap-handler sequence)
                              (cljeromq/send-more! zap-handler "200")
                              (cljeromq/send-more! zap-handler "OK")
                              (cljeromq/send-more! zap-handler "user id")
                              (cljeromq/send! zap-handler "metadata isn't used"))
                            ;; Anything real should loop until we get a signal to stop
                            (when false
                              (recur (.recv zap-handler))))
                          (catch ExceptionInfo ex
                            (let [details (.getData ex)]
                              (println (keys details))
                              (is false "What does EAGAIN look like here?")))))]
                  (let [server (cljeromq/socket! ctx :rep)]
                    (try
                      (curve/make-socket-a-server! server server-keys)
                      (let [localhost "tcp://127.0.0.1"
                            port (cljeromq/bind-random-port! server localhost)
                            url (str localhost ":" port)
                            client (cljeromq/socket! ctx :dealer)]
                        (try
                          (curve/prepare-client-socket-for-server! client client-keys server-public)
                          (cljeromq/connect! client url)
                          (try
                            (let [server-thread
                                  (future
                                    (println "Server: Waiting on encrypted message from dealer")
                                    (let [greet (cljeromq/recv! server)]
                                      (is (= "OLEH" (String. greet)))
                                      (println "Server: Encrypted greeting decrypted")
                                      (is (cljeromq/send! server "cool"))
                                      (println "Server: Response sent"))
                                    true)]
                              (println "Client: sending greeting")
                              (cljeromq/send-more! client nil)
                              (cljeromq/send! client "OLEH")
                              (println "Client: Greeting Sent")
                              (cljeromq/recv! client)
                              (is (cljeromq/has-more? client) "Should have had message payload after NULL separator")
                              (let [response (String. (cljeromq/recv! client))]
                                (is (= "cool" response)))
                              (is @server-thread "That did exit, didn't it?"))
                            (finally (cljeromq/disconnect! client url)))
                          (finally (cljeromq/close! client))))
                      (finally (cljeromq/close! server)))))
                (finally
                  ;; TODO: Make this happen
                  (comment (cljeromq/unbind! zap-handler "inproc://zeromq.zap.01"))))
              (finally (cljeromq/close! zap-handler))))
          (finally (cljeromq/terminate! ctx)))))))
