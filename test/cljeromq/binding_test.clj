(ns cljeromq.binding-test
  "Binding really isn't all that interesting, but it's vital"
  (:import [org.zeromq ZMQ])
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
