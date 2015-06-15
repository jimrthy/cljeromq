(ns cljeromq.constants
  (:require [schema.core :as s])
  ;; This dependency's annoying, but the alternative
  ;; is to just copy/paste its named constants.
  (:import [org.zeromq ZMQ ZMQ$Poller]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def keyword-or-seq (s/either s/Keyword [s/Keyword]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Gory details

(def const
  "TODO: This should be a function instead of a var.
Although, really, that's almost pedantic."
  {:context-option {
                    :threads 1  ; default: 1
                    :max-sockets 2  ; default: 1024
                    }
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

   :device {:forwarder ZMQ/FORWARDER  ; 2
            :queue ZMQ/QUEUE   ; 3
            :streamer ZMQ/STREAMER  ; 1
            }

   :error {:zero 156384712  ; random baseline magic # for 0mq-specific errors
           :again 11
           :fault 14
           :fsm 156384763
           :interrupted 4
           :not-socket 156384721
           :not-supported 156384713
           :terminated 156384765}

   :polling {:poll-in  ZMQ$Poller/POLLIN
             :poll-out ZMQ$Poller/POLLOUT
             :poll-err ZMQ$Poller/POLLERR}

   ;; TODO: Access these via iroh?
   :socket-options {:curve-server 47   ; ZMQ/CURVE_SERVER  ; 1 for yes, 0 for no
                    ;; the next two are for the client
                    :curve-public-key 48  ; ZMQ/CURVE_PUBLIC_KEY
                    :curve-secret-key 49  ; ZMQ/CURVE_SECRET_KEY
                    ;; The server just needs the private key
                    ;; The client's what needs this.
                    :curve-server-key 50  ; ZMQ/CURVE_SERVER_KEY
                    :identity 5
                    :receive-more 13
                    :subscribe 6
                    :unsubscribe 7
                    }

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn control->const :- s/Int
  "Convert a control keyword to a ZMQ constant"
  [key :- s/Keyword]
  (comment (println "Extracting " key))
  ((const :control) key))

(s/defn flags->const :- s/Int
  [flags :- keyword-or-seq]
  "Use in conjunction with control-const to convert a series
of/individual keyword into a logical-or'd flag to control
socket options."
  (if (seq? flags)
    (reduce bit-or (map control->const flags))
    (control->const flags)))

(s/defn sock->const :- s/Int
  "Convert a socket keyword to a ZMQ constant"
  [key :- s/Keyword]
  ((const :socket-type) key))

(defn option->const
  "Convert a keyword naming a socket option to a ZMQ constant"
  [key]
  (-> :socket-options const key))

(defn poll-opts
  [korks]
  (if (keyword? korks)
    (-> const :polling korks)
    (let [current (first korks)
          base (poll-opts current)
          remainder (next korks)]
      (if remainder
        (bit-or base
            (poll-opts (rest korks)))
        base))))

(s/defn version :- {:major s/Int
                    :minor s/Int
                    :patch s/Int}
  "Return the 0mq version number as a vector"
  []
  (let [major (ZMQ/getMajorVersion)
        minor (ZMQ/getMinorVersion)
        patch (ZMQ/getPatchVersion)]
    {:major major :minor minor :patch patch}))
