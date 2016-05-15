(defproject com.jimrthy/cljeromq "0.1.1-clj-zmq-SNAPSHOT"
  :description "Clojure language wrapper on top of the JNI layer over 0MQ"
  :url "https://github.com/jimrthy/cljeromq"
  ;; TODO: Can I switch this to EPL?
  :license {:name "Lesser General Public License"
            :url "http://www.gnu.org/licenses/lgpl.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 #_[org.zeromq/jzmq "3.1.1-SNAPSHOT"]
                 [org.zeromq/zmq-jni "0.1.1-SNAPSHOT"]
                 ;; Because I need *something* to make C-style error handling palatable
                 [im.chit/hara.event "2.3.6"]
                 [prismatic/schema "1.1.1"]]
  ;; Because java isn't bright enough to find this without help.
  :jvm-opts [~(str "-Djava.library.path=/usr/local/lib:" (System/getenv "LD_LIBRARY_PATH"))]
  ;; TODO: Ditch midje
  ;;:plugins [[lein-midje "3.0.0"]]
  :profiles {:dev {:dependencies [#_[midje "1.8.2" :exclusions [clj-tuple riddley]]
                                  [org.clojure/tools.namespace "0.2.10"]
                                  [org.clojure/java.classpath "0.2.3"]]
                   :source-paths ["dev"]}}
  :repl-options {:init-ns user})
