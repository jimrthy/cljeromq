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
  (:refer-clojure :exclude [send])
  (:require [byte-transforms :as bt]
            [cljeromq.constants :as K]
            [clojure.edn :as edn]
            [net.n01se.clojure-jna :as jna]
            [zeromq.zmq :as mq])
  (:import [com.sun.jna Pointer Native]
           (java.util Random)
           (java.nio ByteBuffer)))

;; Really intended as a higher-level wrapper layer over
;; cljzmq. Or maybe an experimental playground to try out alternative ideas/approaches.

(defn context
  "Create a messaging contexts.
threads is the number of threads to use. Should never be larger than (dec cpu-count).
Contexts are designed to be thread safe.

There are very few instances where it makes sense to
do anything more complicated than creating the context when your app starts and then calling
terminate! on it just before it exits."
  []
  (let [cpu-count (dec (.availableProcessors (Runtime/getRuntime)))]
    (context cpu-count))
  [threads]
  (ZMQ/context threads))

(defn terminate!
  "Stop a messaging context.
If you have outgoing sockets with a linger value (which is the default), this will block until
those messages are received."
  [^ZMQ$Context ctx]
  (io! (.term ctx)))

(defmacro with-context
  "Convenience macro for situations where you can create, use, and kill the context in one place.
Seems like a great idea in theory, but doesn't seem all that useful in practice"
  [[id threads] & body]
  `(let [~id (context ~threads)]
     (try ~@body
          (finally (terminate! ~id)))))

(defn socket!
  "Create a new socket."
  [^ZMQ$Context context type]
  (let [real-type (K/sock->const type)]
    (io! (.socket context real-type))))

(defn close!
  "You're done with a socket.
TODO: Manipulate the socket's linger value appropriately."
  [^ZMQ$Socket s]
  (io! (.close s)))

(defmacro with-socket
  "Convenience macro for handling the start/use/close pattern"
  [[name context type] & body]
  `(let [~name (socket ~context ~type)]
     (try ~@body
          (finally (close! ~name)))))

(comment (defn queue
  "Forwarding device for request-reply messaging.
cljzmq doesn't seem to have an equivalent.
It almost definitely needs one.
FIXME: Fork that repo, add this, send a Pull Request."
  [^ZMQ$Context context ^ZMQ$Socket frontend ^ZMQ$Socket backend]
  (ZMQQueue. context frontend backend)))

(defn bind!
  "Associate this socket with a stable network interface/port.
Any given machine can only have one socket bound to one endpoint at any given time.

It might be helpful (though ultimately misleading) to think of this call as setting
up the server side of an interaction."
  [^ZMQ$Socket socket url]
  (io! (.bind socket url)))

(defn bind-random-port!
  "Binds to the first free port. Endpoint should be of the form
\"<transport>://address\". (It automatically adds the port).
Returns the port!"
  ([^ZMQ$Socket socket endpoint]
     (let [port (bind-random-port! socket endpoint 49152 65535)]
       (println (str "Managed to bind to port '" port "'"))
       port))
  ([^ZMQ$Socket socket endpoint min]
     (bind-random-port! socket endpoint min 65535))
  ([^ZMQ$Socket socket endpoint min max]
     (io! (.bindToRandomPort socket endpoint min max))))

(defn unbind!
  [^ZMQ$Socket socket url]
  (io! (.unbind socket url)))

(defn bound-socket!
  "Return a new socket bound to the specified address"
  [ctx type url]
  (let [s (socket ctx type)]
    (bind! s url)
    s))

(defmacro with-bound-socket!
  [[name ctx type url] & body]
  (let [name# name]
    `(with-socket [~name# ~ctx ~type]
       (bind! ~name# ~url)
       (try
         ~@body
         (finally
           ;; This is probably redundant, since the socket will be
           ;; going away pretty much immediately.
           (unbind! ~name# ~url))))))

(defmacro with-randomly-bound-socket!
  [[name port-name ctx type url] & body]
  (let [name# name
        port-name# port-name
        url# url]
    `(with-socket [~name# ~ctx ~type]
       (let [~port-name# (bind-random-port! ~name# ~url#)]
         (println "DEBUG only: randomly bound port # " ~port-name#)
         (~@body)))))

(defn connect!
  [#^ZMQ$Socket socket url]
  (io! (.connect socket url)))

(defn disconnect!
  [#^ZMQ$Socket socket url]
  (io! (.disconnect socket url)))

(defmacro with-connected-socket!
  [[name ctx type url] & body]
  (let [name# name
        url# url]
    `(with-socket [~name# ~ctx ~type]
       (connect! ~name# ~url#)
       (try
         ~@body
         (finally
           (.disconnect ~name# ~url#))))))

(defn connected-socket!
  "Returns a new socket connected to the specified URL"
  [ctx type url]
  (let [s (socket ctx type)]
    (connect! s url)
    s))

(defn subscribe!
  ([#^ZMQ$Socket socket #^String topic]
     (doto socket
       (io! (.subscribe (.getBytes topic)))))
  ([#^ZMQ$Socket socket]
     (subscribe! socket "")))

(defn unsubscribe!
  ([#^ZMQ$Socket socket #^String topic]
     (doto socket
       (io! (.unsubscribe (.getBytes topic)))))
  ([#^ZMQ$Socket socket]
     ;; Q: This unsubscribes from everything, doesn't it?
     (unsubscribe! socket "")))

;;; Send

(defmulti send! (fn [#^ZMQ$Socket socket message & flags]
                  (class message)))

(defmethod send! bytes
  ([#^ZMQ$Socket socket #^bytes message flags]
     (io! (.send socket message (K/flags->const flags))))
  ([#^ZMQ$Socket socket #^bytes message]
     (io! (.send socket message (K/flags->const :dont-wait)))))

(defmethod send! String
  ([#^ZMQ$Socket socket #^String message flags]
     ;; FIXME: Debug only
     (println "Sending string:\n" message)
     (io! (.send #^ZMQ$Socket socket #^bytes (.getBytes message) (K/flags->const flags))))
  ([#^ZMQ$Socket socket #^String message]
     (io! (send socket message :dont-wait))))

(defmethod send! Long
  ([#^ZMQ$Socket socket #^Long message flags]
  "How on earth is the receiver expected to know the difference
between this and a String?
This seems to combine the difficulty that I don't want to be
handling serialization at this level with the fact that there's
a lot of annoyingly duplicate boilerplate involved in these."
  (io! (.send #^ZMQ$Socket socket #^bytes message (K/flags->const flags))))
  ([#^ZMQ$Socket socket #^Long message]
     (send! Long message :dont-wait)))

(defmethod send! :default
  ([#^ZMQ$Socket socket message flags]
     (println "Default Send trying to transmit:\n" message "\n(a"
              (class message) ")")
     ;; For now, assume that we'll only be transmitting something
     ;; that can be printed out in a form that can be read back in
     ;; using eval.
     ;; The messaging layer really shouldn't be responsible for
     ;; serialization at all, but it makes sense to at least start
     ;; this out here.
     (send! socket (-> K/const :flag :edn), :send-more)
     (send! socket (str message) flags))
  ([#^ZMQ$Socket socket message]
     (send! socket message :dont-wait)))

(defn send-partial! [#^ZMQ$Socket socket message]
  "I'm seeing this as a way to send all the messages in an envelope, except 
the last.
Yes, it seems dumb, but it was convenient at one point.
Honestly, that's probably a clue that this basic idea is just wrong."
  (send! socket message :send-more))

(defn send-all! [#^ZMQ$Socket socket messages]
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

(defn proxy 
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
something like (dorun (map ...))"
  [f-in f-out]
  (loop [msg (f-in)]
    (when msg
      (f-out msg)
      (recur (f-in)))))

(defn identify!
  [#^ZMQ$Socket socket #^String name]
  (io! (.setIdentity socket (.getBytes name))))

(defn raw-recv!
  ([#^ZMQ$Socket socket flags]
     (println "Top of raw-recv")
     (let [flags (K/flags->const flags)]
       (println "Receiving from socket (flags:" flags ")")
       (io! (.recv socket flags))))
  ([#^ZMQ$Socket socket]
     (println "Parameterless raw-recv")
     (raw-recv! socket :wait)))

(defn bit-array->string [bs]
  ;; Credit:
  ;; http://stackoverflow.com/a/7181711/114334
  (apply str (map #(char (bit-and % 255)) bs)))

(defn recv!
  "For receiving non-binary messages.
Strings are the most obvious alternative.
More importantly (probably) is EDN."
  ([#^ZMQ$Socket socket flags]
     ;; I am getting here.
     ;; Well...once upon a time I was.
     (println "\tListening. Flags: " flags)
     ;; And then apparently hanging here.
     ;; Well, except that I've successfully set this up to be non-blocking.
     ;; which means I'm getting a nil.
     (io!
      (let [binary (raw-recv! socket flags)]
        (println "\tRaw:\n" binary)
        (let
            [s (bit-array->string binary)]
          (println "Received:\n" s)
          (if (and (.hasReceiveMore socket)
                   (= s (-> K/const :flag :edn)))
            (do
              (println "Should be more pieces on the way")
              (let [actual-binary (raw-recv! socket :dont-wait)
                    actual-content (bit-array->string actual-binary)]
                (println "Actual message:\n" actual-content)
                ;; FIXME: Really should loop and build up a sequence.
                ;; Absolutely nothing says this will be transmitted one
                ;; sequence at a time.
                ;; Well, except that doing that is purposefully
                ;; difficult.
                (edn/read-string actual-content)))
            s)))))
  ([#^ZMQ$Socket socket]
     (recv! socket :wait)))

(defn recv-more?!
  [socket]
  (io! (.hasReceiveMore socket)))

(defn recv-all!
  "Receive all available message parts.
Q: Does it make sense to accept flags here?
A: Absolutely. May want to block or not."
  ([#^ZMQ$Socket socket flags]
      (loop [acc []]
        (let [msg (recv! socket flags)
              result (conj acc msg)]
          (if (recv-more?! socket)
            (recur result)
            result))))
  ([#^ZMQ$Socket socket]
     ;; FIXME: Is this actually the flag I want?
     (recv-all! socket :wait)))

;; I strongly suspect these next few methods are the original
;; that I've re-written above.
;; FIXME: Verify that. See what (if anything) is worth saving.
(defn recv-str!
  ([#^ZMQ$Socket socket]
      (-> socket recv String. .trim))
  ([#^ZMQ$Socket socket flags]
     ;; This approach risks NPE:
     ;;(-> socket (recv flags) String. .trim)
     (when-let [s (recv socket flags)]
       (-> s String. .trim))))

(defn recv-all-str!
  "How much overhead gets added by just converting the received primitive
Byte[] to strings?"
  ([#^ZMQ$Socket socket]
     (recv-all-str! socket 0))
  ([#^ZMQ$Socket socket flags]
     (let [packets (recv-all! socket flags)]
       (map #(String. %) packets))))

(defn recv-obj!
  "This function is horribly dangerous and really should not be used.
It's also quite convenient:
read a string from a socket and convert it to a clojure object.
That's how this is really meant to be used, if you can trust your peers.
Could it possibly be used safely through EDN?"
  ([#^ZMQ$Socket socket]
     (-> socket recv-str! read))
  ([#^ZMQ$Socket socket flags]
     ;; This is pathetic, but I'm on the verge of collapsing
     ;; from exhaustion
     (when-let [s (recv-str! socket flags)]
       (read s))))

(defn poller
  "Return a new Poller instance.
Callers probably shouldn't be using something this low-level.
Except when they need to.
There doesn't seem any good reason to put effort into hiding it."
  [socket-count]
  (ZMQ$Poller. socket-count))

(defmacro with-poller [[poller-name context socket] & body]
  "Cut down on some of the boilerplate around pollers.
What's left still seems pretty annoying.
Of course, a big part of the point to real pollers is
dealing with multiple sockets"
  ;; I don't think I actually need this sort of gensym
  ;; magic with clojure, do I?
  ;; Not really...but the autogensyms *do* need to happen
  ;; inside the backtick.
  ;; It's pretty blatant that I haven't had any time to
  ;; do anything that resembles testing this code.
  (let [name# poller-name
        ctx# context
        s# socket]
    `(let [~name# (mq/poller ~ctx#)]
       (mq/register ~name# ~s# :poll-in :poll-err)
       (try
         ~@body
         (finally
           (mq/unregister ~name# ~s#))))))

(defn poll
  "Returns the number of sockets available in the poller
FIXME: This is just a wrapper around the base handler.
It feels dumb and more than a little pointless. Aside from the
fact that I think it's wrong."
  ([poller]
     (mq/poll poller))
  ([poller timeout]
     (mq/poll poller timeout)))

(defn check-poller 
  "This sort of new-fangledness is why I started this library in the
first place. I think it's missing the point more than a little if it's already
in the default language binding.

Not that this is actually doing *anything*
different."
  [poller time-out & keys]
  (mq/check-poller poller time-out keys))

(defn register-socket-in-poller!
  "Register a socket to poll on." 
  [#^ZMQ$Socket socket #^ZMQ$Poller poller]
  (io! (.register poller socket :poll-in)))

(defn socket-poller-in!
  "Attach a new poller to a seq of sockets.
Honestly, should be smarter and just let me poll on a single socket."
  [sockets]
  (let [checker (poller (count sockets))]
    (doseq [s sockets]
      (register-socket-in-poller! s checker))
    checker))

(defn dump!
  "Cheeseball first draft at just logging incoming messages.
This approach is pretty awful...at the very least it should build
a string and return that.
Then again, it's fairly lispy...callers can always rediret STDOUT."
  [#^ZMQ$Socket socket]
  (println (->> "-" repeat (take 38) (apply str)))
  (doseq [msg (recv-all! socket 0)]
    (print (format "[%03d] " (count msg)))
    (if (and (= 17 (count msg)) (= 0 (first msg)))
      (println (format "UUID %s" (-> msg ByteBuffer/wrap .getLong)))
      (println (-> msg String. .trim)))))

(defn set-id!
  ([#^ZMQ$Socket socket #^long n]
    (let [rdn (Random. (System/currentTimeMillis))]
      (identify! socket (str (.nextLong rdn) "-" (.nextLong rdn) n))))
  ([#^ZMQ$Socket socket]
     (set-id! socket 0)))

(defn -main [ & args]
  "This is a library for you to use...if you can figure out how to install it."
  "Were you really expecting this to do something?")
