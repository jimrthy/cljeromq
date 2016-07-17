(ns cljeromq.common
  (:import [org.zeromq
            ZMQ$Context
            ZMQ$Poller
            ZMQ$Socket]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def byte-array-type (Class/forName "[B"))

;;; Aliases to avoid the ugly java-style nested class names
(def Context ZMQ$Context)
(def Poller ZMQ$Poller)
(def Socket ZMQ$Socket)
