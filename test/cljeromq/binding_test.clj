(ns cljeromq.binding-test
  "This namespace is poorly named. It's really where
all the interesting parts happen"
  (:import [org.zeromq ZMQ
            ZMQException
            ZMQ$Curve
            ZMQ$Curve$KeyPair])
  (:require [cljeromq.curve :as curve]
            [clojure.test :refer :all]))

(deftest req-rep-inproc-unencrypted-handshake
  (testing "Basic inproc req/rep handshake test"
    (let [uri "inproc://a-test-1"
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
                ;; Note that this should fail on 4.0.x
                ;; It's a bug that was fixed in 4.1.0, but deemed unworthy
                ;; of backporting
                (.unbind req uri)))
            (finally (.close req))))
        (finally
          (.term ctx))))))

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

(deftest create-curve-sockets-test
  (testing "Slap together basic server socket options"
    (let [server-keys (ZMQ$Curve/generateKeyPair)
          server-public (ZMQ$Curve/z85Decode (.publicKey server-keys))
          server-secret (ZMQ$Curve/z85Decode (.secretKey server-keys))
          client-keys (ZMQ$Curve/generateKeyPair)
          client-public (ZMQ$Curve/z85Decode (.publicKey client-keys))
          client-secret (ZMQ$Curve/z85Decode (.secretKey client-keys))
          ctx (ZMQ/context 1)]
      (println "Encrypted Router-Dealer test")
      (try
        (let [router (.socket ctx ZMQ/ROUTER)]
          (try
            ;; python unit tests treat this as read-only
            (.setCurveServer router true)   ; server?
            ;; Most unit tests I see online set this.
            ;; The official suite doesn't.
            ;(.setBytesSockopt router 50 server-public) ; curve-server-key
            ;; Definitely don't need client keys
            ;(.setBytesSockopt router 48 client-public) ; curve-public-key
            ;(.setBytesSockopt router 49 client-secret) ; curve-secret-key
            (.setCurveSecretKey router server-secret)
            (.setIdentity router (.getBytes "SOMETHING"))  ; IDENT...doesn't seem to matter
            (let [dealer (.socket ctx ZMQ/DEALER)]
              (try
                (.setCurveServer dealer false)
                ;(.setBytesSockopt dealer 49 server-secret)
                ;; Q: Do I actually need to set this?
                (.setCurveServerKey dealer server-public) ; curve-server-key
                (.setCurvePublicKey dealer client-public) ; curve-public-key
                (.setCurveSecretKey dealer client-secret) ; curve-secret-key
                ;; Note that just getting this far is a fairly significant
                ;; victory
                (finally (.close dealer))))
            (finally (.close router))))
        (finally (.term ctx))))))

(deftest test-encrypted-req-rep
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 1)
        in (.socket ctx ZMQ/REQ)
        url "inproc://reqrep"]
    (try
      (curve/prepare-client-socket-for-server! in client-keys (:public server-keys))
      (.bind in url)
      (try
        (let [out (.socket ctx ZMQ/REP)]
          (try
            (curve/make-socket-a-server! out (:private server-keys))
            (.connect out url)
            (try
              (dotimes [n 10]
                (let [req (.getBytes (str "request" n))
                      rep (.getBytes (str "reply" n))]
                  (comment (println n))
                  (let [success (.send in req 0)]
                    (when-not success
                      (println "Sending request returned:" success)
                      (is (or false true))))
                  (let [response (.recv out 0)]
                    (is (= (String. req) (String. response))))

                  (let [success (.send out (String. rep))]
                    (when-not success
                      (println "Error sending Reply: " success)
                      (is (or false true))))
                  (let [response (.recv in 0)]
                    (is (= (String. rep) (String. response))))))
              (finally (.disconnect out url)))
            (finally
              (.close out))))
        (finally
          (.unbind in url)))
      (finally
        (.close in)
        (.term ctx)))))

(deftest test-encrypted-push-pull
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 2)
        out (.socket ctx ZMQ/PUSH)
        url "inproc://push-pull"]
    (try
      ;; Note: this really should fail, since the client absolutely should not know
      ;; the server's private key.
      ;; Seems to work because inproc totally ignores encryption
      (curve/prepare-client-socket-for-server! out client-keys (:private server-keys))
      (.bind out url)
      (try
        (let [in (.socket ctx ZMQ/PULL)]
          (try
            (curve/make-socket-a-server! in (:private server-keys))
            (.connect in url)
            (try
              (dotimes [n 10]
                (let [req (.getBytes (str "request" n))]
                  (let [success (.send out req 0)]
                    (when-not success
                      (throw (ex-info "Failed" {}))))
                  (let [response (.recv in 0)]
                    (is (= (String. req) (String. response))))))
              (finally
                (.disconnect in url)))
            (finally (.close in))))
        (finally
          (.unbind out url)))
      (finally
        (.close out)
        (.term ctx)))))

(deftest test-encrypted-router-dealer
  "Translated directly from my java unit test"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 2)
        in (.socket ctx ZMQ/DEALER)
        url "inproc://router-dealer"]
    (try
      (curve/prepare-client-socket-for-server! in client-keys (:public server-keys))
      (.setIdentity in (.getBytes "basic router/dealer encryption check"))
      (.bind in url)
      (try
        (let [out (.socket ctx ZMQ/ROUTER)]
          (try
            (curve/make-socket-a-server! out (:private server-keys))
            (.connect out url)  ; Much more realistic for this to bind.
            (try
              (dotimes [n 10]
                (let [s-req (str "request" n)
                      req (.getBytes s-req)
                      rep (.getBytes (str "reply" n))]
                  (let [_ (.sendMore in "")
                        success (.send in req 0)]
                    (when-not success
                      (throw (ex-info (str "Sending request returned:" success) {}))))

                  (let [id (.recv out 0)
                        delimeter (.recv out 0)
                        response (.recv out 0)
                        s-rsp (String. response)]
                    (when-not (= s-req s-rsp)
                      (println "Sent '" s-req "' which is " (count req) " bytes long.\n"
                               "Received '" s-rsp "' which is " (count response)
                               " from " (String. id)))
                    (is (= s-req s-rsp))

                    (.sendMore out (String. id)))
                  (let [_ (.sendMore out "")
                        success (.send out (String. rep))]
                    (when-not success
                      (throw (ex-info (str "Error sending Reply: " success) {}))))
                  (let [_ (.recv in 0)
                        response (.recv in 0)]
                    (is (= (String. rep) (String. response))))))
              (finally
                (.disconnect out url)))
            (finally
              (.close out))))
        (finally
          (.unbind in url)))
      (finally
        (.close in)
        (.term ctx)))))

(deftest test-unencrypted-router-dealer-over-tcp
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
          ;; This is throwing an exception.
          ;; Q: Why?
          (.unbind in server-url)
          (println "Dealer socket unbound")))
      (finally
        (.close in)
        (.term ctx)))))

(deftest test-unencrypted-dealer-router-over-tcp
  "Just like the router-dealer equivalent above, but w/ dealer as 'client'"
  (let [ctx (ZMQ/context 1)
        server (.socket ctx ZMQ/ROUTER)
        server-url "tcp://*:54398"]
    (try
      (println "Unencrypted dealer/router over TCP")
      ;; Q: Does this step still matter?
      (.setIdentity server (.getBytes "insecure router/dealer over TCP"))
      (.bind server server-url)
      (try
        (let [client (.socket ctx ZMQ/DEALER)
              client-url "tcp://127.0.0.1:54398"]
          (try
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
          (println "Unbinding the Router socket from" server-url)
          (.unbind server server-url)
          (println "Dealer socket unbound")))
      (finally
        (.close server)
        (.term ctx)))))

(deftest test-encrypted-router-dealer-over-tcp
  "This is broken.
TODO: Contrast with the inproc version to find difference(s) that matter"
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 2)
        in (.socket ctx ZMQ/DEALER)
        server-url "tcp://*:54398"
        client-url "tcp://127.0.0.1:54398"]
    (try
      (println "Encrypted router-dealer TCP test")
      (curve/prepare-client-socket-for-server! in client-keys (:public server-keys))
      (.bind in server-url)
      (try
        (let [out (.socket ctx ZMQ/ROUTER)]
          (curve/make-socket-a-server! out (:private server-keys))
          (.connect out client-url)
          (try
            (dotimes [n 10]
              (let [s-req (str "request" n)
                    req (.getBytes s-req)
                    rep (.getBytes (str "reply" n))]
                (comment) (println "Encrypted dealer/router over TCP" n)
                (let [_ (.sendMore in "")
                      success (.send in req 0)]
                  (when-not success
                    (throw (ex-info (str "Sending request returned:" success) {}))))
                (println "Waiting for encrypted message from dealer to arrive at router over TCP")
                (let  [id (.recv out 0)
                       delimeter (.recv out 0)
                       response (.recv out 0)
                       s-rsp (String. response)]
                  (println "Router received REQ")
                  (let [s-rsp (String. response)]
                    (when-not (= s-req s-rsp)
                      (println "Sent '" s-req "' which consists of " (count req) " bytes\n"
                               "Received '" s-rsp "' which is " (count response) " bytes long")
                      (is (= s-req s-rsp))))
                  (.sendMore out (String. id)))
                (.sendMore out "")
                (let [success (.send out (String. rep))]
                  (when-not success
                    (throw (ex-info (str "Error sending Reply: " success) {}))))
                (println "Dealer waiting for encrypted ACK from Router over TCP")

                (let [delimiter (.recv in 0)
                      _ (println "Delimiter received:" delimiter)
                      response (.recv in 0)]
                  (println "Have response:" response)
                  (is (= (String. rep) (String. response))))
                (println "Encrypted Dealer->Router over TCP complete")))
            (finally
              (.disconect out client-url))))
        (finally
          (.unbind in server-url)))
      (finally
        (.close in)
        (.term ctx)))))

(deftest minimal-curveless-communication-test
  (testing "Because communication is boring until the principals can swap messages"
    ;; Both threads block at receiving. Have verified that this definitely works in python
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
                    (.connect dealer url)
                    (try
                      (let [resp (future (println "Dealer: sending greeting")
                                         (is (.sendMore dealer ""))
                                         (is (.send dealer "OLEH"))
                                         (println "Dealer: greeting sent")
                                         (let [identity (String. (.recv dealer))
                                               result (String. (.recv dealer))]
                                           (println "Dealer: received " result
                                                    " from '" identity "'")
                                           result))]
                        (println "Router: Waiting on encrypted message from dealer")
                        (let [greet (.recv router)]  ;; TODO: Add a timeout
                          (is (= "OLEH" (String. greet)))
                          (println "Router: Encrypted greeting decrypted")
                          (is (.send router "cool"))
                          (println "Router: Response sent")
                          (is (= @resp "cool"))
                          (println "Unencrypted Dealer<->REP: Handshook")))
                      (finally (.disconnect dealer url)))
                    (finally (.close dealer))))
                (finally (.unbind router url))))
            (finally (.close router))))
        (finally (.term ctx))))))

(comment
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
