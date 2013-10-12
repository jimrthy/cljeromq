;; Really intended as a higher-level wrapper layer over
;; cljzmq.
;;
;; Really need to add the license...this project is LGPL.
;; Q: Why?
;; A: Because the most restrictive license on which it
;; depends is currently zeromq.zmq, and that's its license.
;; This probably isn't strictly required, and it gets finicky
;; when it comes to the EPL...I am going to have to get an
;; opinion from the FSF (and probably double-check with
;; the 0mq people) to verify how this actually works in 
;; practice.

(ns cljeromq.core
  (:refer-clojure :exclude [send])
  (:require [byte-transforms :as bt]
            [clojure.edn :as edn]
            [taoensso.timbre :as timbre
             :refer (trace debug info warn error fatal spy with-log-level)]
            [zeromq.zmq :as mq])
  (:import [org.zeromq ZMQ ZMQ$Context ZMQ$Socket ZMQ$Poller ZMQQueue])
  (:import (java.util Random)
           (java.nio ByteBuffer)))

;; FIXME: Debug only!
;; Q: Set up real logging options?
;; A: Really should let whatever uses this library configure that.
(comment (timbre/set-level! :trace))

(defn context [threads]
  (ZMQ/context threads))

(defn terminate [#^ZMQ$Context ctx]
  (.term ctx))

(defmacro with-context
  [[id threads] & body]
  `(let [~id (context ~threads)]
     (try ~@body
          (finally (terminate ~id)))))

(def const {
            :control {
                      ;; Non-blocking send/recv
                      :no-block ZMQ/NOBLOCK
                      :dont-wait ZMQ/DONTWAIT

                      ;; Blocking (default...doesn't seem to be an 
                      ;; associated named constant)
                      :wait 0
                                
                      ;; More message parts are coming
                      :sndmore ZMQ/SNDMORE
                      :send-more ZMQ/SNDMORE}
            
            ;;; Socket types
            :socket-type {
                          ;; Request/Reply
                          :req ZMQ/REQ
                          :rep ZMQ/REP
                                    
                          ;; Publish/Subscribe
                          :pub ZMQ/PUB
                          :sub ZMQ/SUB
                          
                          ;; Extended Publish/Subscribe
                          :x-pub ZMQ/XPUB
                          :x-sub ZMQ/XSUB
                          ;; Push/Pull
                          
                          :push ZMQ/PUSH
                          :pull ZMQ/PULL

                          ;; Internal 1:1
                          :pair ZMQ/PAIR

                          ;; Router/Dealer

                          ;; Creates/consumes request-reply routing envelopes.
                          ;; Lets you route messages to specific connections if you
                          ;; know their identities.
                          :router ZMQ/ROUTER
                                    
                          ;; Combined ventilator/sink.
                          ;; Does load balancing on output and fair-queuing on input.
                          ;; Can shuffle messages out to N nodes then shuffle the replies back.
                          ;; Raw bidirectional async pattern.
                          :dealer ZMQ/DEALER
                                    
                          ;; Obsolete names for Router/Dealer
                          :xreq ZMQ/XREQ
                          :xrep ZMQ/XREP}
            ;; Named magical numbers/strings
            :flag
            {:edn "clojure/edn"}})

(defn control->const
  "Convert a control keyword to a ZMQ constant"
  [key]
  (trace "Extracting " key)
  ((const :control) key))

(defn sock->const
  "Convert a socket keyword to a ZMQ constant"
  [key]
  ((const :socket-type) key))

(defn socket
  [#^ZMQ$Context context type]
  (let [real-type (sock->const type)]
    (.socket context real-type)))

(defn close [#^ZMQ$Socket s]
  (.close s))

(defmacro with-socket [[name context type] & body]
  `(let [~name (socket ~context ~type)]
     (try ~@body
          (finally (close ~name)))))

(defmacro with-poller [[poller-name context socket] & body]
  "Cut down on some of the boilerplate around pollers.
What's left still seems pretty annoying."
  ;; I don't think I actually need this sort of gensym
  ;; magic with clojure, do I?
  ;; Not really...but the autogensyms *do* need to happen inside
  ;; inside the backtick.
  ;; It's pretty blatant that I haven't had any time to
  ;; do anything that resembles testing this code.
  (let [name# poller-name
        ctx# context
        s# socket]
    `(let [~name# (mq/poller ~ctx#)]
       (mq/register ~name# ~s# :pollin :pollerr)
       (try
         ~@body
         (finally
           (mq/unregister ~name# ~s#))))))

(comment (defn queue
  "Forwarding device for request-reply messaging.
cljzmq doesn't seem to have an equivalent.
It almost definitely needs one.
FIXME: Fork that repo, add this, send a Pull Request."
  [#^ZMQ$Context context #^ZMQ$Socket frontend #^ZMQ$Socket backend]
  (ZMQQueue. context frontend backend)))

(defn bind
  [#^ZMQ$Socket socket url]
  (.bind socket url))

(defmacro with-bound-socket
  [[name ctx type url] & body]
  (let [name# name]
    `(with-socket [name# ~ctx ~type]
       (bind name# ~url)
       ~@body)))

(defn bound-socket
  "Return a new socket bound to the specified address"
  [ctx type url]
  (let [s (socket ctx type)]
    (bind s url)
    s))

(defn connect
  [#^ZMQ$Socket socket url]
  (.connect socket url))

(defmacro with-connected-socket
  [[name ctx type url] & body]
  (let [name# name]
    `(with-socket [name# ~ctx ~type]
       (connect name# ~url)
       ~@body)))

(defn connected-socket
  "Returns a new socket connected to the specified URL"
  [ctx type url]
  (let [s (socket ctx type)]
    (connect s url)
    s))

(defn subscribe
  ([#^ZMQ$Socket socket #^String topic]
     (doto socket
       (.subscribe (.getBytes topic))))
  ([#^ZMQ$Socket socket]
     (subscribe socket "")))

(defn unsubscribe
  ([#^ZMQ$Socket socket #^String topic]
     (doto socket
       (.unsubscribe (.getBytes topic))))
  ([#^ZMQ$Socket socket]
     (unsubscribe socket "")))

;;; Send

(defmulti send (fn [#^ZMQ$Socket socket message & flags]
                 (class message)))

(defn flags->const ^long [flags]
  "Use in conjunction with control-const to convert a series
of/individual keyword into a logical-or'd flag to control
socket options."
  (if (seq? flags)
    (reduce bit-or (map control->const flags))
    (control->const flags)))

(defmethod send bytes
                 ([#^ZMQ$Socket socket #^bytes message flags]
                    (.send socket message (flags->const flags)))
                 ([#^ZMQ$Socket socket #^bytes message]
                    (.send socket message (flags->const :dont-wait))))

(defmethod send String
  ([#^ZMQ$Socket socket #^String message flags]
     (trace "Sending string:\n" message)
     (.send #^ZMQ$Socket socket #^bytes (.getBytes message) (flags->const flags)))
  ([#^ZMQ$Socket socket #^String message]
     (send socket message :dont-wait)))

(defmethod send Long
  ([#^ZMQ$Socket socket #^Long message flags]
  "How on earth is the receiver expected to know the difference
between this and a String?
This seems to combine the difficulty that I don't want to be
handling serialization at this level with the fact that there's
a lot of annoyingly duplicate boilerplate involved in these."
     (.send #^ZMQ$Socket socket #^bytes message (flags->const flags)))
  ([#^ZMQ$Socket socket #^Long message]
     (send Long message :dont-wait)))

(defmethod send :default
  ([#^ZMQ$Socket socket message flags]
     (trace "Trying to transmit:\n" message "\n(a"
              (class message) ")")
     ;; For now, assume that we'll only be transmitting something
     ;; that can be printed out in a form that can be read back in
     ;; using eval.
     ;; The messaging layer really shouldn't be responsible for
     ;; serialization at all, but it makes sense to at least start
     ;; this out here.
     (send socket (-> const :flag :edn), :send-more)
     (send socket (str message) flags))
  ([#^ZMQ$Socket socket message]
     (send socket message :dont-wait)))

(defn send-partial [#^ZMQ$Socket socket message]
  "I'm seeing this as a way to send all the messages in an envelope, except 
the last.
Yes, it seems dumb, but it was convenient at one point.
Honestly, that's probably a clue that this basic idea is just wrong."
  (send socket message (const :send-more)))

(defn send-all [#^ZMQ$Socket socket messages]
  "At this point, I'm basically envisioning the usage here as something like HTTP.
Where the headers back and forth carry more data than the messages.
This approach is a total cop-out.
There's no way it's appropriate here.
I just need to get something written for my
\"get the rope thrown across the bridge\" approach.
It totally falls apart when I'm just trying to send a string."
  (doseq [m messages]
    (send-partial socket m))
  (send socket ""))

(defn identify
  [#^ZMQ$Socket socket #^String name]
  (.setIdentity socket (.getBytes name)))

(defn raw-recv
  ([#^ZMQ$Socket socket flags]
     (trace "Top of raw-recv")
     (let [flags (flags->const flags)]
       (trace "Receiving from socket (flags:" flags ")")
       (.recv socket flags)))
  ([#^ZMQ$Socket socket]
     (trace "Parameterless raw-recv")
     (raw-recv socket :wait)))

(defn bit-array->string [bs]
  ;; Credit:
  ;; http://stackoverflow.com/a/7181711/114334
  (apply str (map #(char (bit-and % 255)) bs)))

(defn recv
  "For receiving non-binary messages.
Strings are the most obvious alternative.
More importantly (probably) is EDN."
  ([#^ZMQ$Socket socket flags]
     ;; I am getting here.
     ;; Well...once upon a time I was.
     (trace "\tListening. Flags: " flags)
     ;; And then apparently hanging here.
     ;; Well, except that I've successfully set this up to be non-blocking.
     ;; which means I'm getting a nil.
     (let [binary (raw-recv socket flags)]
       (trace "\tRaw:\n" binary)
       (let
           [s (bit-array->string binary)]
           (trace "Received:\n" s)
           (if (and (.hasReceiveMore socket)
                    (= s (-> const :flag :edn)))
             (do
               (trace "Should be more pieces on the way")
               (let [actual-binary (raw-recv socket :dont-wait)
                     actual-content (bit-array->string actual-binary)]
                 (trace "Actual message:\n" actual-content)
                 ;; FIXME: Really should loop and build up a sequence.
                 ;; Absolutely nothing says this will be transmitted one
                 ;; sequence at a time.
                 ;; Well, except that doing that is purposefully
                 ;; difficult.
                 (edn/read-string actual-content)))
             s))))
  ([#^ZMQ$Socket socket]
     (recv socket :wait)))

(defn recv-all
  "Receive all available message parts.
Q: Does it make sense to accept flags here?
A: Absolutely. May want to block or not."
  ([#^ZMQ$Socket socket flags]
      (loop [acc []]
        (let [msg (recv socket flags)
              result (conj acc msg)]
          (if (.hasReceiveMore socket)
            (recur result)
            result))))
  ([#^ZMQ$Socket socket]
     ;; FIXME: Is this actually the flag I want?
     (recv-all socket (const :send-more))))

;; I strongly suspect these next few methods are the original
;; that I've re-written above.
;; FIXME: Verify that. See what (if anything) is worth saving.
(defn recv-str
  ([#^ZMQ$Socket socket]
      (-> socket recv String. .trim))
  ([#^ZMQ$Socket socket flags]
     ;; This approach risks NPE:
     ;;(-> socket (recv flags) String. .trim)
     (when-let [s (recv socket flags)]
       (-> s String. .trim))))

(defn recv-all-str
  "How much overhead gets added by just converting the received primitive
Byte[] to strings?"
  ([#^ZMQ$Socket socket]
     (recv-all-str socket 0))
  ([#^ZMQ$Socket socket flags]
     (let [packets (recv-all socket flags)]
       (map #(String. %) packets))))

(defn recv-obj
  "This function is horribly dangerous and really should not be used.
It's also quite convenient:
read a string from a socket and convert it to a clojure object.
That's how this is really meant to be used, if you can trust your peers.
Could it possibly be used safely through EDN?"
  ([#^ZMQ$Socket socket]
     (-> socket recv-str read))
  ([#^ZMQ$Socket socket flags]
     ;; This is pathetic, but I'm on the verge of collapsing
     ;; from exhaustion
     (when-let [s (recv-str socket flags)]
       (read s))))

(defn poller
  "Return a new Poller instance.
Callers probably shouldn't be using something this low-level.
Except when they need to.
There doesn't seem any good reason to put effort into hiding it."
  [socket-count]
  (ZMQ$Poller. socket-count))

(def poll-in ZMQ$Poller/POLLIN)
(def poll-out ZMQ$Poller/POLLOUT)

(defn poll
  "FIXME: This is just a wrapper around the base handler.
It feels dumb and more than a little pointless. Aside from the
fact that I think it's wrong.
At this point, I just want to get pieces to compile so I can
call it a night...
what does that say about the dynamic/static debate?"
  [poller]
  (mq/poll poller))

(defn check-poller 
  "This sort of new-fangledness is why I started this library in the
first place. It's missing the point more than a little if it's already
in the default language binding." 
  [poller time-out & keys]
  (mq/check-poller poller time-out keys))

(defn close 
  "Yeah, this seems more than a little stupid"
  [sock]
  (.close sock))

(defn register-in
  "Register a listening socket to poll on." 
  [#^ZMQ$Socket socket #^ZMQ$Poller poller]
  (.register poller socket poll-in))

(defn socket-poller-in
  "Attach a new poller to a seq of sockets.
Honestly, should be smarter and just let me poll on a single socket."
  [sockets]
  (let [checker (poller (count sockets))]
    (doseq [s sockets]
      (register-in s checker))
    checker))

(defn dump
  "Cheeseball first draft at just logging incoming messages.
This approach is pretty awful...at the very least it should build
a string and return that.
Then again, it's fairly lispy...callers can always rediret STDOUT."
  [#^ZMQ$Socket socket]
  (println (->> "-" repeat (take 38) (apply str)))
  (doseq [msg (recv-all socket 0)]
    (print (format "[%03d] " (count msg)))
    (if (and (= 17 (count msg)) (= 0 (first msg)))
      (println (format "UUID %s" (-> msg ByteBuffer/wrap .getLong)))
      (println (-> msg String. .trim)))))

(defn set-id
  ([#^ZMQ$Socket socket #^long n]
    (let [rdn (Random. (System/currentTimeMillis))]
      (identify socket (str (.nextLong rdn) "-" (.nextLong rdn) n))))
  ([#^ZMQ$Socket socket]
     (set-id socket 0)))

(defn -main [ & args]
  "This is a library for you to use...if you can figure out how to install it."
  "Were you really expecting this to do something?")
