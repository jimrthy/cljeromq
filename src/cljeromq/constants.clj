(ns cljeromq.constants
  ;; TODO: Probably shouldn't be relying on an external logger.
  ;; Then again, the alternatives seem worse.
  (:require [taoensso.timbre :as timbre])
  ;; TODO: Get rid of this dependency
  (:import [org.zeromq ZMQ]))

;;; Basically just copy/pasting a bunch ofmagic numbers, then
;;; putting them into dictionaries to make them a little easier
;;; (IMO) to cope with

(def const {:context-option {:threads 1  ; default: 1
                             :max-sockets 2  ; default: 1024
                             }
            :control {
                      ;; Message options
                      :more 1
                      :srcfd 2  ; ???

                      ;; Non-blocking send/recv
                      :dont-wait 1
                      :no-block 1   ; deprecated

                      ;; Blocking (default...doesn't seem to be an 
                      ;; associated named constant)
                      :wait 0
                                
                      ;; More message parts are coming
                      :sndmore 2
                      :send-more 2}

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
            
            :security {:null 0
                       :plain 1
                       :curve 2}
            
            ;; TODO: Access these via iroh?
            :socket-options {:affinity 4
                             :backlog 19
                             :conflate 54
                             :connect-rid 61
                             ;; the next two are for the client
                             :curve-public-key 48  ; ZMQ/CURVE_PUBLIC_KEY
                             :curve-secret-key 49  ; ZMQ/CURVE_SECRET_KEY
                             :curve-server 47  ; 1 for yes, 0 for no
                             ;; The server just needs the private key
                             ;; The client's what needs this.
                             :curve-server-key 50  ; ZMQ/CURVE_SERVER_KEY
                             :events 15
                             :fd 14   ;;; ???
                             :identity 5
                             :immediate 39
                             :ipc-filter-gid 60
                             :ipc-filter-pid 58
                             :ipc-filter-uid 59
                             :ipv6 42
                             :last-endpoint 32
                             :linger 17
                             :max-msg-size 22
                             :mechanism 43
                             :multicast-hops 25
                             :plain-server 44
                             :plain-username 45
                             :plain-password 46
                             :probe-router 51
                             :rate 8
                             :receive-buffer 12
                             :rcv-hwm 24
                             :receive-more 13
                             :receive-time-out 27
                             :reconnect-interval 18
                             :reconnect-interval-max 21
                             :recovery-interval 9
                             :req-correlate 52
                             :req-relaxed 53
                             :router-handover 56
                             :router-mandatory 33
                             :router-raw 41
                             :send-buffer 11
                             :send-hwm 23
                             :send-time-out 28                             
                             :subscribe 6
                             :tcp-accept-filter 38
                             :tcp-keepalive 34
                             :tcp-keepalive-count 35
                             :tcp-keepalive-idle 36
                             :tcp-keepalive-interval 37
                             :tos 57  ; ????
                             :type 16
                             :unsubscribe 7
                             :xpub-verbose 40
                             :zap-domain 55

                             ;; deprecated
                             :ipv4-only 31
                             }

            ;;; Socket types
            :socket-type {
                          ;; Internal 1:1
                          :pair 0

                          ;; Publish/Subscribe
                          :pub 1
                          :sub 2
                          
                          ;; Request/Reply
                          :req 3
                          :rep 4
                                    
                          ;; Router/Dealer

                          ;; Combined ventilator/sink.
                          ;; Does load balancing on output and fair-queuing on input.
                          ;; Can shuffle messages out to N nodes then shuffle the replies back.
                          ;; Raw bidirectional async pattern.
                          :dealer 5

                          ;; Creates/consumes request-reply routing envelopes.
                          ;; Lets you route messages to specific connections if you
                          ;; know their identities.
                          :router 6

                          ;; Extended Publish/Subscribe
                          ;; Push/Pull
                          :pull 7
                          :push 8
                                    
                          :x-pub 9
                          :x-sub 10

                          :stream 11

                          ;; Obsolete names for Router/Dealer
                          :xreq 5
                          :xrep 6}

            ;; Named magical numbers/strings
            ;; (OK, I made these up)
            :flag
            {:edn "clojure/edn"}

            ;; Note sure what these are actually for.
            ;; Including for the sake of completeness
            :transport-events {:connected 1
                               :delayed 2
                               :retried 4
                               :listening 8
                               :bind-failed 16
                               :accepted 32
                               :accept-failed 64
                               :closed 128
                               :close-failed 256
                               :disconnected 512
                               :monitor-stopped 1024}
            :multiplex {:poll-in 1
                        :poll-out 2
                        :poll-err 4}})

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
