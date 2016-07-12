(ns cljeromq.curve-test
  (:require [cljeromq.curve :as enc]
            [cljeromq.core :as mq]
            [clojure.test :refer [deftest is testing]]))

(defn push-unencrypted [ctx msg]
  (comment (println "Plain-text Push Server thread started"))
  (mq/with-socket! [pusher ctx :push]
    (mq/connect! pusher  "tcp://127.0.0.1:2101")
    (dotimes [i 10]
      (comment (println "Push " (inc i)))
      (mq/send! pusher (str msg i) 0))))

(deftest basic-push-pull
       (println "Checking plain-text push/pull interaction")
       (mq/with-context [ctx 2]
         (mq/with-socket! [puller ctx :pull]
           (let [url "tcp://127.0.0.1:2101"]
             (mq/bind! puller url)
             (try
               (let [msg "Unencrypted push"
                     push-thread (future (push-unencrypted ctx msg))]
                 (testing "pulls what was pushed"
                       (comment (println "Checking pulls"))
                       (dotimes [i 10]
                         (is (= (str msg i) (mq/recv! puller)))))
                 (testing "What does msg/send return?"
                       (println "Waiting on push-thread exit")
                       (is (nil? @push-thread))
                       (println "Unencrypted PUSH thread exited")))
               (finally
                 (mq/unbind! puller url)))))))

(let [url "tcp://127.0.0.1:2102"]
  (defn push-encrypted [ctx server-keys msg]
    (println "Encrypted Push-Server thread started")
    (mq/with-socket! [pusher ctx :push]
      (enc/make-socket-a-server! pusher (:private server-keys))
      (mq/connect! pusher url)
      (dotimes [i 10]
        (println "Push" (inc i))
        (mq/send! pusher (str msg i "\n") 0))))

  (deftest basic-socket-encryption
         (println "Checking encrypted push/pull interaction")
         (mq/with-context [ctx 1]
           (let [server-keys (enc/new-key-pair)
                 msg "Encrypted push "
                 push-thread (future (push-encrypted ctx server-keys msg))]
             (mq/with-socket! [puller ctx :pull]
               (let [client-keys (enc/new-key-pair)]
                 (enc/prepare-client-socket-for-server! puller
                                                        client-keys
                                                        (:public server-keys))
                 (mq/bind! puller url)
                 (println "Puller Bound")
                 (try
                   (testing "pulls what was pushed"
                         (dotimes [i 10]
                           (println "Pulling #" (inc i))
                           (is (= (str msg i "\n") (mq/recv! puller)))))
                   (testing "What does msg/send return?"
                         (println "Waiting on Encrypted Push thread to exit")
                         (is (nil? @push-thread))
                         (println "Encrypted Push thread exited"))
                   (finally (mq/unbind! puller url)
                            (println "Encrypted push-pull cleaned up")))))))))
