(ns cljeromq.common
  (:require [clojure.spec :as s])
  (:import [org.zeromq
            ZMQ$Context
            ZMQ$Poller
            ZMQ$Socket]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def byte-array-type (Class/forName "[B"))
(s/def ::byte-array-type #(instance? byte-array-type %))
(s/def ::byte-array-seq (s/coll-of ::byte-array-type))
;; I hated this name the first few times I ran across it in argument lists.
;; Now that I've typed out the full keyword-or-keywords often enough, I get it.
(s/def ::korks (s/or :single-key keyword?
                     :multi-keys (s/coll-of keyword?)))

(comment
  (let [string->bytes #(->> % (map (comp byte int)) byte-array)
        frames [(string->bytes "address")
                (string->bytes "address2")
                (byte-array [])
                (string->bytes "body")]]
    (s/conform ::byte-array-seq frames)))

;;; Aliases to avoid the ugly java-style nested class names
(def Context ZMQ$Context)
(s/def ::Context #(instance? ZMQ$Context %))
(def Poller ZMQ$Poller)
(s/def ::Poller #(instance? ZMQ$Poller %))
(def Socket ZMQ$Socket)
(s/def ::Socket #(instance? ZMQ$Socket %))
