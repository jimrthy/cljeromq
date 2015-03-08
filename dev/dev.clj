(ns dev
  (:require [cljeromq.core :as mq]
            [cljeromq.curve :as enc]
            [clojure.java.io :as io]
            [clojure.inspector :as i]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clojure.test :as test]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]))

(comment
  (def system nil)

  (defn init
  "Constructs the current development system."
  []
  (alter-var-root #'system
                  (constantly (system/init))))

  (defn start
  "Starts the current development system."
  []
  (alter-var-root #'system component/start))

  (defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system
                  (fn [s] (when s (component/stop s)))))

  (defn go-go
  "Initializes the current development system and starts it running.
  Can't just call this go: that conflicts with a macro from core.async."
  []
  (println "Initializing system")
  (init)
  (println "Restarting system")
  (start))

  (defn reset []
    (println "Stopping")
    (stop)
    (println "Refreshing namespaces")
    ;; This pulls up a window (in the current thread) which leaves
    ;; emacs unusable. Get it at least not dying instantly from lein run,
    ;; then make this play nicely with the main window in a background
    ;; thread.
    ;; Which doesn't really work at all on a Mac: more impetus than
    ;; ever to get a REPL working there internally.
    ;; But I don't need it yet.
    (throw (RuntimeException. "Currently broken"))
    (refresh :after 'dev/go-go)))

;; Just because it's annoying to define this every time
(def ctx (mq/context))

(comment)
(def pusher (mq/socket! ctx :push))
(def puller (mq/socket! ctx :pull))
(comment (mq/bind! puller "inproc://dev-test")
         (mq/connect! pusher "inproc://dev-test"))
;; Make this constant for testing out python interop
(let [public "nvXGXG:{=4&>pXCRSuk<p#Hv)&4w$)g{pdk2NCJx"
      private "X/98-PJZN)oCHZ7RJ8Sx&^V>e>5ZJh34JK4KdDIv"]
  (def server-keys {:public (.getBytes public)
                    :private (.getBytes private)}))
(def client-keys (enc/new-key-pair))
(comment (enc/prepare-client-socket-for-server! puller client-keys (:public server-keys))
         (enc/make-socket-a-server! pusher (:private server-keys)))
(mq/bind! puller "tcp://127.0.0.1:2111")
(mq/connect! pusher "tcp://127.0.0.1:2111")
(def push-future (future (mq/send! pusher "Push Test" 0)
                            (println "Dev message pushed, push-future exiting")))
