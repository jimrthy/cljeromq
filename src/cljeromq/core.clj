;; The file is adapted from zilch, with some other useful helper methods.
;; https://github.com/dysinger/zilch
;;
;; Really need to add its license...like all the sample ZMQ code, this
;; should be LGPL...right?


(ns cljeromq.core
  (:refer-clojure :exclude [send])
  (:import [org.zeromq ZMQ ZMQ$Context ZMQ$Socket ZMQ$Poller ZMQQueue])
  (:import (java.util Random)
           (java.nio ByteBuffer)))

(defn context [threads]
  (ZMQ/context threads))

(defmacro with-context
  [[id threads] & body]
  `(let [~id (context ~threads)]
     (try ~@body
          (finally (.term ~id)))))

(def const {
            ;; Non-blocking send/recv
            :no-block ZMQ/NOBLOCK
            :dont-wait ZMQ/DONTWAIT

            ;; More message parts are coming
            :sndmore ZMQ/SNDMORE
            :send-more ZMQ/SNDMORE

            ;;; Socket types

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
            :xrep ZMQ/XREP})

(defn socket
  [#^ZMQ$Context context type]
  (.socket context type))

(defmacro with-socket [[name context type] & body]
  `(let [~name (socket ~context ~type)]
     (try ~@body
          (finally (.close ~name)))))

(defn queue
  [#^ZMQ$Context context #^ZMQ$Socket frontend #^ZMQ$Socket backend]
  (ZMQQueue. context frontend backend))

(defn bind
  [#^ZMQ$Socket socket url]
  (doto socket
    (.bind url)))

(defn connect
  [#^ZMQ$Socket socket url]
  (doto socket
    (.connect url)))

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

(defmulti send (fn [#^ZMQ$Socket socket message & flags]
                 (class message)))

(defmethod send String
  ([#^ZMQ$Socket socket #^String message flags]
     (.send socket (.getBytes message) flags))
  ([#^ZMQ$Socket socket #^String message]
     (send socket message ZMQ/NOBLOCK)))

(defn send-partial [#^ZMQ$Socket socket message]
  "I'm seeing this as a way to send all the messages in an envelope, except the last."
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

(defn recv
  ([#^ZMQ$Socket socket flags]
     (.recv socket flags))
  ([#^ZMQ$Socket socket]
     (recv socket 0)))

(defn recv-all
  "Does it make sense to accept flags here?"
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

(defn recv-str
  ([#^ZMQ$Socket socket]
      (-> socket recv String. .trim))
  ([#^ZMQ$Socket socket flags]
     ;; This approach risks NPE:
     ;;(-> socket (recv flags) String. .trim)
     (when-let [s (recv socket flags)]
       (-> s String. .trim))))

(defn recv-all-str
  "How much, overhead gets added by just converted the received primitive Byte[] to strings?"
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
Realistically, this shouldn't be public...it opens the door
for some pretty low-level stuff."
  [socket-count]
  (ZMQ$Poller. socket-count))

(def poll-in ZMQ$Poller/POLLIN)
(def poll-out ZMQ$Poller/POLLOUT)

(defn register-in
  "Register a listening socket to poll on." 
  [#^ZMQ$Socket socket #^ZMQ$Poller poller]
  (.register poller socket poll-in))

(defn socket-poller-in
  "Get a poller attached to a seq of sockets"
  [sockets]
  (let [checker (poller (count sockets))]
    (doseq [s sockets]
      (register-in s checker))
    checker))

(defn dump
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
