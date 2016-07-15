(ns cljeromq.binding-test
  "Binding really isn't all that interesting, but it's vital"
  (:import [org.zeromq ZMQ
            ZMQ$Curve])
  (:require [cljeromq.core :as mq]
            [clojure.test :refer :all]))

(deftest check-unbinding
  (mq/with-context [ctx 1]
    (mq/with-socket! [nothing ctx :rep]
      (let [addr "tcp://127.0.0.1:5678"]
        (mq/bind! nothing addr)
        (println "Bound" nothing "to" addr)
        (is true)
        (mq/unbind! nothing addr)
        (println "Unbound" nothing "from" addr)
        (is true)))))

(deftest check-unbinding-macro
  (mq/with-context [ctx 1]
    (mq/with-bound-socket! [nothing ctx :rep "tcp://127.0.0.1:5679"]
      (is true))))

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
