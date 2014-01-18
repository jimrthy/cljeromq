(ns cljeromq.curve-test
  (:require [cljeromq.curve :as enc]
            [cljeromq.core :as mq])
  (:use [midje.sweet]))

(defn push-encrypted [ctx server-keys msg]
  (mq/with-bound-socket [pusher ctx :push "tcp://*:2101"]
    (enc/make-socket-a-server! pusher (:private server-keys))
    (mq/send pusher msg)))

(facts basic-socket-encryption
       (mq/with-context [ctx 1]
         (let [server-keys (enc/new-key-pair)
               msg "Encrypted push"
               push-thread (future (push-encrypted server-keys msg))]
           (mq/with-connected-socket [puller ctx :pull
                                      "tcp://localhost:2101"]
             (let [client-keys (enc/new-key-pair)]
               (enc/prepare-client-socket-for-server! 
                puller client-keys (:public server-keys))
               
               (fact "pulls what was pushed"
                     (mq/recv puller) => msg)
               (fact "What does msg/send return?"
                     @push-thread => nil))))))
