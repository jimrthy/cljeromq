;; The file is adapted from zilch, with some other useful helper methods.
;; https://github.com/dysinger/zilch


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

;; Non-blocking send/recv
(def no-block ZMQ/NOBLOCK)
(def dont-wait ZMQ/DONTWAIT)
;; More message parts are coming
(def sndmore ZMQ/SNDMORE)
(def send-more ZMQ/SNDMORE)

;;; Socket types
;; Request/Reply
(def req ZMQ/REQ)
(def rep ZMQ/REP)
;; Publish/Subscribe
(def pub ZMQ/PUB)
(def sub ZMQ/SUB)
;; Extended Publish/Subscribe
(def x-pub ZMQ/XPUB)
(def x-sub ZMQ/XSUB)
;; Push/Pull
(def push ZMQ/PUSH)
(def pull ZMQ/PULL)
;; Internal 1:1
(def pair ZMQ/PAIR)

;; Router/Dealer

;; Creates/consumes request-reply routing envelopes.
;; Lets you route messages to specific connections if you
;; know their identities.
(def router ZMQ/ROUTER)
;; Combined ventilator/sink.
;; Does load balancing on output and fair-queuing on input.
;; Can shuffle messages out to N nodes then shuffle the replies back.
;; Raw bidirectional async pattern.
(def dealer ZMQ/DEALER)
;; Obsolete names for Router/Dealer
(def xreq ZMQ/XREQ)
(def xrep ZMQ/XREP)

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

(defn send-more [#^ZMQ$Socket socket message]
  (send socket message send-more))

(defn send-all [#^ZMQ$Socket socket messages]
  "At this point, I'm basically envisioning the usage here as something like HTTP.
Where the headers back and forth carry more data than the messages.
This approach is a total cop-out.
There's no way it's appropriate here.
I just need to get something written for my
\"get the rope thrown across the bridge\" approach.
It totally falls apart when I'm just trying to send a string."
  (doseq [m messages]
    (send-more socket m))
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
  [#^ZMQ$Socket socket flags]
  (loop [acc []]
    (let [msg (recv socket flags)
          result (conj acc msg)]
      (if (.hasReceiveMore socket)
        (recur result)
        result))))

(defn recv-str
  ([#^ZMQ$Socket socket]
      (-> socket recv String. .trim))
  ([#^ZMQ$Socket socket flags]
     ;; This approach risks NPE:
     ;;(-> socket (recv flags) String. .trim)
     (when-let [s (recv socket flags)]
       (-> s String. .trim))))

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
