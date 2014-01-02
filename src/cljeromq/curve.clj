(ns cljeromq.curve
  (:require [net.n01se.clojure-jna :as jna]
            [cljeromq.constants :as K])
  (:import [com.sun.jna Pointer]
           [java.nio ByteBuffer])
  (:gen-class))

(defn new-key-pair
  "Return a map of new public/private keys in ByteBuffers.
It's very tempting to return them as Strings instead, because
that seems like it would be easiest to deal with. But I
strongly suspect that everything else will be happier with
the ByteBuffers.

It's easy enough to create a new string from the ByteBuffer's
array if you need that.
e.g.
;; (def s (String. (.array buffer)))"
  []
  (let [private (ByteBuffer/allocate 41)
        public (ByteBuffer/allocate 41)
        success (jna/invoke Integer zmq/zmq_curve_keypair
                            public private)]
    (if (= 0 success)
      {:public public
       :private private}
      (throw (RuntimeException. "Creating curve keypair failed")))))

(defn make-socket-a-server!
  "Adjust sock so that it's ready to serve CURVE-encrypted messages.
Documentation seems fuzzy about whether or not it also needs to set
the public key."
  [sock private-key]
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-server)
              1
              (Native/getNativeSize Integer))
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-server-key)
              private-key
              40)
  ;; official tests also set the ZMQ_IDENTITY option.
  ;; Q: What does that actually do?
  )

(defn prepare-client-socket-for-server!
  "Adjust socket options to make it suitable for connecting as
a client to a server identified by server-key"
  [sock client-key-pair server-public-key]
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-server)
              0
              (Native/getNativeSize Integer))
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-server-key)
              server-public-key
              40)
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-public-key)
              (:public client-key-pair)
              40)
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-secret-key)
              (:private client-key-pair)
              40))

(defn server-socket
  "Create a new socket suitable for use as a CURVE server.
There isn't really anything interesting here. Create a new
socket of the specified time then run it through
make-socket-a-server!"
  [ctx type private-key]  
  (throw (RuntimeException. "Get this written")))

(defn build-authenticator
  "Used for ZAP to verify clients"
  []
  ;; This isn't actually part of libzmq. It's in czmq.
  ;; Q: Require that or re-implement?
  ;; Q: For that matter, which is more authoritative?
  ;; A: czmq is a convenience layer atop libzmq.
  ;; Other language bindings are expected to provide
  ;; something along the same lines.
  (throw RuntimeException. "What happened to zauth_new?"))

;; TODO:
;; Need to pull certs from a ZPL-format file
;; (ZMQ RFC4)
;; These files have 2 sections: metadata and curve
;; metadata consists of name=value pairs (1/line)
;; curve has a public-key=key and (possibly) a
;; secret-key=keyvalue.
;; keyvalues are Z85-encoded CURVE keys
;; They look at least vaguely like YAML files.

;; Can build cert files with czmq's addons/makecert
;; program.
