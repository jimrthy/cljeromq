(ns cljeromq.common
  (:refer-clojure :exclude [send])
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
;; TODO: Make this go away.
;; Everything that uses it should just switch to using the builtin.
;; Right?
(s/def ::byte-array-type bytes?)
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
  (recv [this] "Returns a byte-array that was written from the other socket"))
(defprotocol IWriteable
  (send [this array-of-bytes] "Sends array-of-bytes to the other socket"))
;;; This really defines all those protocols/interfaces. By implementing them.
;;; Retrofitting isn't ideal, but I have my doubts about getting alternatives
;;; accepted
(extend ZMQ$Socket
  IBindingSocket {}
  IConnectingSocket {}
  IReadable {}
  IWriteable {})

(defn create-test-reader
  []
  (reify
    IReadable
    (recv [this]
      (println "Generated read socket .recv")
      ;; TODO: Switch to just using the byte-array-type generator?
      (gen/generate (gen/bytes)))))
(comment
  ;; Take this down to its minimum
  (.recv (create-test-reader)))

(defn gen-readable-socket
  []
  ;; When I try to actually use this, in frereth.common.baseline-test, I wind
  ;; up with this error:
  ;; No matching method found: recv for class cljeromq.common$gen_readable_socket$reify__34272
  ;; Things I've tried:
  ;; Calling whatever reify returns.
  ;; That error is:
  ;; cljeromq.common$gen_readable_socket$reify__35997 cannot be cast to clojure.lang.IFn
  ;; Note that cljeromq.common-test calls this, and it's fine
  (gen/return (create-test-reader)))
(s/def ::testable-read-socket
  (s/spec (fn [x]
            (println "Checking the Read Socket spec for" x)
            (let [result (satisfies? IReadable x)]
              (if result
                (do
                  (println "This is fine")
                  ;; OK, doing this breaks things.
                  ;; So I'm missing something that should be obvious.
                  (println "Generated:" (.recv x))
                  x)
                (do
                  (println x "does not implement IReadable")
                  nil))))
          :gen gen-readable-socket))

(defn gen-writeable-socket
  []
  (gen/return (reify
                IWriteable
                (send
                    [this array-of-bytes]
                  ;; Seems like we should do something more than just swallowing the
                  ;; input.
                  ;; Q: What else could possibly make sense?
                  nil))))
(s/def ::write-socket (s/spec #(satisfies? IWriteable %)))
(s/def ::testable-write-socket
  (s/spec ::write-socket :gen gen-writeable-socket))

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
