(ns cljeromq.constants
  (:require [clojure.spec :as s])
  ;; This dependency's annoying, but the alternative
  ;; is to just copy/paste its named constants.
  (:import [org.zeromq ZMQ ZMQ$Poller]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Specs
(s/def ::korks (s/or :single keyword? :multiple (s/coll-of keyword?)))

(s/def ::major integer?)
(s/def ::minor integer?)
(s/def ::patch integer?)
(s/def ::version-map (s/keys :req [::major ::minor ::patch]))

;; Maintaining this seems like a thankless job.
;; TODO: Come up with a way to automate it.
(s/def ::control-keyword-initial #{:no-block, :dont-wait, :wait,
                                   :sndmore, :send-more,
                                   :poll-in, :poll-out, :poll-err})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Gory details

(def const
  "TODO: This should be a function instead of a var.
Although, really, that's almost pedantic.

Q: Is it worth the effort to namespace these?"
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
             :send-more ZMQ/SNDMORE

             :poll-in ZMQ$Poller/POLLIN
             :poll-out ZMQ$Poller/POLLOUT
             :poll-err ZMQ$Poller/POLLERR}

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

   ;; These should be public now
   ;; TODO: Verify and switch
   :socket-options {:curve-server 47   ; ZMQ/CURVE_SERVER  ; 1 for yes, 0 for no
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

(s/def ::control-keyword (-> const :control keys set))
(s/def ::error-keyword (-> const :error keys set))
(s/def ::poller-flags (-> const :polling keys set))
(s/def ::socket-options (-> const :socket-options keys set))
(s/def ::socket-types (-> const :socket-type keys set))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/fdef control->const
        :args (s/cat :key ::control-keyword)
        ;; Note that this really returns a value representing a bit flag
        :ret integer?)
(defn control->const
  "Convert a control keyword to a ZMQ constant"
  [key]
  (comment (println "Extracting " key))
  ((const :control) key))

(s/fdef error->const
        :args (s/cat :which ::error-keyword)
        :ret integer?)
(defn error->const
  [which]
  ((:error const) which))

(s/fdef flags->const
        ;; This is really ::korks where each keyword is a control key.
        ;; TODO: Spec that out
        :args (s/cat :flags ::korks)
        :ret integer?)
(defn flags->const
  [flags]
  "Use in conjunction with control-const to convert a series
of/individual keyword into a logical-or'd flag to control
socket options."
  (or (if (sequential? flags)
        (reduce bit-or
                0
                (map control->const flags))
        (control->const flags))
      0))

(s/fdef sock->const
        :args (s/cat :key ::socket-types)
        :ret integer?)
(defn sock->const
  "Convert a socket keyword to a ZMQ constant"
  [key]
  (-> const :socket-type key))

(s/fdef option->const
        :args (s/cat :key ::socket-options))
(defn option->const
  "Convert a keyword naming a socket option to a ZMQ constant"
  [key]
  (-> :socket-options const key))

(s/fdef poll-opts
        ;; This is another that's really a composite of specific keyword types
        :args (s/cat :korks ::korks)
        :ret integer?)
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

(s/fdef version
        :ret ::version-map)
(defn version
  "Return the 0mq version number as a vector"
  []
  (let [major (ZMQ/getMajorVersion)
        minor (ZMQ/getMinorVersion)
        patch (ZMQ/getPatchVersion)]
    {::major major ::minor minor ::patch patch}))
