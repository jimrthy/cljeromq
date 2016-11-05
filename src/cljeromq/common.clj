(ns cljeromq.common
  (:refer-clojure :exclude [read])
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
;; Honestly, I need specs for both bound and connected sockets as well.
(s/def ::socket #(instance? ZMQ$Socket %))

;; But start w/ this
(defprotocol IBindingSocket
  "Things with known addresses, like servers, generally do this"
  (bind [this url] "Generally requires exclusive access to a socket"))
(defprotocol IConnectingSocket
  "The other have of a connection that talks to the IBindingSocket.
  Generally considered the client"
  (connect [this url]))
(defprotocol IReadable
  "A socket you can read"
  ;; TODO: Need to exclude clojure.core/read to avoid compiler warning
  (read [this] "Returns a byte-array that was written from the other socket"))
(defprotocol IWriteable
  (write [this array-of-bytes] "Sends array-of-bytes to the other socket"))
;;; This really defines all those protocols/interfaces. By implementing them.
;;; Retrofitting isn't ideal, but I have my doubts about getting alternatives
;;; accepted
(extend ZMQ$Socket
  IBindingSocket {}
  IConnectingSocket {}
  IReadable {}
  IWriteable {})

(comment
  (defn- socket-creator
    [nested-generator]
    ;; nested-generator is a clojure.test.check.generators.Generator
    ;; Which means that it has a :gen member
    (println "Trying to generate a Socket based on"
             nested-generator
             "a" (class nested-generator))
    (throw (ex-info "How did I get here?" {}))
    (let [kind
          ;; Can't call this directly
          #_(nested-generator)
          ;; Can't use gen to call it
          #_(s/gen nested-generator)
          ;; This fails because I have to supply args
          #_((:gen nested-generator))
          (throw (ex-info "Well, what should I do?" {}))]
      (comment) (println "Generating a" kind "socket")
      (let [ctx (ZMQ/context 2)
            actual (K/sock->const kind)]
        (.socket ctx actual))))
  (def gen-socket (partial socket-creator (s/gen ::socket-type))))

;; OK, how are these really supposed to work?
(defn gen-readable-socket
  []
  (gen/return (reify
                IReadable
                (read [this]
                  (println "Trying to read")
                  (gen/generate (gen/bytes))))))
(comment
  ;; Just verifying that reify does what I think
  (println (str (reify
                  Object
                  (toString [this]
                    "blah")))))
(s/def ::testable-read-socket
  (s/spec #_(instance? IReadable %)
          (fn [x]
            (println "Conforming " x "to verify that it's a IReadable? (spoiler: it's a" (class x) ")")
            (try
              (let [success (satisfies? IReadable x)]
                (try
                  (println "It was" (if success "" "not") "!")
                  (catch ClassCastException ex
                    (println "Failed trying to document result of instance?")))
                (when success
                  x))
              (catch ClassCastException ex
                (println "Failure trying to call instance?:" ex))))
          :gen gen-readable-socket))
(defn gen-writeable-socket
  []
  (gen/return (reify
                IWriteable
                (write
                    [this array-of-bytes]
                  ;; Seems like we should do something more than just swallowing the
                  ;; input.
                  ;; Q: What else could possibly make sense?
                  nil))))
(s/def ::testable-write-socket
  (s/spec #(instance? IWriteable %)
          :gen gen-writeable-socket))

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
