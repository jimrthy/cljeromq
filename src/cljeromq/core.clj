;; Really need to add the license...this project is LGPL.
;; Q: Why?
;; A: Because the most restrictive license on which it
;; depends is currently zeromq.zmq, and that's its license.
;; This probably isn't strictly required, and it gets finicky
;; when it comes to the EPL...I am going to have to get an
;; opinion from the FSF (and probably double-check with
;; the 0mq people) to verify how this actually works in
;; practice.

;; TODO: What are the ramifications of using libraries that
;; are both EPL and LGPL? AIUI, just using the library
;; doesn't affect other libraries, unless the one you're using
;; is GPL. Even then, that's supposed to be about code that's
;; linked into your exe. That definitely seems like shaky
;; ground. Then again, so does having this be LGPL.

(ns cljeromq.core
  "This is where the basic socket functionality happens.

Should probably update the API to keep it compatible w/ cljzmq
to make swapping back and forth seamless."
  (:require [cljeromq.common :as common :refer (byte-array-type)]
            [cljeromq.constants :as K]
            [clojure.edn :as edn]
            [clojure.spec :as s]
            [clojure.string :as string])
  (:import [java.net InetAddress]
           [java.nio ByteBuffer]
           [java.util Random]
           [org.zeromq
            ZMQ
            ZMQ$Poller
            ZMQException]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs

;;; TODO: Move these elsewhere.
;;; They're slightly more generally useful than the bulk
;;; of the actual code.

;; I don't like these names. But I really have to pick *something*
(s/def ::internal-pair (s/keys :req [::lhs ::rhs ::url]))

;; TODO: This should really be a :common/zmq-url instead
(s/def ::url string?)

(s/fdef send!
        :args (s/cat :socket :cljeromq.common/socket
                     :message (s/or :byte-array :cljoromq.common/byte-array-type
                                    :string string?
                                    :other any?)
                     :flags :cljeromq.constants/socket-options))
(defmulti send! (fn [_ message & flags]
                  (class message)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers

;;; TODO: Really do need something along these lines
;;; for diagnosing problems
;;; This is really a leftover from an obsolete experiment
;;; where I dabbled with just switching everything to JNA
;;; instead of going through the "official" JNI binding.
(comment (defn errno
  "What is the 0mq error state?
  The name is stolen from the C library I'm actually using."
  []
  (let [code (jna/invoke Integer zmq/zmq_errno)
        message (jna/invoke NativeString zmq/zmq_strerror code)]
    ;; TODO: Convert code into a meaningful symbol
    {:code code
     :message (.toString message)})))

(s/fdef has-more?
        :args (s/cat :sock :cljeromq.common/socket)
        :ret boolean?)
(defn has-more?
  [sock]
  (.hasReceiveMore sock))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef context
        :args (s/cat :thread-count (s/and int? pos?))
        :ret :cljeromq.common/context)
(defn context
  "Create a messaging contexts.
threads is the number of threads to use. Should never be larger than (dec cpu-count).

Sources disagree about the optimal value here. Some recommend the max, others just 1.
In practice, I've had issues with < 2, but those were my fault.

Contexts are designed to be thread safe.

There are very few instances where it makes sense to
do anything more complicated than creating the context when your app starts and then calling
terminate! on it just before it exits.

Q: Why didn't I name this context! ?"
  ([thread-count]
     (io! (ZMQ/context thread-count)))
  ([]
     (let [cpu-count (.availableProcessors (Runtime/getRuntime))]
       ;; Go with maximum as default
       (context (max 1 (dec cpu-count))))))

(s/fdef terminate!
        :args (s/cat :ctx :cljeromq.common/context))
(defn terminate!
  "Stop a messaging context.
If you have outgoing sockets with a linger value (which is the default), this will block until
those messages are received."
  [ctx]
  (io!
   (.term ctx)))

;; TODO: spec this out
(defmacro with-context
  "Convenience macro for situations where you can create, use, and kill the context in one place.
Seems like a great idea in theory, but doesn't seem all that useful in practice"
  [[id threads] & body]
  `(let [~id (context ~threads)]
     (try ~@body
          (finally (terminate! ~id)))))

(s/fdef socket!
        :args (s/cat :ctx :cljeromq.common/context
                     :type :cljeromq.constants/socket-types)
        :ret :cljeromq.common/socket)
;; TODO: ^:always-validate
(defn socket!
  "Create a new socket.
TODO: the type really needs to be an enum of keywords"
  [ctx type]
  (let [^Integer real-type (K/sock->const type)]
    (io! (.socket ctx real-type))))

(s/fdef set-linger!
        :args (s/cat :s :cljeromq.common/socket
                     :n integer?))
(defn set-linger!
  [s n]
  (io!
   (.setLinger s n)))

(s/fdef set-router-mandatory!
        :args (s/cat :s :cljeromq.common/socket
                     :on boolean?))
(defn set-router-mandatory!
  "This is hugely important

Desperately needs documentation"
  ([s]
   (set-router-mandatory! s true))
  ([s on]
   (.setRouterMandatory s on)))

(s/fdef close!
        :args (s/cat :s :cljeromq.common/socket))
(defn close!
  "You're done with a socket."
  [s]
  ;; Q: Is it more appropriate to wrap both in io!
  ;; or let set-linger's io! speak for itself?
  ;; Maybe I should just be wrapping up the .close
  ;; here.
  (io! (set-linger! s 0)
       (.close s)))

;; TODO: Spec this out
(defmacro with-socket!
  "Convenience macro for handling the start/use/close pattern"
  [[name context type] & body]
  `(let [~name (socket! ~context ~type)]
     (try ~@body
          (finally (close! ~name)))))

(s/fdef bind!
        :args (s/cat :socket :cljeromq.common/socket
                     :url ::url))
(defn bind!
  "Associate this socket with a stable network interface/port.
Any given machine can only have one socket bound to one endpoint at any given time.

It might be helpful (though ultimately misleading) to think of this call as setting
up the server side of an interaction."
  [socket url]
  (try
    (io! (.bind socket url))
    (catch ZMQException ex
      (throw (ex-info "Binding Failed"
                      {:url url}
                      ex)))))

(s/fdef bind-random-port!
        :args (s/cat :socket :cljeromq.common/socket
                     :endpoint ::url
                     :min :cljeromq.common/port
                     :max :cljeromq.common/port)
        :ret :cljeromq.common/port)
(defn bind-random-port!
  "Binds to the first free port. Endpoint should be of the form
\"<transport>://address\". (It automatically adds the port).
Returns the port number"
  ([socket endpoint]
   (let [port (bind-random-port! socket endpoint 49152 65535)]
     (println (str "Managed to bind to port '" port "'"))
     port))
  ([socket endpoint min]
   (bind-random-port! socket endpoint min 65535))
  ([socket endpoint min max]
   (io!
    (.bindToRandomPort socket endpoint min max))))

(s/fdef unbind!
        :args (s/cat :socket :cljeromq.common/socket
                     :url ::url)
        :ret :cljeromq.common/port)
(defn unbind!
  [socket url]
  (io!
   (try
     ;; TODO: Check the protocol.
     ;; If it's inproc, just skip the inevitable
     ;; failure and pretend everything was kosher.
     ;; Q: Could that cause problems?
     ;; Bigger Q: Isn't unbinding inproc fixed now?
     (.unbind socket url)
     (catch ZMQException ex
       (throw (ex-info "Unbinding failed"
                       {:url url
                        :socket socket}
                       ex))))))

(s/fdef bound-socket!
        :args (s/cat :ctx :cljeromq.common/context
                     :type :clojeromq.constant/socket-types
                     :url ::url)
        :ret :cljeromq.common/socket)
(defn bound-socket!
  "Return a new socket bound to the specified address"
  [ctx type url]
  (let [s (socket! ctx type)]
    (bind! s url)
    s))

;; TODO: Needs spec
(defmacro with-bound-socket!
  [[name ctx type url] & body]
  (let [name# name]
    `(with-socket! [~name# ~ctx ~type]
       (bind! ~name# ~url)
       (try
         ~@body
         (finally
           ;; This is probably redundant, since the socket will be
           ;; going away pretty much immediately.
           (unbind! ~name# ~url))))))


;;; TODO: Desperately need to test this
(defmacro with-randomly-bound-socket!
  [[name port-name ctx type url] & body]
  `(let [url# ~url]
     (with-socket! ['~name ~ctx ~type]
       (let ['~port-name (bind-random-port! '~name url#)]
         (println "DEBUG only: randomly bound port # " '~port-name)
         ~body))))

(s/fdef connect
        :args (s/cat :socket :cljeromq.common/socket
                     :url ::url)
        :ret :cljeromq.common/socket)
(defn connect!
  [socket url]
  (io! (.connect socket url))
  socket)

(s/fdef disconnect!
        :args (s/cat :socket :cljeromq.common/socket
                     :url ::url)
        :ret :cljeromq.common/socket)
(defn disconnect!
  [socket url]
  (io! (.disconnect socket url)))

;; TODO: Spec this
(defmacro with-connected-socket!
  [[name ctx type url] & body]
  (let [name# name
        url# url]
    `(with-socket! [~name# ~ctx ~type]
       (connect! ~name# ~url#)
       (try
         ~@body
         (finally
           (.disconnect ~name# ~url#))))))

(s/fdef connected-socket!
        :args (s/cat :socket :cljeromq.common/socket
                     :type :cljeromq.constant/socket-types
                     :url ::url)
        :ret :cljeromq.common/socket)
(defn connected-socket!
  "Returns a new socket connected to the specified URL"
  [ctx type url]
  (let [s (socket! ctx type)]
    (connect! s url)
    s))

(s/fdef subscribe!
        :ret :cljeromq.common/socket
        :args (s/cat :socket :cljeromq.common/socket
                     :topic string?))
(defn subscribe!
  "SUB sockets won't start receiving messages until they've subscribed"
  ([socket topic]
   (io! (.subscribe socket (.getBytes topic)))
   socket)
  ([socket]
     ;; Subscribes to all incoming messages
     (subscribe! socket "")))

(s/fdef unsubscribe!
        :ret :cljeromq.common/socket
        :args (s/cat :socket :cljeromq.common/socket
                     :topic string?))
(defn unsubscribe!
  ([socket topic]
   (io! (.unsubscribe socket (.getBytes topic))))
  ([socket]
   ;; Q: This *does* unsubscribe from everything, doesn't it?
   (unsubscribe! socket "")))

(s/fdef build-internal-pair!
        :args (s/cat :ctx :cljeromq.common/context)
        :ret ::internal-pair)
(defn build-internal-pair!
  [ctx]
  (io! (let [url (str "inproc://" (gensym))]
         ;; Note that, according to the docs,
         ;; binding must happen first (although
         ;; it doesn't seem to matter in practice)
         ;; As of 2012, this was a long-standing bug.
         {:rhs (bound-socket! ctx :pair url)
          ;; TODO: Verify that connect succeeded!
          :lhs (connected-socket! ctx :pair url)
          :url url})))

(s/fdef close-internal-pair!
        :args (s/cat :pair ::internal-pair))
(defn close-internal-pair!
  [pair]
  ;; Q: Is there any point to setting linger to 0?
  (println "cljeromo: Stopping an internal pair")
  (disconnect! (:lhs pair) (:url pair))
  (println "lhs disconnected")
  (close! (:lhs pair))
  (println "lhs closed")
  (close! (:rhs pair))
  (println "rhs closed"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Send

(defmethod send! byte-array-type
  [socket message flags]
  (comment (println "Sending byte array on" socket "\nFlags:" flags))
  (when-not (.send socket message 0 (count message) (K/flags->const flags))
    (throw (ex-info "Sending failed" {:not-implemented "What went wrong?"}))))

(defmethod send! String
  ([socket message flags]
   (comment (println "Sending string:\n" message))
   (send! socket (.getBytes message) flags))
  ([socket message]
   (io! (send! socket message :dont-wait))))

(defmethod send! :default
  ([socket message flags]
   (comment
     (println "Default Send trying to transmit:\n" message "\n(a"
              (class message) ")"))
   (when (nil? message)
     (throw (NullPointerException. (str "Trying to send on" socket "with flags" flags))))
   ;; For now, assume that we'll only be transmitting something
   ;; that can be printed out in a form that can be read back in
   ;; using eval.
   ;; The messaging layer really shouldn't be responsible for
   ;; serialization at all, but it makes sense to at least start
   ;; this out here.
   (comment (println "Sending the EDN header"))
   (send! socket (-> K/const :flag :edn) :send-more)
   (comment (println "Sending the 'encoded' message"))
   (send! socket (pr-str message) flags)
   (comment (println "Default send! complete")))
  ([socket message]
   ;; Q:
   (send! socket message :dont-wait)))

(s/fdef send-and-forget!
        :args (s/cat :socket :cljeromq.common/socket
                     :message string?))
(defn send-and-forget!
  "Send message, returning immediately.
  Just assume that it succeeded."
  [socket message]
  (io! (send! socket message :dont-wait)))

(s/fdef send-partial!
        :args (s/cat :socket :cljeromq.common/socket
                     :message (s/or :string string?
                                    :bytes :cljeromq.common/byte-array-type)))
(defn send-partial! [socket message]
  "I'm seeing this as a way to send all the messages in an envelope, except
the last.
Yes, it seems dumb, but it was convenient at one point.
Honestly, that's probably a clue that this basic idea is just wrong."
  (send! socket message :send-more))

(s/fdef send-all!
        :args (s/cat :socket :cljeromq.common/socket
                     :messages (s/coll-of (s/or :string string?
                                                :bytes :cljeromq.common/byte-array-type))))
(defn send-all! [socket messages]
  "At this point, I'm basically envisioning the usage here as something like HTTP.
Where the headers back and forth carry more data than the messages.
This approach is a total cop-out.
There's no way it's appropriate here.
I just need to get something written for my
\"get the rope thrown across the bridge\" approach.
It totally falls apart when I'm just trying to send a string."
  (doseq [m messages]
    (send-partial! socket m))
  (send! socket ""))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Questionable Helpers

(defn proxy_
  "Reads from f-in as long as there are messages available,
  forwarding to f-out.

  Odds are, this involves i/o so shouldn't happen inside
  a transaction. Who knows, though?

  f-out needs to be a function that accepts 1 parameter (whatever
  f-in returned).
  Really nothing more than a convenience function because I've
  found myself writing this pattern a lot, and the messaging functions
  don't seem to lend themselves well to the Seq abstraction...though
  that's really exactly what they're doing.

  Q: Why couldn't I handle these messages that way instead? i.e. with
  something like (dorun (map ...))

  TODO: Come up with a better name that doesn't conflict
  with core clojure functionality"
  [f-in f-out]
  (loop [msg (f-in)]
    (when msg
      (f-out msg)
      (recur (f-in)))))

(s/fdef identify!
        :args (s/cat :socket :cljeromq.common/socket
                     :name string?)
        :ret :cljeromq.common/socket)
(defn identify!
  [socket name]
  ;; TODO: Check for errors
  (io! (.setIdentity socket (.getBytes name)))
  socket)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Receive

(s/fdef raw-recv!
        :args (s/cat :socket :cljeromq.common/socket
                     :flags :cljeromq.constant/socket-type)
        :ret :cljeromq.common/byte-array-type)
(defn raw-recv!
  ([socket flags]
   (comment (println "Top of raw-recv"))
   (let [flags (K/flags->const flags)]
     (comment (println "Receiving from socket (flags:" flags ")"))
     (.recv socket flags)))
  ([socket]
   (comment (println "Parameterless raw-recv"))
   (raw-recv! socket :wait)))

(s/fdef recv!
        :args (s/cat :socket :cljeromq.common/socket
                     :flags :cljeromq.constant/socket-type)
        :ret string?)
(defn recv!
  "For receiving non-binary messages.
Strings are the most obvious alternative.
More importantly (probably) is EDN."
  ([socket flags]
   (comment (println "\tListening. Flags: " flags))
   (io!
    (when-let [^bytes binary (raw-recv! socket flags)]
      ;; This should be a ByteBuffer now
      (comment (println "\tRaw:\n" binary))
      (let
          [s (String. binary)]
        (comment (println "Received:\n" s))
        (if (and (has-more? socket)
                 (= s (-> K/const :flag :edn)))
          (do
            (comment (println "Should be more pieces on the way"))
            (let [actual-binary (raw-recv! socket :dont-wait)
                  actual-content (String. actual-binary)]
              (comment (println "Actual message:\n" actual-content))
              ;; FIXME: Really should loop and build up a sequence.
              ;; Absolutely nothing says this will be transmitted one
              ;; form at a time.
              ;; Well, except that doing that is deliberately
              ;; difficult because it's a terrible thing to do
              ;; TODO: Allow callers to specify custom readers
              (edn/read-string actual-content)))
          s)))))
  ([socket]
   (recv! socket :wait)))

(s/fdef recv-all!
        :args (s/cat :socket :cljeromq.common/socket
                     :flags :cljerom.constants/socket-options)
        :ret (s/coll-of :cljeromq.common/byte-array-type))
(defn recv-all!
  "Receive all available message parts.
Q: Does it make sense to accept flags here?
A: Absolutely. May want to block or not."
  ([socket flags]
   (loop [acc []]
     (let [msg (recv! socket flags)
           result (conj acc msg)]
       (if (has-more? socket)
         (recur result)
         result))))
  ([socket]
   (recv-all! socket :wait)))

;; I strongly suspect these next few methods are the original
;; that I've re-written above.
  ;; FIXME: Verify that. See what (if anything) is worth saving.

(s/fdef recv-str!
        :args (s/cat :socket :cljeromq.common/socket
                     :flags :cljerom.constants/socket-options)
        :ret (s/nilable string?))
(defn recv-str!
  ([socket]
   (-> socket recv! String. .trim))
  ([socket flags]
   ;; This approach risks NPE:
   ;;(-> socket (recv flags) String. .trim)
   (when-let [s (recv! socket flags)]
     (-> s String. .trim))))

(s/fdef recv-all-str!
        :args (s/cat :socket :cljeromq.common/socket
                     :flags :cljerom.constants/socket-options)
        :ret (s/coll-of string?))
(defn recv-all-str!
  "How much overhead gets added by just converting the received primitive
Byte[] to strings?"
  ([socket]
   (recv-all-str! socket 0))
  ([socket flags]
   (let [packets (recv-all! socket flags)]
     (map #(String. %) packets))))

(s/fdef recv-obj!
        :args (s/cat :socket :cljeromq.common/socket
                     :flags :cljerom.constants/socket-options)
        :ret any?)
(defn recv-obj!
  "This function is horribly dangerous and really should not be used.
It's also quite convenient:
read a string from a socket and convert it to a clojure object.
That's how this is really meant to be used, if you can trust your peers."
  ([socket]
   (-> socket recv-str! read))
  ([socket flags]
   (when-let [s (recv-str! socket flags)]
     (edn/read-string s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Polling
;;; TODO: this part needs some TLC

(s/fdef poller
        :args (s/cat :socket-count integer?)
        :ret :cljeromq.common/poller)
(defn poller
  "Return a new Poller instance.
Callers probably shouldn't be using something this low-level.
Except when they need to.
There doesn't seem any good reason to put effort into hiding it."
  [socket-count]
  ;; Can't use the convenience alias here
  (ZMQ$Poller. socket-count))

(s/fdef poll
        :args (s/cat :poller :cljeromq.common/poller
                     :timeout integer?)
        :ret integer?)
(defn poll
  "Returns the number of sockets available in the poller
This is just a wrapper around the base handler.
It feels dumb and more than a little pointless. Aside from the
fact that I think it's wrong.
Q: Why do I have a problem with it?
Aside from the fact that it seems like it'd be better to return a
lazy seq of available sockets.
For that matter, it seems like it would be better to just implement
ISeq and return the next message as it becomes ready."
  ([poller]
   (.poll poller))
  ([poller timeout]
   (.poll poller timeout)))

(s/fdef register-socket-in-poller!
        :args (s/cat :socket :cljeromq.common/socket
                     :poller :cljeromq.common/poller
                     :first-flag :cljeromq.constants/poller-flags
                     :more-flags (s/coll-of :cljeromq.constants/poller-flags)))
;; TODO: ^:always-validate
(defn register-socket-in-poller!
  "Register a socket to poll 'in'."
  ([socket
    poller]
   (let [^Long flag (K/control->const :poll-in)]
     (io! (.register poller socket flag))))
  ([socket
    poller
    flag
    & more-flags]
   ;; TODO: Verify that this does what I think with various flag keyword combinations
   (let [^Long actual-flags (K/flags->const (conj more-flags flag))]
     ;; If nothing else, needs a unit test
     (throw (ex-info "Check this" {}))
     (io! (.register poller socket actual-flags)))))

;; TODO: Swap the args either here or in the register counterpart
;; to make them consistent
(s/fdef unregister-socket-in-poller!
        :args (s/cat :poller :cljeromq.common/poller
                     :socket :cljeromq.common/socket))
(defn unregister-socket-in-poller!
  [poller socket]
  (io! (.unregister poller socket)))

(defmacro with-poller [[poller-name context socket] & body]
  "Cut down on some of the boilerplate around pollers.
What's left still seems pretty annoying.
Of course, a big part of the point to real pollers is
dealing with multiple sockets"
  ;; It's pretty blatant that I haven't had any time to
  ;; do anything that resembles testing this code.
  `(let [pn# (cljeromq.core/poller ~context)
         ~poller-name pn#]
     (cljeromq.core/register-socket-in-poller! ~socket pn# :poll-in :poll-err)
     (try
       ~@body
       (finally
         ;; Note that, as written, these arguments are in the wrong order
         ;; The problem isn't here. It's in unregister
         ;; (or possibly register, depending on your perspective)
         (cljeromq.core/unregister-socket-in-poller! ~socket pn#)))))

(s/fdef socket-poller-in!
        :args (s/cat :sockets (s/coll-of :cljeromq.common/socket)))
(defn socket-poller-in!
  "Attach a new poller to a seq of sockets.
Honestly, should be smarter and also allow end-users poll on a single socket.

TODO: Make that so (it isn't a big change, I just don't want to mix it up with
current work)"
  [sockets]
  (let [checker (poller (count sockets))]
    ;; TODO: Convert to run! instead (?)
    (doseq [s sockets]
      (register-socket-in-poller! s checker))
    checker))

(s/fdef in-available?
        :args (s/cat :poller :cljeromq.common/poller
                     :n integer?)
        :ret boolean?)
(defn in-available?
  "Did the last poll trigger poller's POLLIN flag on socket n?

Yes, this is the low-level interface aspect that I want to hide.
There are err and out versions that do the same thing.

Currently, I only need this one."
  [poller n]
  (.pollin poller n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers of dubious value
;;; TODO: Move these elsewhere

(s/fdef connection-string
        :args (s/cat :url :cljeromq.common/zmq-url)
        :ret string?)
(defn connection-string
  [url]
  {:pre [(s/valid? :cljeromq.common/zmq-url url)]
   :post [(string? %)]}
  (let [base-address (:address url)
        address (if (number? (first base-address))
                  (InetAddress/getByAddress (byte-array base-address))
                  (InetAddress/getByName base-address))]
    ;; TODO: This approach is overly simplistic.
    ;; It seems like it's guaranteed to break on inproc,
    ;; as a are minimum.
    (let [base (str (name (:protocol url))
                    "://"
                    (.getHostAddress address))]
      (if-let [port (:port url)]
        (str base ":" port)
        base))))

(s/fdef dump
        :args (s/cat :socket :cljeromq.common/socket)
        :ret string?)
(defn dump
  "Log incoming messages"
  [socket]
  (let [seperator (->> "-" repeat (take 38) (apply str))
        ;; Q: How can I type-hint msg (which should be a Byte Array)
        ;; to avoid reflection warnings?
        formatted (map (fn [msg]
                         (str (format "[%03d] " (count msg))
                              (if (and (= 17 (count msg)) (= 0 (first msg)))
                                (format "UUID %s" (-> msg ByteBuffer/wrap .getLong))
                                (-> msg String. .trim))))
                       (recv-all! socket 0))]
    (string/join \newline (concat [seperator] formatted))))

(s/fdef set-id!
        :args (s/cat :socket :cljeromq.common/socket
                     :n integer?)
        :ret :cljeromq.common/socket)
(defn set-id!
  "Assign a socket's identity based on a couple of pseudo-random numbers and an arbitrary one supplied by caller"
  ([socket n]
    (let [rdn (Random. (System/currentTimeMillis))]
      (identify! socket (str (.nextLong rdn) "-" (.nextLong rdn) "-" n))))
  ([socket]
     (set-id! socket 0)))

(s/fdef string->bytes
        :args (s/cat :s string?)
        :ret :cljeromq.common/byte-array-type)
(defn string->bytes
  "Converts a string into a java array of bytes

Because almost all of these functions really work
on the latter, but it's much easier for humans to
work on the former.

It really seems like this should be a built-in.

Then again, I found this in the clojuredocs
examples for bytes, so that's pretty close"
  [s]
  (->> s
      (map (comp byte int))
      byte-array))

(s/fdef bytes->string
        :args (s/cat :bs :cljeromq.common/byte-array-type)
        :ret string?)
(defn bytes->string
  "Converts a java array of bytes into a string"
  [bs]
  (String. bs))

(s/fdef bit-array->string
        :args (s/cat :bs (s/coll-of :cljeromq.common/byte))
        :ret string?)
(defn bit-array->string [bs]
  ;; Credit:
  ;; http://stackoverflow.com/a/7181711/114334
  (apply str (map #(char (bit-and % 0xff)) bs)))

(defn -main
  "This is a library for you to use...if you can figure out how to install it."
  [ & args]
  "Were you really expecting this to do something?")
