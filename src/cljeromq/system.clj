(ns cljeromq.system
 (:require [com.stuartsierra.component :as component]
           [cljeromq.core :as mq]
           [ribol.core :refer (raise)]
           [schema.core :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(defrecord Context [ctx thread-count]
  component/Lifecycle
  (start
    [this]
    (assoc this :ctx (mq/context thread-count)))
  (stop
    [this]
    (when ctx
      (mq/terminate! ctx)
      (assoc this :ctx nil))))

(defn new-base-system-map
  "The skeleton that everything else is built around"
  []
  ;; Do something like
  (comment (component/system-map
            :comms (worker/ctor)
            :configuration (cfg/ctor log-file-name)
            :coordinator (coordinator/ctor {})
            :done {:flag (promise)}
            :remote (remote/ctor (core-async/chan))
            :routes (routes/ctor {})
            :security-manager (security-manager/ctor)))
  (component/system-map :context (map->Context {:thread-count 4})))

(defn new-base-dependency-map
  "Dependency tree used to decide start/stop order"
  [system-map]
  ;; Do something like
  (comment
    {:comms [:configuration :routes]
     :coordinator {:comms :comms
                   :remote-sender :remote}
     ;; Odds are, this is going to need access to
     ;; the :async Component as well.
     ;; For now, that's YAGNI
     :routes [:security-manager]
     :security-manager [:configuration]})
  {})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn init
  [config-options]
  (let [s-map (new-base-system-map config-options)
        d-map (new-base-dependency-map s-map)]
    (with-meta
      (component/system-using s-map d-map)
      {:dependencies d-map})))
