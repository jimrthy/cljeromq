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
  "Adjust sock so that it's ready to serve CURVE-encrypted messages"
  [sock private-key]
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-server)
              1
              (Native/getNativeSize Integer))
  (jna/invoke Integer zmq/zmq_setsockopt sock
              (K/option->const :curve-server-key)
              private-key
              40))

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
  "Create a new socket suitable for use as a CURVE server"
  [ctx type private-key])
