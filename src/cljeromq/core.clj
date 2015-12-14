;;;; Really need to add a license.
;;;; I really thought that this needed to be LGPL,
;;;; since that's 0mq's license.

;;;; But the GPL is really only viral with static linking,
;;;; and LGPL really isn't even that.

;;;; Neither is compatible with the Eclipse license, which
;;;; covers essentially everything in the clojure community.
;;;; Which means this will never get used by anyone else
;;;; if its license isn't in the same ballpark.

;;;; GNU licenses are most definitely and specifically not
;;;; compatible with the Eclipse license.

;;;; Worst case scenario seems to be that it's illegal to
;;;; redistribute this. Which may make it totally useless
;;;; except for internal projects.

;;;; This is a challenging question.

;;;; Note that the zmq-jni license on which this is based
;;;; is released under the Mozilla license, which may be
;;;; more tractable.

(ns cljeromq.core
  "This is where the basic socket functionality happens.

Should probably update the API to keep it compatible w/ cljzmq
to make swapping back and forth seamless."
  (:refer-clojure :exclude [proxy send])
  (:require [cljeromq.constants :as K]
            [clojure.edn :as edn]
            [ribol.core :refer (raise)]
            [schema.core :as s])
  (:import [clojure.lang ExceptionInfo]
           [java.net InetAddress]
           [java.nio ByteBuffer]
           [java.util Random]
           [org.zeromq.jni
            PollItemArray
            ZMQ]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

;;; TODO: Move these elsewhere.
;;; They're slightly more generally useful than the bulk
;;; of the actual code.

;;; really just aliases so I don't have to look at the icky
;;; java names everywhere

(def Context long)
(def Socket long)

(def InternalPair
  "I don't like these names. But I really have to pick something arbitrary"
  {:lhs Socket
   :rhs Socket
   :url s/Str})

(def byte-array-class (Class/forName "[B"))

(def socket-types (s/enum :req :rep
                          :pub :sub
                          :x-pub :x-sub
                          :push :pull
                          :router :dealer
                          :xreq :xrep  ; obsolete names for router/dealer
                          :pair))

(def zmq-protocol
  ;; TODO: Look up the rest
  (s/enum :tcp :inproc))

(def zmq-address (s/either
                  [s/Int]  ; 4 bytes
                  ;; hostname
                  s/Str
                  ;; ??? for IPv6
                  ))

(def zmq-url {:protocol zmq-protocol
              :address zmq-address
              ;; TODO: Actually, this is a short
              (s/optional-key :port) s/Int})

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

(s/defn last-error :- {:error-code s/Int, :error-message s/Str}
  []
  (let [errno (ZMQ/zmq_errno)
        error-string (ZMQ/zmq_strerror errno)]
    {:error-code errno
     :error-message error-string}))

(s/defn add-error-detail :- ExceptionInfo
  [msg :- s/Str
   details :- {s/Any s/Any}]
  (ex-info msg (into details (last-error))))

(s/defn wrap-0mq-boolean-fn-call :- s/Int
  "Assumes the function being wrapped returns a boolean"
  ([f
    error-msg :- s/Str
    base-exception-map :- {s/Any s/Any}]
   (let [success (io! (f))]
     (when-not success
       (throw (add-error-detail error-msg base-exception-map)))
     ;; Every once in a while, the return value
     ;; means something on success
     success))
  ([f
    error-msg :- s/Str]
   ;; Because this is really probably all we care about
   ;; in most cases
   (wrap-0mq-boolean-fn-call f error-msg {})))

(s/defn wrap-0mq-numeric-fn-call :- s/Int
  "Assumes the function being wrapped returns a short/int/long"
  ([f
    error-msg :- s/Str
    base-exception-map :- {s/Any s/Any}]
   (let [success (io! (f))]
     (when (< success 0)
       (throw (add-error-detail error-msg base-exception-map)))
     ;; Every once in a while, the return value
     ;; means something on success
     success))
  ([f
    error-msg :- s/Str]
   ;; Because this is really probably all we care about
   ;; in most cases
   (wrap-0mq-boolean-fn-call f error-msg {})))

(s/defn ^:always-validate set-socket-option!
  [s :- Socket
   option    ; :- (s/either s/Int s/Keyword)
   ;; This might be an int, long, or byte[]
   ;; TODO: Get schema to specify that
   value]
  ;; Compilation fails because it can't find zmq_setsockopt
  ;; Q: What's up?
  (let [real-option (if (keyword? option)
                      (K/option->const option)
                      option)
        ;; This check for boolean? is a pending PR into clojure.core
        ;; TODO: Move it into some sort of util ns where others can
        ;; take advantage
        real-value (if (instance? java.lang.Boolean value)
                     (if value 1 0)
                     value)]
    (wrap-0mq-boolean-fn-call #_(ZMQ/zmq_setsockopt s real-option real-value)
                              (fn []
                                (ZMQ/zmq_setsockopt s real-option real-value))
                              "Setting socket option failed"
                              {:socket s
                               :option option
                               :value value})))

(s/defn ^:always-validate set-context-option!
  [ctx :- Context
   option :- s/Int
   value :- s/Int]
  (wrap-0mq-boolean-fn-call #(ZMQ/zmq_ctx_set ctx option value)
                    "Setting context option failed"
                    {:context ctx
                     :option option
                     :value value}))

(s/defn ^:always-validate get-long-socket-option :- s/Int
  ([sock :- Socket
    option :- s/Keyword
    error-message :- s/Str
    base-error-map :- {s/Any s/Any}]
   (let [real-option (K/option->const option)]
     (wrap-0mq-numeric-fn-call #(ZMQ/zmq_getsockopt_long sock real-option)
                       error-message
                       base-error-map)))
  ([sock :- Socket
    option :- s/Keyword]
   (get-long-socket-option sock
                           option
                           "Failed to extract socket option"
                           {:option-name option})))

(s/defn has-more?
  [sock :- Socket]
  (not= 0 (get-long-socket-option sock :receive-more)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn context :- Context
  "Create a messaging contexts.
threads is the number of threads to use. Should never be larger than (dec cpu-count).

Sources disagree about the optimal value here. Some recommend the max, others just 1.
In practice, I've had issues with < 2, but those were my fault.

Contexts are designed to be thread safe.

There are very few instances where it makes sense to
do anything more complicated than creating the context when your app starts and then calling
terminate! on it just before it exits.

TODO: Rename this to context!"
  ;; TODO: Honestly, need another arity that allows us to set the socket limit
  ([thread-count :- s/Int]
     (io!
      (let [ctx (ZMQ/zmq_ctx_new)]
        ;; Breaks the pattern followed by pretty much everything else
        (when (= ctx 0)
          ;; This is really pretty much undefined.
          ;; Should always be able to create one
          (throw (ex-info
                  "Failed to create a 0mq Context. Haven't tried to apply thread count option yet"
                 {:thread-count thread-count})))
        ;; This could very well throw...
        (let [thread-option (-> K/const :context-options :threads)]
          (comment (println (str "Context Creation: setting option "
                                 thread-option " on " ctx " to " thread-count)))
          (set-context-option! ctx thread-option thread-count))
        ctx)))
  ([]
   ;; Go with maximum advised as default
   ;; Minimum of 1...0mq doesn't work well if it isn't allowed to have any threads at all
   (let [cpu-count (min (.availableProcessors (Runtime/getRuntime)) 2)]
     (context (dec cpu-count)))))

(s/defn terminate!
  "Stop a messaging context.
If you have outgoing sockets with a linger value (which is the default), this will block until
those messages are received."
  [ctx :- Context]
  (wrap-0mq-boolean-fn-call #(ZMQ/zmq_ctx_destroy ctx)
                     "Context Termination Failed"))

(defmacro with-context
  "Convenience macro for situations where you can create, use, and kill the context in one place.
Seems like a great idea in theory, but doesn't seem all that useful in practice"
  [[id threads] & body]
  `(let [~id (context ~threads)]
     (try ~@body
          (finally (terminate! ~id)))))

(s/defn ^:always-validate socket! :- Socket
  "Create a new socket.
TODO: the type really needs to be an enum of keywords"
  [ctx :- Context type :- socket-types]
  (let [^Integer real-type (K/sock->const type)]
    (let [success (io! (ZMQ/zmq_socket ctx real-type))]
      ;; The other method that breaks the pattern
      (when (= success 0)
        (throw (add-error-detail "Socket creation failed"
                                 {:type type
                                  :context ctx})))
      success)))

(s/defn set-linger!
  [s :- Socket
   n :- s/Int]
  (io!
   (set-socket-option! s :linger n)))

(s/defn set-router-mandatory!
  "Pretty vital for debugging router socket messaging issues.

0 (default): Silently discards unrouteable messages
1: Returns EHOSTUNREACHALE if the message cannot be routed
If the SNDHWM is reach and ZMQ_DONTWAIT is set, send attempts will return EAGAIN
If SNDHWM without ZMQ_DONTWAIT, will block."
  ([s :- Socket]
   (set-router-mandatory! s true))
  ([s :- Socket
    on :- s/Bool]
   (set-socket-option! s :router-mandatory on)))

(s/defn set-time-out!
  "-1 (default): infinite
0: always return immediately
n: in milliseconds"
  [s :- Socket
   timeout :- s/Int]
  (set-socket-option! s :receive-time-out timeout))

(s/defn close!
  "You're done with a socket."
  [s :- Socket]
  ;; Q: Is it more appropriate to wrap both in io!
  ;; or let set-linger's io! speak for itself?
  ;; Maybe I should just be wrapping up the .close
  ;; here.
  (set-linger! s 0)
  (wrap-0mq-boolean-fn-call #(ZMQ/zmq_close s)
                    "Invalid socket"))

(defmacro with-socket
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
  [socket :- Socket
   url :- s/Str]
  (wrap-0mq-boolean-fn-call
   #(ZMQ/zmq_bind socket url)
   (str "Unable to bind socket " socket " to " url)
   {:socket socket
    :url url}))

  (s/defn bind-random-port! :- s/Int
    "Binds to the first free port. Endpoint should be of the form
\"<transport>://address\". (It automatically adds the port).
Returns the port number"
    ([socket :- Socket
      endpoint :- s/Str]
     ;; I think these are the ephemeral ports defined for some system
     ;; or other
     ;; Q: Is this something standard?
     ;; More important Q: Is this wise?
     ;; Seems like it would be better to pick something in the range up
     ;; to the ephemerals
     (let [port (bind-random-port! socket endpoint 49152 65535)]
       (println (str "Managed to bind to port '" port "'"))
       port))
    ([socket :- Socket
      endpoint :- s/Str
      min :- s/Int]
     (bind-random-port! socket endpoint min 65535))
    ([socket :- Socket
      endpoint :- s/Str
      min :- s/Int
      max :- s/Int]
     ;; TODO: Implement retries on failure
     (let [range (- max min)
           port (+ (rand-int range) min)]
       (if (bind! socket (str endpoint ":" port))
         port
         (throw (add-error-detail (str "Failed to bind random port: " port)
                                  {:socket socket
                                   :endpoint endpoint
                                   :min min
                                   :max max}))))))

(s/defn unbind!
  [socket :- Socket
   url :- s/Str]
  ;; TODO: Check the protocol.
  ;; If it's inproc, just skip the inevitable
  ;; failure and pretend everything was kosher.
  ;; Q: Could that cause problems?
  (wrap-0mq-boolean-fn-call #(ZMQ/zmq_unbind socket url)
                    "Unable to release socket binding"))

(s/defn bound-socket! :- Socket
  "Return a new socket bound to the specified address"
  [ctx :- Context
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
           ;; going away pretty much immediately anyway.
           ;; Still...better safe than sorry
           (unbind! ~name# ~url))))))


;;; N.B.: This is pretty much totally untested, because
;;; I've never found any actual use for it.
;;; It was really just a "good" excuse to dabble with
;;; macros
(defmacro with-randomly-bound-socket!
  [[name port-name ctx type url] & body]
  `(let [url# ~url]
     (with-socket! ['~name ~ctx ~type]
       (let ['~port-name (bind-random-port! '~name url#)]
         (println "DEBUG only: randomly bound port # " '~port-name)
         ~body))))

(s/defn connect!
  [socket :- Socket
   url :- s/Str]
  (wrap-0mq-boolean-fn-call #(ZMQ/zmq_connect socket url)
                    "Unable to connect"))

(s/defn disconnect!
  [socket :- Socket
   url :- s/Str]
  (wrap-0mq-boolean-fn-call #(ZMQ/zmq_disconnect socket url)
                    "Unable to connect"))

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
  [ctx :- Context
   type :- s/Keyword
   url :- s/Str]
  (let [s (socket! ctx type)]
    (connect! s url)
    s))

(s/defn subscribe!
  "SUB sockets won't start receiving messages until they've subscribed"
  ([socket :- Socket
    topic :- s/Str]
   (set-socket-option! socket :subscribe (.getBytes topic)))
  ([socket :- Socket]
     ;; Subscribes to all incoming messages
     (subscribe! socket "")))

(s/defn unsubscribe!
  ([socket :- Socket topic :- s/Str]
   (set-socket-option! socket :unsubscribe (.getBytes topic)))
  ([socket :- Socket]
   ;; Q: This *does* unsubscribe from everything, doesn't it?
   (unsubscribe! socket "")))

(s/defn build-internal-pair! :- InternalPair
  [ctx :- Context]
  (let [url (str "inproc://" (gensym))]
    ;; Note that, according to the docs,
    ;; binding must happen first (although
    ;; it doesn't seem to matter in practice)
    ;; As of 2012, this was a long-standing bug.
    {:rhs (bound-socket! ctx :pair url)
     :lhs (connected-socket! ctx :pair url)
     :url url}))

(s/defn close-internal-pair!
  [pair :- InternalPair]
  ;; Q: Is there any point to setting linger to 0?
  (println "cljeromq: Stopping an internal pair")
  (disconnect! (:lhs pair) (:url pair))
  (println "lhs disconnected")
  (close! (:lhs pair))
  (println "lhs closed")
  (close! (:rhs pair))
  (println "rhs closed"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Send

(defmulti send! (fn [socket message & flags]
                  (class message)))

(defmethod send! String
  ([socket ^String message flags]
   ;; FIXME: Debug only
   (comment (println "Sending string:\n" message "\nwith flags: " flags))
   ;; My original plan was that this would convert the string
   ;; to clojure.core$bytes, so it would call the method above
   (send! socket (.getBytes message) flags))
  ([socket ^String message]
   (io! (send! socket message :dont-wait))))

(defmethod send! byte-array-class
  ([socket message]
   (send! socket message [:dont-wait]))
  ([socket message flags]
   ;; Java parameters:
   ;; java byte array message
   ;; offset - where the message starts in that array?
   ;; number of bytes to send
   ;; flags
   (comment (println "Sending a " (count message) " byte array with flags: " flags))
   (wrap-0mq-numeric-fn-call #(ZMQ/zmq_send socket message 0 (count message) (K/flags->const flags))
                             "Sending a byte array failed")))

(defmethod send! :default
  ([socket message flags]
   (comment
     (println "Default Send trying to transmit:\n" message "\n(a"
              (class message) ")"))
   (if (nil? message)
     (send! socket (byte-array 0) flags)
     ;; For now, assume that we'll only be transmitting something
     ;; that can be printed out in a form that can be read back in
     ;; using eval.
     ;; The messaging layer really shouldn't be responsible for
     ;; serialization at all, but it makes sense to at least start
     ;; this out here.
     (send! socket (pr-str message) flags)))
  ([socket message]
   (send! socket message :dont-wait)))

(s/defn send-more!
  ([socket message flags]
   (let [flags (if (seq? flags)
                 flags
                 [flags])]
     (send! socket message (conj flags :send-more))))
  ([socket message]
   (send! socket message :send-more)))

(s/defn send-partial!
  "I'm seeing this as a way to send all the messages in an envelope, except
the last.
Yes, it seems dumb, but it was convenient at one point.
Honestly, that's probably a clue that this basic idea is just wrong."
  [socket :- Socket message]
  (send! socket message :send-more))

(s/defn send-all!
  "At this point, I'm basically envisioning the usage here as something like HTTP.
Where the headers back and forth carry more data than the messages.
This approach is a total cop-out.
There's no way it's appropriate here.
I just need to get something written for my
\"get the rope thrown across the bridge\" approach.
It totally falls apart when I'm just trying to send a string."
  [socket :- Socket messages]
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
  [socket :- Socket name :- s/Str]
  (set-socket-option! socket :identity (.getBytes name)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Receive

(s/defn raw-recv! :- byte-array-class
  ([socket :- Socket
    flags :- K/keyword-or-seq]
   (comment (println "Top of raw-recv"))
   (let [flags (K/flags->const flags)]
     (comment (println "Receiving from socket (flags:" flags ")"))
     ;; Q: How do I know whether this failed?
     ;; A: At the moment, there really isn't any way
     (ZMQ/zmq_recv socket flags)))
  ([socket :- Socket]
   (comment (println "Parameterless raw-recv"))
   (raw-recv! socket :wait)))

(s/defn recv!
  "For receiving non-binary messages.
Strings are the most obvious alternative.
EDN is the most obvious thing to send that way.
Though people stuck with inferior languages on the
other side might need to resort to something like JSON"
  ([socket :- Socket
    flags]
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
              ;; Well, except that doing that is purposefully
              ;; difficult.
              (edn/read-string actual-content)))
          s)))))
  ([socket :- Socket]
   (recv! socket :wait)))

(s/defn recv-all!
  "Receive all available message parts.
Q: Does it make sense to accept flags here?
A: Absolutely. May want to block or not."
  ([socket :- Socket flags]
      (loop [acc []]
        (let [msg (recv! socket flags)
              result (conj acc msg)]
          (if (has-more? socket)
            (recur result)
            result))))
  ([socket :- Socket]
     ;; FIXME: Is this actually the flag I want?
     (recv-all! socket :wait)))

;; I strongly suspect these next few methods are the original
;; that I've re-written above.
;; FIXME: Verify that. See what (if anything) is worth saving.
(s/defn recv-str! :- s/Str
  ([socket :- Socket]
      (-> socket recv! String. .trim))
  ([socket :- Socket flags]
     ;; This approach risks NPE:
     ;;(-> socket (recv flags) String. .trim)
     (when-let [s (recv! socket flags)]
       (-> s String. .trim))))

(s/defn recv-all-str! :- [s/Str]
  "Q: How much overhead gets added by just converting the received primitive
Byte[] to strings?"
  ([socket :- Socket]
     (recv-all-str! socket 0))
  ([socket :- Socket flags]
     (let [packets (recv-all! socket flags)]
       (map #(String. %) packets))))

(s/defn recv-obj!
  "This function is horribly dangerous and really should not be used.
It's also quite convenient:
read a string from a socket and convert it to a clojure object.
That's how this is really meant to be used, if you can trust your peers."
  ([socket :- Socket]
     (-> socket recv-str! read))
  ([socket :- Socket flags]
     (when-let [s (recv-str! socket flags)]
       (edn/read-string s))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Polling
;;; TODO: this part needs some TLC

(s/defn poll-item-array :- PollItemArray
  "Return a new PollItemArray instance.
Callers probably shouldn't be using something this low-level.
Except when they need to.
There doesn't seem any good reason to put effort into hiding it."
  [socket-count :- s/Int]
  (PollItemArray/allocatePollItems socket-count))

(s/defn poll :- s/Int
  "Returns the number of sockets available in the poller

This is just a wrapper around the base handler.
It feels dumb and more than a little pointless. Aside from the
fact that I think it's wrong.
Q: Why do I have a problem with it?
Aside from the fact that it seems like it'd be better to return a
lazy seq of available sockets.
For that matter, it seems like it would be better to just implement
ISeq and return the next message as it becomes ready.

But this is a start."
  ([poller :- PollItemArray]
   (poll poller 0))
  ([poller :- PollItemArray
    timeout :- s/Int]
   (wrap-0mq-numeric-fn-call #(ZMQ/zmq_poll poller timeout) "Polling failed")))

(s/defn register-socket-in-poller!
  "Register a socket to poll on.
This didn't make a lot of sense in the original.
Seems almost totally pointless, given the current implementation"
  [poller :- PollItemArray
   socket :- Socket]
  (throw (RuntimeException. "obsolete")))

(s/defn unregister-socket-in-poller!
  [poller :- PollItemArray
   socket :- Socket]
  (throw (RuntimeException. "obsolete")))

(s/defn socket-poller :- PollItemArray
  ;; The values in this map are really korks
  ;; TODO: What does the Schema look like for that now?
  [socks :- {Socket [s/Keyword]}]
  (let [length (count socks)
        result (poll-item-array length)
        lazy-seq (map-indexed
                  (fn [idx sock]
                    (let [poll-item (.get result idx)
                          poll-opt-keys (get socks sock)
                          poll-opts (K/poll-opts poll-opt-keys)]
                      (io!
                       (.setSocket poll-item sock)
                       (.setEvents poll-item poll-opts))))
                  (keys socks))]
    ;; Make sure the side-effects happen
    (dorun lazy-seq)
    result))

(comment
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
     (cljeromq.core/register-socket-in-poller!  ~socket ~poller-name :poll-in :poll-err)
     (try
       ~@body
       (finally
         (cljeromq.core/unregister-socket-in-poller! ~socket ~poller-name))))))


(s/defn socket-poller-in!
  "Attach a new poller to a seq of sockets.
Honestly, should be smarter and just let me poll on a single socket."
  [sockets :- [Socket]]
  (let [descr (reduce (fn [acc next]
                        (assoc acc next :poll-in))
                      {}
                      sockets)]
    (socket-poller descr)))

(s/defn events-polled :- s/Int
  [poller :- PollItemArray
   index :- s/Int]
  (let [item (.get poller index)]
    (.getREvents item)))

(s/defn in-available? :- s/Bool
  "Did the last poll trigger poller's POLLIN flag on socket n?

Yes, this is the low-level interface aspect that I want to hide.
There are err and out versions that do the same thing.

Currently, I only need this one."
  [poller :- PollItemArray
   index :- s/Int]
  (let [ready-events (events-polled poller index)]
    (not= 0 (bit-and ready-events (K/poll-opts :poll-in)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers of dubious value
;;; TODO: Move these elsewhere

(s/defn ^:always-validate connection-string :- s/Str
  [url :- zmq-url]
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
  ([socket :- Socket]
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
