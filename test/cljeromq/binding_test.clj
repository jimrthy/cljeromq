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
            ;; Definitely should not.
            ;(.setBytesSockopt router 50 server-public) ; curve-server-key
            ;; Definitely don't need client keys
            ;(.setBytesSockopt router 48 client-public) ; curve-public-key
            ;(.setBytesSockopt router 49 client-secret) ; curve-secret-key
            (.setCurveSecretKey router server-secret)
            ;; IDENT...doesn't seem to matter
            ;; Absolutely vital for the Dealer sockets, though
            (.setIdentity router (.getBytes "SOMETHING"))
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
  "It's inproc, so mostly pointless. Still, had to start somewhere"
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

(deftest test-encrypted-inproc-push-pull
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

(deftest test-encrypted-inproc-router-dealer
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

(deftest test-encrypted-tcp-dealer-router
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 1)
        server (.socket ctx ZMQ/ROUTER)
        server-url "tcp://*:54399"]
    (try
      (curve/make-socket-a-server! server (:private server-keys))
      (.bind server server-url)
      (try
        (let [client (.socket ctx ZMQ/DEALER)
              client-url "tcp://127.0.0.1:54399"]
          (try
            ;; Q: Does this step make any difference?
            ;; A: Absolutely. This actually seems like a Very Bad Thing.
            (.setIdentity client (.getBytes "secure dealer over TCP"))
            (curve/prepare-client-socket-for-server! client client-keys (:public server-keys))
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

(deftest test-encrypted-tcp-router-dealer
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 2)
        server (.socket ctx ZMQ/DEALER)
        server-url "tcp://*:54398"
        client-url "tcp://127.0.0.1:54398"]
    (try
      (.setIdentity server (.getBytes "secure dealer serving TCP"))
      (curve/prepare-client-socket-for-server! server client-keys (:public server-keys))
      (.bind server server-url)
      (try
        (let [client (.socket ctx ZMQ/ROUTER)]
          (try
            ;; Yes, this is another screwy curve, just to see how things work
            (curve/make-socket-a-server! client (:private server-keys))
            (.connect client client-url)
            (try
              (dotimes [n 10]
                (let [s-req (str "request" n)
                      req (.getBytes s-req)
                      rep (.getBytes (str "reply" n))]
                  (let [_ (.sendMore server "")
                        success (.send server req 0)]
                    (when-not success
                      (throw (ex-info (str "Sending request returned:" success) {}))))
                  (let  [id (.recv client 0)
                         delimeter (.recv client 0)
                         response (.recv client 0)
                         s-rsp (String. response)]
                    (let [s-rsp (String. response)]
                      (when-not (= s-req s-rsp)
                        (println "Sent '" s-req "' which consists of " (count req) " bytes\n"
                                 "Received '" s-rsp "' which is " (count response) " bytes long")
                        (is (= s-req s-rsp))))
                    (.sendMore client (String. id)))
                  (.sendMore client "")
                  (let [success (.send client (String. rep))]
                    (when-not success
                      (throw (ex-info (str "Error sending Reply: " success) {}))))

                  (let [delimiter (.recv server 0)
                        response (.recv server 0)]
                    (is (= (String. rep) (String. response))))))
              (finally
                (.disconnect client client-url)))
            (finally
              (.close client))))
        (finally
          (try
            (.unbind server server-url)
            (catch ZMQException _
              (println "Failed to unbind the dealer server. Still annoying")))))
      (finally
        (.close server)
        (.term ctx)))))

(defn handle-zap
  [zap-handler]
  ;; TODO: Honestly, this should really be using
  ;; ZAuth with its ZAPRequest and ZAuthAgent inner
  ;; classes.
  ;; Assuming I need it at all.
  ;; (Imperical evidence implies that I do. Docs imply
  ;; that I shouldn't).
  ;; However: the current implementation does not include any
  ;; sort of CURVE authentication outside the white/blacklist
  ;; mechanism.
  ;; So, really, I need to get this working
  (loop [version (String. (.recv zap-handler))]
    (println "ZAP validation request received version" version)
    (if (.hasReceiveMore zap-handler)
      (do
        (let [request-id (String. (.recv zap-handler))]
          (if (.hasReceiveMore zap-handler)
            (let [domain (.recv zap-handler)]
              (if (.hasReceiveMore zap-handler)
                (let [address (.recv zap-handler)]
                  (if (.hasReceiveMore zap-handler)
                    (let [id (.recv zap-handler)]
                      (do
                        (if (.hasReceiveMore zap-handler)
                          (let [mechanism (String. (.recv zap-handler))]
                            (if (= mechanism "CURVE")
                              (if (.hasReceiveMore zap-handler)
                                (let [credentials (.recv zap-handler)]
                                  (when (.hasReceiveMore zap-handler)
                                    (println "Extra, unexpected frames available"))
                                  (println "=== ZAP:")
                                  (println "Version:" version)
                                  (println "Request ID: " request-id)
                                  (println "Domain: " (String. domain))
                                  (println "Address:" (String. address))
                                  (println (str "Identity: '" (String. id) "'"))
                                  (println "Mechanism: " mechanism)
                                  (println "Credentials: " (curve/z85-encode credentials))
                                  ;; Need to respond with the sequence, b"200 and b"OK"
                                  ;; (based on the python handler, which doesn't match spec)
                                  (when (= version "1.0")
                                    (println "Sending authenticated response")
                                    (try
                                      ;; Since I'm using a REP socket here,
                                      ;; I don't/can't send the empty address separator frame
                                      (.sendMore zap-handler version)
                                      (.sendMore zap-handler request-id)
                                      (.sendMore zap-handler "200")
                                      (.sendMore zap-handler "OK")
                                      (.sendMore zap-handler "user id")
                                      ;; Note that this is actually a dictionary of
                                      ;; key/value pairs.
                                      ;; If you don't include an even number, it just
                                      ;; fails silently
                                      (.send zap-handler "metadata is not used")
                                      (catch Exception ex
                                        (println "Sending authentication failed:" ex)))))
                                (println "ZAP violation: missing credentials"))
                              (do
                                (println "Invalid connection mechanism:" mechanism)
                                ;; TODO: Pop any remaining frames
                                )))
                          (println "ZAP violation: missing frames after identity"))))
                    (println "ZAP violation: missing frames after address")))))
            (println "ZAP violation: just got version and identity"))))
      (println "ZAP violation: Missing everything except the version"))
    (recur (String. (.recv zap-handler)))))

(deftest zap-curve-communication-test
  (testing "Because communication is dangerous until the principals can swap messages securely"
    (let [server-keys (curve/new-key-pair)
          server-public (:public server-keys)
          server-secret (:private server-keys)
          client-keys (curve/new-key-pair)
          client-public (:public client-keys)
          client-secret (:private client-keys)]
      (let [ctx (ZMQ/context 2)]
        (try
          ;; Note that, for realistic scenarios, this should really be a router
          (let [zap-handler (.socket ctx ZMQ/REP)]
            (try
              (.bind zap-handler "inproc://zeromq.zap.01")
              (try
                (let [zap-future (future
                                   (handle-zap zap-handler))]
                  (let [server (.socket ctx ZMQ/REP)]
                    (try
                      (println "Setting up encrypted server socket")
                      (curve/make-socket-a-server! server server-secret)
                      ;; This only matters for NULL security, which doesn't
                      ;; interest me at all
                      (comment (.setZAPDomain server (.getBytes "Does this make any difference?")))
                      (let [localhost "tcp://127.0.0.1"
                            port (.bindToRandomPort server localhost)
                            url (str localhost ":" port)]
                        (try
                          (println "Doing comms over '" url "'")
                          (let [client (.socket ctx ZMQ/DEALER)]
                            (try
                              (println "Setting up encrypted client socket")
                              (.setIdentity client (.getBytes (str (gensym))))
                              ;; Based on the original blog post, it really looks as though
                              ;; the client isn't supposed to set a ZAP domain
                              (comment (.setZAPDomain client (.getBytes "Does this make any difference?")))
                              (curve/prepare-client-socket-for-server! client client-keys server-public)
                              (try
                                (.connect client url)
                                (try
                                  (println "Creating server thread")
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
                                      (let [separator (String. (.recv client))]
                                        (println "Client received separator frame: '" separator "'")
                                        (is (.hasReceiveMore client))
                                        (let [result (String. (.recv client))]
                                          (println "Client: received " result)
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
                (finally (.unbind zap-handler "inproc://zeromq.zap.01")
                         (println "ZAP handler unbound")))
              (finally (.close zap-handler)
                       (println "ZAP handling inproc socket closed"))))
          (finally (.term ctx)
                   (println "Context terminated")))))))

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
