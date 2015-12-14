(ns cljeromq.curve-test
  "In a lot of ways, this is the highest level set of tests
It would be nice if library users could stick to this level of abstraction"
  (:require [cljeromq.curve :as enc]
            [cljeromq.core :as mq]
            [clojure.test :refer (deftest is)])
  (:import clojure.lang.ExceptionInfo))

(defn push-unencrypted [ctx msg]
  (println "Plain-text Push Server thread started")
  (mq/with-socket [pusher ctx :push]
    (mq/connect! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (comment (println "Push " (inc i)))
      (mq/send! pusher (str msg i)))))

(deftest plain-text-push-pull []
  (mq/with-context [ctx 2]
    (mq/with-socket [puller ctx :pull]
      (mq/bind! puller "tcp://127.0.0.1:2101")

      (let [msg "Unencrypted push"
            push-thread (future (push-unencrypted ctx msg))]
        (dotimes [i 10]
          (is (= (mq/recv! puller) (str msg i))
              "Didn't pull what was pushed"))
        (is (nil? @push-thread)
            "Really just checking that it exited correctly")))))

(defn push-encrypted [ctx server-keys msg]
  (println "Encrypted Push-Server thread started")
  (mq/with-socket [pusher ctx :push]
    (enc/make-socket-a-server! pusher server-keys)
    (mq/bind! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (comment (println "Pushing encrypted packet " (inc i)))
      (mq/send! pusher (str msg i) 0))))

(deftest basic-socket-encryption []
  (println "Checking encrypted push/pull interaction")
  (mq/with-context [ctx 1]
    (let [server-keys (enc/new-key-pair)
          msg "Encrypted push"
          push-thread (future (push-encrypted ctx server-keys msg))]
      (mq/with-socket [puller ctx :pull]
        (mq/set-time-out! puller 200)
        (let [client-keys (enc/new-key-pair)]
          (enc/prepare-client-socket-for-server!
           puller client-keys (:public server-keys))
          (mq/connect! puller "tcp://127.0.0.1:2101")

          (try
            (dotimes [i 10]
              (comment (println "Pulling Encrypted Packet # " (inc i)))
              (let [received (try
                               (let [received (mq/recv! puller 0)]
                                 (comment (println "Received: " received))
                                 received)
                               (catch ExceptionInfo ex
                                 (println "Encrypted Receive failed")
                                 ex))]
                (is (= (str msg i)
                       received)
                    "Didn't pull what was pushed")))
            (finally (mq/disconnect! puller "tcp://127.0.0.1:2101")))
          (is (nil? @push-thread) "How did this get broken?"))))))
