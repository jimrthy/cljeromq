(ns cljeromq.common
  (:require [cljeromq.constants :as K]
   [clojure.spec :as s]
            [clojure.spec.gen :as gen])
  (:import [org.zeromq
            ZMQ
            ZMQ$Context
            ZMQ$Poller
            ZMQ$Socket]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

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
;;; FIXME: Use s/keys and :req/un || :opt-un instead
(s/def ::context #(instance? ZMQ$Context %))
(def Poller ZMQ$Poller)
(s/def ::poller #(instance? ZMQ$Poller %))

(s/def ::direction #{:bind :connect})
(s/def ::socket-type #{:push :pull
                       :req :rep
                       :pair
                       :pub :sub
                       :router :dealer})
(def Socket ZMQ$Socket)
(def socket-descriptions #{})
(defn- socket-creator
  [kind]
  (let [ctx (ZMQ/context 2)
        actual (K/sock->const kind)]
    (.socket ctx actual)))
(def gen-socket (gen/fmap socket-creator (s/gen ::socket-type)))
;; Honestly, I need specs for both bound and connected sockets as well.
(s/def ::socket
  (s/spec
    #(instance? ZMQ$Socket %)
    :gen gen-socket))

;; TODO: Look up the rest
(s/def ::zmq-protocol #{:inproc :tcp})

(s/def ::byte (s/and int? #(<= 0%) #(< % 256)))
(s/def ::dotted-quad (s/tuple ::byte ::byte ::byte ::byte))
(s/def ::hostname string?)  ;; Q: regex? (or something along those lines)
;; TODO: ipv6
(s/def ::zmq-address (s/or :dotted-quad ::dotted-quad
                           :hostname ::hostname
                           :database-name string?))
(s/def ::port (s/and int? (complement neg?) #(< % 65536)))

;; TODO: In recent (pending?) versions, can also specify a resource identifier
;; I think the point's to bind multiple sockets to the same port
(s/def ::zmq-url (s/keys :req [::zmq-protocol ::zmq-address]
                         :opt [::port]))

(s/def ::internal-pair-lhs ::socket)
(s/def ::internal-pair-rhs ::socket)

(s/def ::internal-pair (s/keys :req [::internal-pair-lhs ::internal-pair-rhs ::zmq-url]))
