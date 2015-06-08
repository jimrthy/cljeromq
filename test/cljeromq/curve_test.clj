(ns cljeromq.curve-test
  (:require [cljeromq.curve :as enc]
            [cljeromq.core :as mq]
            [clojure.test :refer (deftest is)]))

(defn push-unencrypted [ctx msg]
  (println "Plain-text Push Server thread started")
  (mq/with-socket! [pusher ctx :push]
    (mq/connect! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (comment) (println "Push " (inc i))
      (mq/send! pusher (str msg i)))))

(deftest plain-text-push-pull []
  (mq/with-context [ctx 2]
    (mq/with-socket! [puller ctx :pull]
      (mq/bind! puller "tcp://127.0.0.1:2101")

      (let [msg "Unencrypted push"
            push-thread (future (push-unencrypted ctx msg))]
        (dotimes [i 10]
          (is (= (mq/recv! puller) (str msg i))
              "Didn't pull what was pushed"))
        (is (nil? @push-thread)
            "What does msg/send return?")))))

(defn push-encrypted [ctx server-keys msg]
  (println "Encrypted Push-Server thread started")
  (mq/with-socket! [pusher ctx :push]
    (enc/make-socket-a-server! pusher (:private server-keys))
    (mq/connect! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (println "Push " (inc i))
      (mq/send! pusher (str msg i)))))

(deftest basic-socket-encryption []
  (println "Checking encrypted push/pull interaction")
  (mq/with-context [ctx 1]
    (let [server-keys (enc/new-key-pair)
          msg "Encrypted push"
          push-thread (future (push-encrypted ctx server-keys msg))]
      (mq/with-socket! [puller ctx :pull]
        (let [client-keys (enc/new-key-pair)]
          (enc/prepare-client-socket-for-server!
           puller client-keys (:public server-keys))
          (mq/bind! puller "tcp://127.0.0.1:2101")

          (dotimes [i 10]
            (println "Pulling # " (inc i))
            (is (= (mq/recv! puller) (str msg i))
                "Didn't pull what was pushed"))
          (is (nil? @push-thread) "What does msg/send return?"))))))
