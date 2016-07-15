(ns cljeromq.curve-test
  (:require [cljeromq.core :as mq]
            [cljeromq.curve :as curve]
            [clojure.test :refer [deftest is testing]])
  (:import [org.zeromq ZMQ
            ZMQException
            ZMQ$Curve]))

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

(let [url "tcp://127.0.0.1:2102"]
  (defn push-encrypted [ctx server-keys msg]
    (println "Encrypted Push-Server thread started")
    (mq/with-socket! [pusher ctx :push]
      (curve/make-socket-a-server! pusher (:private server-keys))
      (mq/bind! pusher url)
      (try
        (dotimes [i 10]
          (println "Push" (inc i))
          (mq/send! pusher (str msg i "\n") 0))
        (finally
          (comment) (mq/unbind! pusher url)))
      (println "push-encrypted thread completed")))

  (deftest basic-socket-encryption
    (println "Checking encrypted push/pull interaction")
    (mq/with-context [ctx 2]
      (let [server-keys (curve/new-key-pair)
            msg "Encrypted push "
            push-thread (future (push-encrypted ctx server-keys msg))]
        (mq/with-socket! [puller ctx :pull]
          (let [client-keys (curve/new-key-pair)]
            (curve/prepare-client-socket-for-server! puller
                                                   client-keys
                                                   (:public server-keys))
            ;; Q: surely this doesn't matter, does it?
            ;; A: Nope, doesn't seem to
            (Thread/sleep 10)
            (mq/connect! puller url)
            (println "Puller Connected")
            (try
              (testing "pulls what was pushed"
                (dotimes [i 10]
                  (println "Pulling #" (inc i))
                  (is (= (str msg i "\n") (mq/recv! puller)))))
              (testing "Push thread exited"
                (println "Waiting on Encrypted Push thread to exit")
                (is (realized? push-thread))
                (println "Encrypted Push thread exited"))
              (finally (comment (mq/disconnect! puller url))
                       (println "Encrypted push-pull cleaned up")))))))))

(deftest test-encrypted-tcp-push-pull
  (let [client-keys (curve/new-key-pair)
        server-keys (curve/new-key-pair)
        ctx (ZMQ/context 2)
        server (.socket ctx ZMQ/PUSH)
        url "tcp://127.0.0.1:54387"]
    (try
      (curve/make-socket-a-server! server (:private server-keys))
      (.bind server url)
      (try
        (let [client (.socket ctx ZMQ/PULL)]
          (try
            (curve/prepare-client-socket-for-server! client client-keys (:public server-keys))
            (.connect client url)
            (println "Starting the real encrypted push/pull test")
            (try
              (dotimes [n 10]
                (let [req (.getBytes (str "request" n))]
                  (println "Sending request" n)
                  (let [success (.send server req 0)]
                    (when-not success
                      (throw (ex-info "Failed" {}))))
                  (println "Waiting for response" n)
                  (let [response (.recv client 0)]
                    (is (= (String. req) (String. response))))))
              (finally
                (println "Disconnecting client")
                (.disconnect client url)))
            (finally (.close client))))
        (finally
          (.unbind server url)))
      (finally
        (.close server)
        (println "Terminating context for encrypted push/pull over TCP")
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
                                      (.send zap-handler #_"metadata is not used" "")
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
                              ;; Note that this doesn't seem to be supplied to the ZAP handler
                              (.setIdentity client (.getBytes (str (gensym))))
                              ;; Based on the original blog post, it really looks as though
                              ;; the client isn't supposed to know anything about the ZAP domain
                              (comment (.setZAPDomain client (.getBytes "Does this make any difference?")))
                              (curve/prepare-client-socket-for-server! client client-keys server-public)
                              (try
                                (.connect client url)
                                (try
                                  (println "Creating server thread")
                                  (let [server-thread
                                        (future
                                          (println "Server: Waiting on encrypted message from dealer")
                                          ;; TODO: Add a timeout so we don't block everything on failure
                                          (let [greet (.recv server)]
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
