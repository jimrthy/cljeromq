(ns cljeromq.router-dealer-test
  (:require [cljeromq.core :as core]
            [clojure.test :refer (deftest is)])
  (:import [clojure.lang ExceptionInfo]))

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

(defn server-thread
  [socket verify-fn]
  (try
    (let [incoming (core/recv-all! socket)]
      (verify-fn incoming)
      true)
    (catch ExceptionInfo ex
      (print "Receive failed on the server thread:" ex)
      (throw ex))))

(deftest dealer-connect-send
  []
  (let [uri "tcp://127.0.0.1:16846"
        [ctx dealer router] (setup uri :dealer :router)]
    (try
      (let [verifier (fn [incoming]
                       ;; Dealer's only sending 1 real frame
                       (is (= (count incoming) 3))
                       ;; Don't really expect this to work
                       ;; I think I have a seq rather than a vector
                       (is (= (-> 0 incoming count) 0))
                       (is (= (incoming 2)) "Test"))
            server-thread (future (server-thread router verifier))]
        (core/send! dealer (byte-array 0) :send-more)
        (core/send! dealer "Test")
        (println "Send succeeded")
        (is @server-thread))
      (finally
        (teardown ctx dealer router uri)))))
