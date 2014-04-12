(ns cljeromq.constants
  ;; TODO: Probably shouldn't be relying on an external logger.
  ;; Then again, the alternatives seem worse.
  (:require [taoensso.timbre :as timbre]))

;;; Basically just copy/pasting a bunch ofmagic numbers, then
;;; putting them into dictionaries to make them a little easier
;;; (IMO) to cope with

(def const {
            ;;; Error codes
            ;;; TODO: There aren't really that many of these. Might as well copy/paste them too

            ;;; Context Options
            :context-option {:io-threads 1
                             :max-sockets 2}
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

            :socket-options {:affinity 4
                             :identity 5
                             :subscribe 6
                             :unsubscribe 7
                             :rate 8
                             :recovery-interval 9
                             :send-buffer 11
                             :receive-buffer 12
                             :receive-more 13
                             :fd 14   ;;; ???
                             :events 15
                             :type 16
                             :linger 17
                             :reconnect-interval 18
                             :backlog 19
                             :reconnect-interval-max 21
                             :max-msg-size 22
                             :send-hwm 23
                             :rcv-hwm 24
                             :multicast-hops 25
                             :receive-time-out 27
                             :send-time-out 28                             
                             :last-endpoint 32
                             :router-mandatory 33
                             :tcp-keepalive 34
                             :tcp-keepalive-count 35
                             :tcp-keepalive-idle 36
                             :tcp-keepalive-interval 37
                             :tcp-accept-filter 38
                             :immediate 39
                             :xpub-verbose 40
                             :router-raw 41
                             :ipv6 42
                             :mechanism 43
                             :plain-server 44
                             :plain-username 45
                             :plain-password 46
                             :curve-server 47  ; 1 for yes, 0 for no
                             ;; the next two are for the client
                             :curve-public-key 48
                             :curve-secret-key 49
                             ;; According to docs, server just needs private key
                             :curve-server-key 50
                             :probe-router 51
                             :req-correlate 52
                             :req-relaxed 53
                             :conflate 54
                             :zap-domain 55
                             :router-handover 56
                             :tos 57  ; ????
                             :ipc-filter-pid 58
                             :ipc-filter-uid 59
                             :ipc-filter-gid 60
                             :connect-rid 61

                             ;; deprecated
                             :ipv4-only 31
                             }

            :control {
                      ;; Message options
                      :more 1
                      :srcfd 2  ; ???

                      ;; Blocking (default...doesn't seem to be an 
                      ;; associated named constant)
                      :wait 0
                                
                      ;; Non-blocking send/recv
                      :dont-wait 1
                      :no-block 1   ; deprecated

                      ;; More message parts are coming
                      :sndmore 2
                      :send-more 2                      
                      }
            :security {:null 0
                       :plain 1
                       :curve 2}
            
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
