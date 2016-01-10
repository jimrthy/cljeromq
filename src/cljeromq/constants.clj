(ns cljeromq.constants
  (:require [schema.core :as s])
  (:import [org.zeromq.jni ZMQ]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def keyword-or-seq (s/either s/Keyword [s/Keyword]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Gory details

(def const
  "TODO: This should be a function instead of a var.
Although, really, that's almost pedantic."
  {:context-options {
                    :threads ZMQ/IO_THREADS  ; default: 1
                    :max-sockets ZMQ/MAX_SOCKETS  ; default: 1024
                    }
   :control {
             ;; Non-blocking send/recv
             :no-block ZMQ/NOBLOCK
             :dont-wait ZMQ/DONTWAIT

             ;; Blocking (default...doesn't seem to be an
             ;; associated named constant)
             :wait 0

             ;; More message parts are coming
             :rcvmore ZMQ/RCVMORE
             :receive-more ZMQ/RCVMORE
             :sndmore ZMQ/SNDMORE
             :send-more ZMQ/SNDMORE}

   :curve {:binary-key-length 32
           :text-key-length 40}

   :error {
           :zero 156384712  ; seemingly random baseline magic number for specific errors
           :again 11
           :fault 14
           :fsm 156384763
           :interrupted 4
           :not-socket 156384721
           :not-supported 156384713
           :terminated 156384765}

   :polling {:poll-in  ZMQ/POLLIN
             :poll-out ZMQ/POLLOUT
             :poll-err ZMQ/POLLERR}

   :socket-options {;; the next two are for the client
                    :curve-public-key ZMQ/CURVE_PUBLICKEY     ; 48
                    :curve-private-key ZMQ/CURVE_SECRETKEY     ; 49
                    :curve-secret-key ZMQ/CURVE_SECRETKEY     ; 49
                    :curve-server ZMQ/CURVE_SERVER            ; 47 -- set to 1 for yes, 0 for no
                    ;; The server just needs the private key
                    ;; The client's what needs this.
                    :curve-server-key ZMQ/CURVE_SERVERKEY     ; 50
                    :identity ZMQ/IDENTITY                    ; 5
                    :last-end-point ZMQ/LAST_ENDPOINT
                    :linger ZMQ/LINGER
                    :receive-more ZMQ/RCVMORE                 ; 13
                    :receive-time-out ZMQ/RCVTIMEO
                    :router-mandatory ZMQ/ROUTER_MANDATORY
                    :subscribe ZMQ/SUBSCRIBE                  ; 6
                    :unsubscribe ZMQ/UNSUBSCRIBE              ; 7
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

(s/defn section-flag->const :- s/Int
  "Base conversion function to build on below"
  [section :- s/Keyword
   key :- s/Keyword]
  (-> section const key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(s/defn control->const :- s/Int
  "Convert a control keyword to a ZMQ constant"
  [key :- s/Keyword]
  (comment (println "Extracting " key))
  (section-flag->const :control key))

(s/defn error->const :- s/Int
  [flag :- s/Keyword]
  (section-flag->const :error flag))

(s/defn flags->const :- s/Int
  "Use in conjunction with control-const to convert a series
of/individual keyword into a logical-or'd flag to control
socket options."
  [flags :- keyword-or-seq]
  (if (integer? flags)
    flags
    (if-let [result (if (or (seq? flags)
                            (vector? flags))
                      (reduce (fn [acc x]
                                (if x
                                  (bit-or acc x)
                                  acc))
                              0
                              (map control->const flags))
                      (control->const flags))]
      result
      ;; Note that NULL flags is totally legit...
      ;; but we really do want 0 rather than nil
      0)))

(s/defn option->const :- s/Int
  "Convert a keyword naming a socket option to a ZMQ constant"
  [name :- s/Keyword]
  (section-flag->const :socket-options name))

(defn poll-opts
  [korks]
  (if (keyword? korks)
    (section-flag->const :polling korks)
    (let [current (first korks)
          base (poll-opts current)
          remainder (next korks)]
      (if remainder
        (bit-or base
            (poll-opts (rest korks)))
        base))))

(s/defn sock->const :- s/Int
  "Convert a socket keyword to a ZMQ constant"
  [key :- s/Keyword]
  (section-flag->const :socket-type key))

(s/defn version :- {:major s/Int
                    :minor s/Int
                    :patch s/Int}
  "Return the 0mq version number as a vector"
  []
  ;; No longer have these available
  (comment (let [major (ZMQ/getMajorVersion)
           minor (ZMQ/getMinorVersion)
           patch (ZMQ/getPatchVersion)]
       {:major major :minor minor :patch patch}))
  ;; FIXME: This is obviously wrong
  ;; TODO: Fix it
  ;; (it should be really low-hanging fruit. Issue #1)
  {:major (ZMQ/version)
   :minor 0
   :patch 0})
