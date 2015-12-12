(ns cljeromq.binding-test
  (:import [org.zeromq.jni ZMQ])
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
                (comment (is (thrown-with-msg? ZMQException #"No such file or directory"
                                               (.unbind req uri))))
                ;; So...what happens now?
                (cljeromq/unbind! req uri)))
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
            (cljeromq/identify! router (.getBytes "SOMETHING"))  ; IDENT...doesn't seem to matter
            (let [dealer (cljeromq/socket! ctx :dealer)]
              (try
                (.set-socket-option! dealer 47 0)
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
    (println "Encrypted req/rep inproc test")
    (curve/prepare-client-socket-for-server! in client-keys (:private server-keys))
    (cljeromq/bind! in "inproc://reqrep")

    (let [out (cljeromq/socket! ctx :rep)]
      (curve/make-socket-a-server! out (:private server-keys))
      (cljeromq/connect! out "inproc://reqrep")

      (dotimes [n 10]
        (let [req (.getBytes (str "request" n))
              rep (.getBytes (str "reply" n))]
          (comment (println n))
          (let [success (cljeromq/send! in req 0)]
            (when-not success
              (println "Sending request returned:" success)
              ;; Q: Is there any point to this approach now?
              ;; If the send! failed, it really should throw an exception
              (is (or false true))))
          (let [response (cljeromq/recv! out 0)]
            (is (= (String. req) (String. response))))

          (let [success (cljeromq/send! out (String. rep))]
            (when-not success
              (println "Error sending Reply: " success)
              ;; Another absolutely meaningless test
              (is (or false true))))
          (let [response (cljeromq/recv! in 0)]
            (is (= (String. rep) (String. response))))))))
  (println "Simple CURVE checked"))

(deftest test-encrypted-push-pull
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :push)]
    (println "Encrypted push/pull inproc test")
    (curve/prepare-client-socket-for-server! in client-keys (:private server-keys))
    (cljeromq/bind! in "inproc://reqrep")

    (let [out (cljeromq/socket! ctx :pull)]
      (curve/make-socket-a-server! out (:private server-keys))
      (cljeromq/connect! out "inproc://reqrep")

      (dotimes [n 10]
        (let [req (.getBytes (str "request" n))
              rep (.getBytes (str "reply" n))]
          (let [success (.send in req 0)]
            (when-not success
              (println "Sending request returned:" success)
              (is false)))
          (let [response (.recv out 0)]
            (is (= (String. req) (String. response)))))))))

(deftest test-encrypted-router-dealer
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (cljeromq/context 1)
        in (cljeromq/socket! ctx :dealer)
        id-string "basic router/dealer encryption check"
        id-byte-array (byte-array (map (comp byte int) id-string))
        id-bytes (bytes id-byte-array)]
    (println "Encrypted router-dealer inproc test")
    (curve/prepare-client-socket-for-server! in client-keys (:private server-keys))
    (cljeromq/identify! in id-byte-array)
    (cljeromq/bind! in "inproc://reqrep")

    (let [out (cljeromq/socket! ctx :router)]
      (curve/make-socket-a-server! out (:private server-keys))
      (cljeromq/connect! out "inproc://reqrep")  ; Much more realistic for this to bind.

      (dotimes [n 10]
        (let [req (.getBytes (str "request" n))
              rep (.getBytes (str "reply" n))]
          (comment (println n))
          (let [_ (cljeromq/send-more! in (byte-array 0))
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
          (is false "Uncomment that form and get it to work")))))
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
      #_(try
        (let [out (cljeromq/socket! ctx :router)]
          (try
            (curve/make-socket-a-server! out (:private server-keys))
            (cljeromq/connect! out "tcp://127.0.0.1:54398")
            (println "Sockets bound/connected. Beginning test")
            (try
              (dotimes [n 10]
                (println (str "Encrypted Router/Dealer over TCP: Test " n))
                (let [req (.getBytes (str "request" n))
                      rep (.getBytes (str "reply" n))]
                  (comment) (println "Encrypted dealer/router over TCP" n)
                  (let [success (cljeromq/send! in req 0)]
                    (when-not success
                      (println "Sending request returned:" success)
                      (is false)))
                  (println "Waiting for encrypted message from dealer to arrive at router over TCP")
                  (comment (let [response (cljeromq/recv! out 0)]
                             (println "Router received REQ")
                             (let [s-req (String. req)
                                   s-res (String. response)]
                               (when-not (= s-req s-res)
                                 (println "Sent '" s-req "' which consists of " (count req) " bytes\n"
                                          "Received '" s-res "' which is " (count response) " bytes long")
                                 (is (= s-req s-res)))))

                           (let [success (cljeromq/send! out (String. rep))]
                             (when-not success
                               (println "Error sending Reply: " success)
                               (is false)))
                           (println "Dealer waiting for encrypted ACK from Router over TCP")
                           (let [response (cljeromq/recv! in 0)]
                             (is (= (String. rep) (String. response))))
                           (println "Encrypted Dealer->Router over TCP complete"))
                  (is false "Get the rest of the test passing")))
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
    (println "Unencrypted router/dealer over TCP")
    (cljeromq/bind! in "tcp://*:54398")

    (let [out (cljeromq/socket! ctx :router)]
      (cljeromq/connect! out "tcp://127.0.0.1:54398")

      (dotimes [n 10]
        (let [req (.getBytes (str "request" n))
              rep (.getBytes (str "reply" n))]
          (comment (println n))
          (let [success (cljeromq/send! in req 0)]
            (when-not success
              (println "Sending request returned:" success)
              (is (or false true))))
          (println "Waiting for message from dealer to arrive at router")
          (let [response (cljeromq/recv! out 0)]
            (println "Router received REQ")
            (let [s-req (String. req)
                  s-res (String. response)]
              (when-not (= s-req s-res)
                (println "Sent '" s-req "' which consists of " (count req) " bytes\n"
                         "Received '" s-res "' which is " (count response) " bytes long")
                (is (= s-req s-res)))))

          (let [success (cljeromq/send! out (String. rep))]
            (when-not success
              (println "Error sending Reply: " success)
              (is false)))
          (let [response (cljeromq/recv! in 0)]
            (is (= (String. rep) (String. response)))))))))

(deftest minimal-curveless-communication-test
  (testing "Because communication is boring until the principals can swap messages"
    ;; Both threads block at receiving. Have verified that this definitely works in python
    (let [ctx (cljeromq/context 1)]
      (println "Minimal rep/dealer unencrypted over TCP")
      (try
        (let [router (cljeromq/socket! ctx :reply)]
          (try
            (let [localhost "tcp://127.0.0.1"
                  port (.bindToRandomPort router localhost)
                  url (str localhost ":" port)]
              (try
                (println "Doing unencrypted comms over '" url "'")
                (let [dealer (cljeromq/socket! ctx :dealer)]
                  (try
                    (cljeromq/connect! dealer url)
                    (try
                      (let [resp (future (println "Dealer: sending greeting")
                                         (is (cljeromq/send-more! dealer ""))
                                         (is (cljeromq/send! dealer "OLEH"))
                                         (println "Dealer: greeting sent")
                                         (let [identity (String. (cljeromq/recv! dealer))
                                               result (String. (cljeromq/recv! dealer))]
                                           (println "Dealer: received " result
                                                    " from '" identity "'")
                                           result))]
                        (println "Router: Waiting on encrypted message from dealer")
                        (let [greet (cljeromq/recv! router)]  ;; TODO: Add a timeout
                          (is (= "OLEH" (String. greet)))
                          (println "Router: Encrypted greeting decrypted")
                          (is (cljeromq/send! router "cool"))
                          (println "Router: Response sent")
                          (is (= @resp "cool"))
                          (println "Unencrypted Dealer<->REP: Handshook")))
                      (finally (cljeromq/disconnect! dealer url)))
                    (finally (cljeromq/close! dealer))))
                (finally (cljeromq/unbind! router url))))
            (finally (cljeromq/close! router))))
        (finally (cljeromq/terminate! ctx))))))

(comment
  ;;; This just became a lot more obsolete
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

(comment (deftest hardcoded-curve-communication-test
           (testing "Try using keys that I just generated by hand from the python binding"
             (let [server-keys (ZCurveKeyPair/Factory)
                   server-public (.getBytes "hoaxyE2KWMErRBAUy<@1@hp%=.ykI&aXLSScm2@N")
                   server-secret (.getBytes "t9]p?z@&md(@Guv28PfpI6-<CA+#-#VT!x(E(OgL")
                   client-keys (ZCurveKeyPair/Factory)
                   client-public (.getBytes "/sV8iUDqeYxYPdp-fsp<UUpMEPOEG]q}N1zL@na!")
                   client-secret (.getBytes "zEL]A*h4:Cmoo}.>?mT@*)I5mt:(t{^$^.Sfz83s")]
               (println "Trying to connect from '" (String. client-public) "' / '" (String. client-secret) "'\n(that's "
                        (count client-public) " and " (count client-secret) " bytes) to\n'"
                        (String. server-public) "' / '" (String. server-secret) "'\nwith " (count server-public)
                        " and " (count server-secret) " bytes")
               (let [ctx (ZMQ/context 1)]
                 (try
                   (let [zap-handler (.socket ctx ZMQ/REP)]
                     (try
                       ;; See if jzmq is using ZAuth behind my back in some sort of
                       ;; sneaky way.
                       ;; I honestly don't believe this is the case.
                       (comment (.bind zap-handler "inproc://zeromq.zap.01"))
                       (try
                         (let [zap-future (future
                                            ;; TODO: Honestly, this should really be using something
                                            ;; like ZAuth's ZAPRequest and ZAuthAgent inner classes.
                                            ;; Assuming I need it at all.
                                            ;; (Imperical evidence implies that I do. Docs imply
                                            ;; that I shouldn't.
                                            (throw (RuntimeException. "Write this")))]
                           (let [router (.socket ctx ZMQ/DEALER)]
                             (try
                               ;; python unit tests treat this as read-only
                               (.setLongSockopt router 47 1)   ; server?
                               ;; Most unit tests I see online set this.
                               ;; The official suite doesn't.
                                        ;(.setBytesSockopt router 50 server-public) ; curve-server-key
                               ;; Definitely don't need client keys
                                        ;(.setBytesSockopt router 48 client-public) ; curve-public-key
                                        ;(.setBytesSockopt router 49 client-secret) ; curve-secret-key
                               (.setBytesSockopt router 49 server-secret)
                               (.setIdentity router (.getBytes "SOMETHING"))  ; IDENT...doesn't seem to matter
                               (let [dealer (.socket ctx ZMQ/DEALER)]
                                 (try
                                   (.setLongSockopt dealer 47 0)
                                        ;(.setBytesSockopt dealer 49 server-secret)
                                   ;; Q: Do I actually need to set this?
                                   (.setBytesSockopt dealer 50 server-public) ; curve-server-key
                                   (.setBytesSockopt dealer 48 client-public) ; curve-public-key
                                   (.setBytesSockopt dealer 49 client-secret) ; curve-secret-key
                                   ;; Note that just getting this far is a fairly significant
                                   ;; victory

                                   (let [localhost "tcp://127.0.0.1"
                                         port (.bindToRandomPort router localhost)
                                         url (str localhost ":" port)]
                                     (try
                                       (.connect dealer url)
                                       (try
                                         (let [resp (future (println "Hard Dealer: sending greeting")
                                                            (.send dealer "OLEH")
                                                            (println "Hard Dealer: greeting sent")
                                                            (let [result (String. (.recv dealer))]
                                                              (println "Hard Dealer: received " result)
                                                              result))]
                                           (println "Hard Router: Waiting on encrypted message from dealer")
                                           (let [greet (.recv router)]  ;; TODO: Add a timeout
                                             (is (= "OLEH" (String. greet)))
                                             (println "Hard Router: Encrypted greeting decrypted")
                                             (.send router "cool")
                                             (println "Hard Router: Response sent")
                                             (is (= @resp "cool"))
                                             (println "Hard Router: Handshook")))
                                         (finally
                                           (.disconnect dealer url)))
                                       (finally
                                         (.unbind router url))))
                                   (finally (.close dealer))))
                               (finally (.close router))))
                           (when-not (realized? zap-future)
                             (future-cancel zap-future)))
                         (finally
                           ;; Don't try to unbind inproc sockets
                           ))
                       (finally (.close zap-handler))))
                   (finally (.term ctx))))))))
