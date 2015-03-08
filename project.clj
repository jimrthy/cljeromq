(defproject org.clojars.jimrthy/cljeromq "0.1.0-SNAPSHOT"
  :description "Clojure language wrapper on top of the JNI layer over 0MQ"
  :url "https://github.com/jimrthy/cljeromq"
  ;; TODO: Can I switch this to EPL?
  :license {:name "Lesser General Public License"
            :url "http://www.gnu.org/licenses/lgpl.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.zeromq/jzmq "3.1.1-SNAPSHOT"]
                 [im.chit/ribol "0.4.0"]  ; Because I need *something* to make C-style error handling palatable
                 [prismatic/schema "0.4.0"]]
  ;; Because java isn't bright enough to find this without help.
  :jvm-opts [~(str "-Djava.library.path=/usr/local/lib:" (System/getenv "LD_LIBRARY_PATH"))]
  :plugins [[lein-midje "3.0.0"]]
  ;; TODO: This isn't fancy enough to justify using midje
  :profiles {:dev {:dependencies [[midje "1.6.3"]
                                  [org.clojure/tools.namespace "0.2.10"]
                                  [org.clojure/java.classpath "0.2.2"]]
                   :source-paths ["dev"]}}
  :repl-options {:init-ns user})
