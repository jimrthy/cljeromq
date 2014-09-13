(defproject org.clojars.jimrthy/cljeromq "0.1.1-SNAPSHOT"
  :description "Clojure language wrapper using JNA to access 0MQ"
  :url "https://github.com/jimrthy/cljeromq"
  :license {:name "Lesser General Public License"
            :url "http://www.gnu.org/licenses/lgpl.html"}
  :dependencies [[byte-streams "0.1.3"]
                 [byte-transforms "0.1.3"]
                 [com.taoensso/timbre "3.1.6"]
                 [net.n01se/clojure-jna "1.0.0"]
                 [org.clojure/clojure "1.6.0"]
                 [org.zeromq/jzmq "3.1.1-SNAPSHOT"]
                 [im.chit/ribol "0.4.0"]  ; Because I need *something* to make C-style error handling palatable
                 [prismatic/schema "0.2.6"]]
  ;; Because java isn't bright enough to find this without help.
  :jvm-opts [~(str "-Djava.library.path=/usr/local/lib:" (System/getenv "LD_LIBRARY_PATH"))]
  :plugins [[lein-midje "3.0.0"]]
  :profiles {:dev {:dependencies [[midje "1.6.3"]
                                  [org.clojure/tools.namespace "0.2.5"]
                                  [org.clojure/java.classpath "0.2.2"]]
                   :source-paths ["dev"]}}
  :repl-options {:init-ns user}
  ;; Q: Why do I need this next extra repo?
  :repositories {"sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"})
