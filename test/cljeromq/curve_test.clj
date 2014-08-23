(ns cljeromq.curve-test
  (:require [cljeromq.curve :as enc]
            [cljeromq.core :as mq])
  (:use [midje.sweet]))

(defn push-unencrypted [ctx msg]
  (println "Plain-text Push Server thread started")
  (comment (Thread/sleep 500))   ; wait for client to connect (this is awful!)
  (mq/with-socket! [pusher ctx :push]
    (mq/bind! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (comment) (println "Push " (inc i))
      (mq/send! pusher (str msg i)))))

(facts basic-push-pull
       (println "Checking plain-text push/pull interaction")
       (mq/with-context [ctx 2]
         (mq/with-socket! [puller ctx :pull]
           (mq/connect! puller "tcp://127.0.0.1:2101")
           
           (let [msg "Unencrypted push"
                 push-thread (future (push-unencrypted ctx msg))]
             (fact "pulls what was pushed"
                   (dotimes [i 10]
                     (println "Pulling # " (inc i))
                     (mq/recv! puller) => (str msg i)))
             (fact "What does msg/send return?"
                   @push-thread => nil)))))

(defn push-encrypted [ctx server-keys msg]
  (println "Encrypted Push-Server thread started")
  (mq/with-socket! [pusher ctx :push]
    (enc/make-socket-a-server! pusher (:private server-keys))
    (mq/bind! pusher  "tcp://*:2101")
    (Thread/sleep 500)
    (dotimes [i 10]
      (println "Push " (inc i))
      (mq/send! pusher (str msg i)))))

(facts basic-socket-encryption
       (println "Checking encrypted push/pull interaction")
       (mq/with-context [ctx 2]
         (let [server-keys (enc/new-key-pair)
               msg "Encrypted push"
               push-thread (future (push-encrypted ctx server-keys msg))]
           (mq/with-socket! [puller ctx :pull]
             (let [client-keys (enc/new-key-pair)]
               (enc/prepare-client-socket-for-server! 
                puller client-keys (:public server-keys))
               (mq/connect! puller "tcp://localhost:2101")
               
               (fact "pulls what was pushed"
                     (dotimes [i 10]
                       (println "Pulling # " (inc i))
                       (mq/recv! puller) => (str msg i)))
               (fact "What does msg/send return?"
                     @push-thread => nil))))))
