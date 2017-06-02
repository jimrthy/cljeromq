(ns cljeromq.czmq
  "Experimenting with the basic idea"
  (:import [org.zeromq.czmq Zframe Zpoller Zsock]))

(comment
  (def srv (Zsock/newServer "@inproc://abcde"))
  (assert srv)
  (def cli (Zsock/newClient ">inproc://abcde"))
  (assert cli)

  ;; Don't want to block.
  ;; => Need to use either Zloop or Zpoller
  ;; Since Zloop is totally broken, that currently means Zpoller
  (comment
    (let [req "manual test"
          f (Zframe. (.getBytes req) (count req))]
      (.send f (.-self cli) 0))
    (def rcvd  (.recv frame_factory (.-self srv)))
    rcvd
    (.strdup rcvd))

  ;; Note that this only listens to the first spot
  ;; You can add more later, as needed
  (comment (def poller (Zpoller. (long-array [(.-self srv) 0]))))
  ;; This next hoop ties into a problem the current implementation
  ;; has with variadic arguments.
  ;; The underlying constructor really accepts that, and uses
  ;; the NULL pointer to mark the end of the list.
  ;; The current JNI implementation just drops all arguments except
  ;; the first.
  ;; This means that, if you supply a parameter, this winds up
  ;; crashing the JVM.
  (def poller (Zpoller. (long-array [0])))
  (println "Poller ID:" (.-self poller))
  (assert (= 0 (.add poller (.-self srv))))

  (let [req "getting somewhere"]
    (println "a")
    (future
      (Thread/sleep 50)
      (let [f (Zframe. (.getBytes req) (count req))]
        ;; It doesn't seem to matter how I send this.
        ;; It's disappearing.
        ;; This is doubly annoying because this worked in jython.
        (assert (= 0 (.send f (.-self cli) 0)))
        (println "Sent")))
    (println "b")
    (let [handle (.Wait poller 500)]
      (println "Poller notified:" handle)
      (if (and handle
               (not= 0 handle))
        (do
          (println "Message available on srv, handle" handle)
          (assert (= handle (.-self srv)))
          (let [rcvd (Zframe/recv (.-self srv))
                body (.strdup rcvd)]
            (assert (= (String. body) req))
            (let [routing-id (.routingId rcvd)]
              (.close rcvd)
              ;; For proof-of-concept purposes, this is the interesting
              ;; part: how do we send responses back to this socket?
              routing-id)))
        (println "Frame disappeared"))))

  (.close poller)

  (let [req "getting somewhere"
        f (Zframe. (.getBytes req) (count req))]
    (assert (= 0 (.send f (.-self cli) 0))))
  (let [frame_factory (Zframe. (byte-array 0))
        poller (Zpoller. (long-array [(.-self srv) 0]))]
    (try
      (let [rcvd (.recv frame_factory (.-self srv) 4)
            body (.strdup rcvd)
            req "getting somewhere"]
        (assert (= (String. body) req))
        (let [routing-id (.routingId rcvd)]
          (.close rcvd)
          routing-id))
      (finally (.close poller))))

  )
