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
  (:refer-clojure :exclude [proxy send])
  (:require [cljeromq.constants :as K]
            [clojure.edn :as edn]
            [ribol.core :refer (raise)]
            [schema.core :as s])
  (:import [java.util Random]
           [java.nio ByteBuffer]
           [org.zeromq
            ZMQ
            ZMQException
            ZMQ$Context
            ZMQ$Poller
            ZMQ$Socket]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;;; really just aliases so I don't have to look at the icky
;;; java names everywhere

(def Context ZMQ$Context)
(def Poller ZMQ$Poller)
(def Socket ZMQ$Socket)
(def InternalPair
  "I don't like these names. But I really have to pick something arbitrary"
  {:lhs Socket
   :rhs Socket
   :url s/Str})

(def byte-array-class (Class/forName "[B"))

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

(defn has-more?
  [#^ZMQ$Socket sock]
  (.hasReceiveMore sock))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn context :- ZMQ$Context
  "Create a messaging contexts.
threads is the number of threads to use. Should never be larger than (dec cpu-count).

Sources disagree about the optimal value here. Some recommend the max, others just 1.
In practice, I've had issues with < 2, but those were my fault.

Contexts are designed to be thread safe.

There are very few instances where it makes sense to
do anything more complicated than creating the context when your app starts and then calling
terminate! on it just before it exits."
  ([thread-count :- s/Int]
     (io! (ZMQ/context thread-count)))
  ([]
     (let [cpu-count (.availableProcessors (Runtime/getRuntime))]
       ;; Go with maximum as default
       (context (dec cpu-count)))))

(s/defn terminate!
  "Stop a messaging context.
If you have outgoing sockets with a linger value (which is the default), this will block until
those messages are received."
  [ctx :- ZMQ$Context]
  (io!
   (.term ctx)))

(defmacro with-context
  "Convenience macro for situations where you can create, use, and kill the context in one place.
Seems like a great idea in theory, but doesn't seem all that useful in practice"
  [[id threads] & body]
  `(let [~id (context ~threads)]
     (try ~@body
          (finally (terminate! ~id)))))

(s/defn socket! :- ZMQ$Socket
  "Create a new socket.
TODO: the type really needs to be an enum of keywords"
  [ctx :- ZMQ$Context type :- s/Keyword]
  (let [^Integer real-type (K/sock->const type)]
    (io! (.socket ctx real-type))))

(s/defn set-linger!
  [s :- ZMQ$Socket
   n :- s/Int]
  (io!
   (.setLinger s n)))

(s/defn set-router-mandatory!
  "This is hugely important

Desperately needs documentation"
  ([s :- ZMQ$Socket]
   (set-router-mandatory! s true))
  ([s :- ZMQ$Socket
    on :- s/Bool]
   (.setRouterMandatory s on)))

(s/defn close!
  "You're done with a socket."
  [s :- ZMQ$Socket]
  ;; Q: Is it more appropriate to wrap both in io!
  ;; or let set-linger's io! speak for itself?
  ;; Maybe I should just be wrapping up the .close
  ;; here.
  (io! (set-linger! s 0)
       (.close s)))

(defmacro with-socket!
  "Convenience macro for handling the start/use/close pattern"
  [[name context type] & body]
  `(let [~name (socket! ~context ~type)]
     (try ~@body
          (finally (close! ~name)))))

(s/defn bind!
  "Associate this socket with a stable network interface/port.
Any given machine can only have one socket bound to one endpoint at any given time.

It might be helpful (though ultimately misleading) to think of this call as setting
up the server side of an interaction."
  [socket :- ZMQ$Socket
   url :- s/Str]
  (try
    (io! (.bind socket url))
    (catch ZMQException ex
      (throw (ex-info "Binding Failed"
                      {:url url}
                      ex))))

  (s/defn bind-random-port! :- s/Int
    "Binds to the first free port. Endpoint should be of the form
\"<transport>://address\". (It automatically adds the port).
Returns the port number"
    ([socket :- ZMQ$Socket endpoint :- s/Str]
     (let [port (bind-random-port! socket endpoint 49152 65535)]
       (println (str "Managed to bind to port '" port "'"))
       port))
    ([socket :- ZMQ$Socket
      endpoint :- s/Str
      min :- s/Int]
     (bind-random-port! socket endpoint min 65535))
    ([socket :- ZMQ$Socket
      endpoint :- s/Str
      min :- s/Int
      max :- s/Int]
     (io!
      (.bindToRandomPort socket endpoint min max)))))

(s/defn unbind! :- s/Int
  [socket :- ZMQ$Socket
   url :- s/Str]
  (io!
   (try
     ;; TODO: Check the protocol.
     ;; If it's inproc, just skip the inevitable
     ;; failure and pretend everything was kosher.
     ;; Q: Could that cause problems?
     (.unbind socket url)
     (catch ZMQException ex
       (throw (ex-info "Unbinding failed"
                       {:url url
                        :socket socket}
                       ex))))))

(s/defn bound-socket! :- ZMQ$Socket
  "Return a new socket bound to the specified address"
  [ctx :- ZMQ$Context
   type :- s/Keyword
   url :- s/Str]
  (let [s (socket! ctx type)]
    (bind! s url)
    s))

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

(s/defn connect!
  [socket :- ZMQ$Socket url :- s/Str]
  (io! (.connect socket url)))

(s/defn disconnect!
  [socket :- ZMQ$Socket url :- s/Str]
  (io! (.disconnect socket url)))

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

(s/defn connected-socket!
  "Returns a new socket connected to the specified URL"
  [ctx :- ZMQ$Context
   type :- s/Keyword
   url :- s/Str]
  (let [s (socket! ctx type)]
    (connect! s url)
    s))

(s/defn subscribe!
  "SUB sockets won't start receiving messages until they've subscribed"
  ([socket :- ZMQ$Socket topic :- s/Str]
   (io! (.subscribe socket (.getBytes topic))))
  ([socket :- ZMQ$Socket]
     ;; Subscribes to all incoming messages
     (subscribe! socket "")))

(s/defn unsubscribe!
  ([socket :- Socket topic :- s/Str]
   (io! (.unsubscribe socket (.getBytes topic))))
  ([socket :- Socket]
   ;; Q: This *does* unsubscribe from everything, doesn't it?
   (unsubscribe! socket "")))

(s/defn build-internal-pair! :- InternalPair
  [ctx :- Context]
  (io! (let [url (str "inproc://" (gensym))]
         {:lhs (connected-socket! ctx :pair url)
          :rhs (bound-socket! ctx :pair url)
          :url url})))

(s/defn close-internal-pair!
  [pair :- InternalPair]
  ;; Q: Is there any point to setting linger to 0?
  (disconnect! (:lhs pair) (:url pair))
  (close! (:lhs pair))
  (close! (:rhs pair)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Send

(defmulti send! (fn [^ZMQ$Socket socket message & flags]
                  (class message)))

(defmethod send! bytes
  ;; This probably isn't very useful.
  ;; Especially since bytes is actually a function
  ;; for converting a sequence into a primitive byte
  ;; array
  ([^ZMQ$Socket socket ^bytes message flags]
   ;; Of course, if we ever do get here somehow, this needs to
   ;; be reconsidered.
   (throw (ex-info "Pretty sure we can't ever possibly get here"
                   {:problem :obsolete}))
   (when-not (.send socket message 0 flags)
     (comment (let [err-code (errno)
                    msg
                    (condp (= (-> K/const :error (:code %))) err-code
                      :again "Non-blocking mode requested, but message cannot currently be sent"
                      :not-supported "Socket cannot send"
                      :fsm "Cannot send in current state"
                      :terminated "Socket's Context has been terminated"
                      :not-socket "Socket invalid"
                      :interrupted "Interrupted by signal"
                      :fault "Invalid message")]
                (raise [:fail {:reason err-code :message msg}])))
     (raise {:not-implemented "What went wrong?"})))
  ([^ZMQ$Socket socket ^bytes message]
     (io! (send! socket message (K/flags->const :dont-wait)))))

(defmethod send! String
  ([^ZMQ$Socket socket ^String message flags]
     ;; FIXME: Debug only
     (comment) (println "Sending string:\n" message)
     ;; My original plan was that this would convert the string
     ;; to clojure.core$bytes, so it would call the method above
     (send! socket (.getBytes message) flags))
  ([^ZMQ$Socket socket ^String message]
     (io! (send! socket message :dont-wait))))

(defmethod send! byte-array-class
  ([^ZMQ$Socket socket message flags]
   ;; Java parameters:
   ;; java byte array message
   ;; offset - where the message starts in that array?
   ;; number of bytes to send
   ;; flags
   (.send socket message 0 (count message) (K/flags->const flags))))

(defmethod send! :default
  ([^ZMQ$Socket socket message flags]
   (comment)
   (println "Default Send trying to transmit:\n" message "\n(a"
            (class message) ")")
   ;; For now, assume that we'll only be transmitting something
   ;; that can be printed out in a form that can be read back in
   ;; using eval.
   ;; The messaging layer really shouldn't be responsible for
   ;; serialization at all, but it makes sense to at least start
   ;; this out here.
   (send! socket (-> K/const :flag :edn), :send-more)
   (send! socket (pr-str message) flags))
  ([^ZMQ$Socket socket message]
   (send! socket message :dont-wait)))

(s/defn send-partial! [socket :- ZMQ$Socket message]
  "I'm seeing this as a way to send all the messages in an envelope, except
the last.
Yes, it seems dumb, but it was convenient at one point.
Honestly, that's probably a clue that this basic idea is just wrong."
  (send! socket message :send-more))

(s/defn send-all! [socket :- ZMQ$Socket messages]
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

(s/defn identify!
  [socket :- ZMQ$Socket name :- s/Str]
  (io! (.setIdentity socket (.getBytes name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Receive

(s/defn raw-recv!  ; Returns byte array
  ([socket :- ZMQ$Socket
    flags :- K/keyword-or-seq]
   (comment (println "Top of raw-recv"))
   (let [flags (K/flags->const flags)]
     (comment (println "Receiving from socket (flags:" flags ")"))
     (.recv socket flags)))
  ([socket :- ZMQ$Socket]
   (comment (println "Parameterless raw-recv"))
   (raw-recv! socket :wait)))

(defn recv!
  "For receiving non-binary messages.
Strings are the most obvious alternative.
More importantly (probably) is EDN."
  ([^ZMQ$Socket socket flags]
   (comment (println "\tListening. Flags: " flags))
   (io!
    (let [^bytes binary (raw-recv! socket flags)]
      ;; This should be a ByteBuffer now
      (println "\tRaw:\n" binary)
      (let
          [s (String. binary)]
        (println "Received:\n" s)
        (if (and (has-more? socket)
                 (= s (-> K/const :flag :edn)))
          (do
            (println "Should be more pieces on the way")
            (let [actual-binary (raw-recv! socket :dont-wait)
                  actual-content (String. actual-binary)]
              (println "Actual message:\n" actual-content)
              ;; FIXME: Really should loop and build up a sequence.
              ;; Absolutely nothing says this will be transmitted one
              ;; form at a time.
              ;; Well, except that doing that is purposefully
              ;; difficult.
              (edn/read-string actual-content)))
          s)))))
  ([#^ZMQ$Socket socket]
   (recv! socket :wait)))

(s/defn recv-all!
  "Receive all available message parts.
Q: Does it make sense to accept flags here?
A: Absolutely. May want to block or not."
  ([socket :- ZMQ$Socket flags]
      (loop [acc []]
        (let [msg (recv! socket flags)
              result (conj acc msg)]
          (if (has-more? socket)
            (recur result)
            result))))
  ([socket :- ZMQ$Socket]
     ;; FIXME: Is this actually the flag I want?
     (recv-all! socket :wait)))

;; I strongly suspect these next few methods are the original
;; that I've re-written above.
;; FIXME: Verify that. See what (if anything) is worth saving.
(s/defn recv-str! :- s/Str
  ([socket :- ZMQ$Socket]
      (-> socket recv! String. .trim))
  ([socket :- ZMQ$Socket flags]
     ;; This approach risks NPE:
     ;;(-> socket (recv flags) String. .trim)
     (when-let [s (recv! socket flags)]
       (-> s String. .trim))))

(s/defn recv-all-str! :- [s/Str]
  "How much overhead gets added by just converting the received primitive
Byte[] to strings?"
  ([socket :- ZMQ$Socket]
     (recv-all-str! socket 0))
  ([socket :- ZMQ$Socket flags]
     (let [packets (recv-all! socket flags)]
       (map #(String. %) packets))))

(s/defn recv-obj!
  "This function is horribly dangerous and really should not be used.
It's also quite convenient:
read a string from a socket and convert it to a clojure object.
That's how this is really meant to be used, if you can trust your peers."
  ([socket :- ZMQ$Socket]
     (-> socket recv-str! read))
  ([socket :- ZMQ$Socket flags]
     (when-let [s (recv-str! socket flags)]
       (edn/read-string s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Polling
;;; TODO: this part needs some TLC

(s/defn poller :- Poller
  "Return a new Poller instance.
Callers probably shouldn't be using something this low-level.
Except when they need to.
There doesn't seem any good reason to put effort into hiding it."
  [socket-count :- s/Int]
  (ZMQ$Poller. socket-count))

(s/defn poll :- s/Int
  "Returns the number of sockets available in the poller
This is just a wrapper around the base handler.
It feels dumb and more than a little pointless. Aside from the
fact that I think it's wrong.
Q: Why do I have a problem with it?
Aside from the fact that it seems like it'd be better to return a
lazy seq of available sockets.
For that matter, it seems like it would be better to just implement
ISeq and return the next message as it becomes ready."
  ([poller :- Poller]
     (.poll poller))
  ([poller :- Poller timeout :- s/Int]
     (.poll poller timeout)))

(s/defn register-socket-in-poller!
  "Register a socket to poll on."
  [poller :- Poller
   socket :- Socket]
  (io! (.register poller socket (K/poll-opts :poll-in))))

(s/defn unregister-socket-in-poller!
  [poller :- Poller
   socket :- Socket]
  (io! (.unregister poller socket)))

(defmacro with-poller [[poller-name context socket] & body]
  "Cut down on some of the boilerplate around pollers.
What's left still seems pretty annoying.
Of course, a big part of the point to real pollers is
dealing with multiple sockets"
  ;; It's pretty blatant that I haven't had any time to
  ;; do anything that resembles testing this code.
  `(let [~poller-name (cljeromq.core/poller ~context)]
     ;; poller-name might be OK to deref multiple times, since it's
     ;; almost definitely a symbol.
     ;; That same is true of socket, isn't it?
     ;; TODO: Ask Steven
     (cljeromq.core/register-socket-in-poller!  ~socket ~poller-name :poll-in :poll-err)
     (try
       ~@body
       (finally
         (cljeromq.core/unregister-socket-in-poller! ~socket ~poller-name)))))

(s/defn socket-poller-in!
  "Attach a new poller to a seq of sockets.
Honestly, should be smarter and just let me poll on a single socket."
  [sockets :- [Socket]]
  (let [checker (poller (count sockets))]
    (doseq [s sockets]
      (register-socket-in-poller! s checker))
    checker))

(s/defn in-available? :- s/Bool
  "Did the last poll trigger poller's POLLIN flag on socket n?

Yes, this is the low-level interface aspect that I want to hide.
There are err and out versions that do the same thing.

Currently, I only need this one."
  [poller :- Poller
   n :- s/Int]
  (.pollin poller n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers of dubious value
;;; TODO: Move these elsewhere

(s/defn dump! :- s/Str
  "Cheeseball first draft at just logging incoming messages.
This approach is pretty awful...at the very least it should build
a string and return that.
Then again, it's fairly lispy...callers can always rediret STDOUT."
  [socket :- Socket]
  (with-out-str
    (println (->> "-" repeat (take 38) (apply str)))
    (doseq [msg (recv-all! socket 0)]
    (print (format "[%03d] " (count msg)))
    (if (and (= 17 (count msg)) (= 0 (first msg)))
      (println (format "UUID %s" (-> msg ByteBuffer/wrap .getLong)))
      (println (-> msg String. .trim))))))

(s/defn set-id!
  ([socket :- Socket
    n :- s/Int]
    (let [rdn (Random. (System/currentTimeMillis))]
      (identify! socket (str (.nextLong rdn) "-" (.nextLong rdn) n))))
  ([#^ZMQ$Socket socket]
     (set-id! socket 0)))

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

(defn bytes->string
  "Converts a java array of bytes into a string"
  [bs]
  (String. bs))

(defn bit-array->string [bs]
  ;; Credit:
  ;; http://stackoverflow.com/a/7181711/114334
  (apply str (map #(char (bit-and % 255)) bs)))

(defn -main
  "This is a library for you to use...if you can figure out how to install it."
  [ & args]
  "Were you really expecting this to do something?")
