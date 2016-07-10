(ns cljeromq.curve
  (:require [cljeromq.common :as common :refer (byte-array-type)]
            [cljeromq.constants :as K]
            [cljeromq.core :as cljeromq]
            [schema.core :as s])
  (:import [org.zeromq ZMQ$Curve ZMQ$Curve$KeyPair ZMQ$Context ZMQ$Socket])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(s/defschema key-pair {:public byte-array-type
                       :private byte-array-type})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn new-key-pair :- key-pair
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
  (let [pair (ZMQ$Curve/generateKeyPair)
        public (.-publicKey pair)
        private (.-secretKey pair)]
       {:public (ZMQ$Curve/z85Decode public)
        :private (ZMQ$Curve/z85Decode private)}))

(s/defn make-socket-a-server!
  "Adjust sock so that it's ready to serve CURVE-encrypted messages.
Documentation seems fuzzy about whether or not it also needs to set
the public key."
  [sock :- ZMQ$Socket
   private-key :- byte-array-type]
  (io!
   (.setCurveServer sock true)
   (.setCurveSecretKey sock private-key))

  ;; TODO: Move this comment into jzmq
  ;; official tests also set the ZMQ_IDENTITY option.
  ;; Q: What does that actually do?
  ;; A: It's really for clients that might drop
  ;; connections. When they restore (esp. if the
  ;; other side's a Router), they're likely to get
  ;; a new session/identity.
  ;; This lets a client specify its own.
  ;; Which, arguably, is more important for servers.
  ;; The downside to this is that it's really totally
  ;; insecure and unprotected. The mailing lists
  ;; are full of complaints about how this *should*
  ;; work and confusion over how it actually does.
  ;; So...probably a good idea to do, at least in theory.
  )

(s/defn prepare-client-socket-for-server!
  "Adjust socket options to make it suitable for connecting as
a client to a server identified by server-key
TODO: I'm mixing/matching JNA and JNI.
Which seems like a truly horrid idea."
  [sock :- ZMQ$Socket
   {:keys [public private :as client-key-pair]} :- key-pair
   server-public-key :- byte-array-type]
  (io!
   (.setCurvePublicKey sock public)
   (.setCurveSecretKey sock private)
   (.setCurveServerKey sock server-public-key)))

(s/defn server-socket :- ZMQ$Socket
  "Create a new socket suitable for use as a CURVE server.
There isn't really anything interesting here. Create a new
socket of the specified type then run it through
make-socket-a-server!"
  [ctx :- ZMQ$Context
   type :- s/Keyword
   private-key :- byte-array-type]
  (let [s (cljeromq/socket! ctx type)]
    (make-socket-a-server! s private-key)
    s))

(defn build-authenticator
  "Used for ZAP to verify clients"
  []
  ;; This isn't actually part of libzmq. It's in czmq.
  ;; Q: Require that or re-implement?
  ;; Q: For that matter, which is more authoritative?
  ;; A: czmq is the higher level wrapper atop libzmq.
  ;; Other language bindings are expected to provide
  ;; something along the same lines.
  ;; (And jzmq definitely does, so we need to here as well)
  (throw (RuntimeException. "What happened to zauth_new?")))

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
