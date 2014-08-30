(ns cljeromq.constants
  ;; TODO: Probably shouldn't be relying on an external logger.
  ;; Then again, the alternatives seem worse.
  (:require [taoensso.timbre :as timbre])
  ;; TODO: Get rid of this dependency
  (:import [org.zeromq ZMQ]))

(def const {:context-option {:threads 1  ; default: 1
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

(defn control->const
  "Convert a control keyword to a ZMQ constant"
  [key]
  (timbre/trace "Extracting " key)
  ((const :control) key))

(defn flags->const ^long [flags]
  "Use in conjunction with control-const to convert a series
of/individual keyword into a logical-or'd flag to control
socket options."
  (if (seq? flags)
    (reduce bit-or (map control->const flags))
    (control->const flags)))

(defn sock->const
  "Convert a socket keyword to a ZMQ constant"
  [key]
  ((const :socket-type) key))

(defn option->const
  "Convert a keyword naming a socket option to a ZMQ constant"
  [key]
  (-> :socket-options const key))

(defn version
  "Return the 0mq version number as a vector"
  []
  (let [major (ZMQ/getMajorVersion)
        minor (ZMQ/getMinorVersion)
        patch (ZMQ/getPatchVersion)]
    [major minor patch]))
