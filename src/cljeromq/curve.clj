(ns cljeromq.curve
  (:require #_[byte-streams :as b-s]
            [cljeromq.constants :as K]
            [cljeromq.core :as cljeromq]
            [schema.core :as s])
  (:import [java.nio CharBuffer]
           [java.nio.charset StandardCharsets]
           [org.zeromq.jni ZMQ]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;;; byte-array-type should be defined in here
;;; TODO: Move somewhere more generally sharable
(s/def byte-array-type (Class/forName "[B"))

(s/defschema key-pair {:public byte-array-type
                       :private byte-array-type})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(def iso-8859 StandardCharsets/ISO_8859_1)

(s/defn char-buffer->byte-array :- byte-array-type
  "Really intended for 0mq curve keys. Which, really, start as ASCII"
  [src]
  (.encode iso-8859 src))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn new-key-pair :- key-pair
  "Return a map of new public/private keys in CharBuffers.
It's very tempting to return them as Strings instead, because
that seems like it would be easiest to deal with. But I
strongly suspect that everything else will be happier with
the ByteBuffers.

It's easy enough to create a new string from the ByteBuffer's
array if you need that.
e.g.
;; (def s (String. (.array buffer)))"
  []
  (let [key-length (-> K/const :curve :key-length)
        public-key (CharBuffer/allocate key-length)
        private-key (CharBuffer/allocate key-length)
        pair (ZMQ/zmq_curve_keypair public-key private-key)]
       {:public (char-buffer->byte-array public-key)
        :private (char-buffer->byte-array private-key)}))

(s/defn make-socket-a-server!
  "Adjust sock so that it's ready to serve CURVE-encrypted messages.
Documentation seems fuzzy about whether or not it also needs to set
the public key."
  [sock :- cljeromq/Socket
   private-key :- byte-array-type]

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
  (cljeromq/set-socket-option! sock :curve-server true)
  (cljeromq/set-socket-option! sock :curve-private-key private-key))

(s/defn prepare-client-socket-for-server!
  "Adjust socket options to make it suitable for connecting as
a client to a server identified by server-key
TODO: I'm mixing/matching JNA and JNI.
Which seems like a truly horrid idea."
  [sock :- cljeromq/Socket
   {:keys [public private :as client-key-pair]} :- key-pair
   server-public-key :- byte-array-type]
  (comment (.makeIntoCurveClient sock (ZCurveKeyPair. public private) server-public-key))
  (cljeromq/set-socket-option! sock :curve-server false)
  (cljeromq/set-socket-option! sock :curve-server-key server-public-key)
  (cljeromq/set-socket-option! sock :curve-public-key public)
  (cljeromq/set-socket-option! sock :curve-private-key private))

(s/defn server-socket :- cljeromq/Socket
  "Create a new socket suitable for use as a CURVE server.
There isn't really anything interesting here. Create a new
socket of the specified type then run it through
make-socket-a-server!"
  [ctx :- cljeromq/Context
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
  ;; A: czmq is a convenience layer atop libzmq.
  ;; Other language bindings are expected to provide
  ;; something along the same lines.
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
