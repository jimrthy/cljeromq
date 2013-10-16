(defproject org.clojars.jimrthy/cljeromq "0.1.0-SNAPSHOT"
  :description "Clojure language wrapper on top of the JNI layer over 0MQ"
  :url "https://github.com/jimrthy/cljeromq"
  :license {:name "Lesser General Public License"
            :url "http://www.gnu.org/licenses/lgpl.html"}
  :dependencies [[byte-transforms "0.1.0"]
                 [com.taoensso/timbre "2.6.2"]
                 [org.clojure/clojure "1.5.1"]
                 ;; Switching to jeromq for now, at least until
                 ;; zeromq 4 is released.
                 ;; FIXME: That's out now. Use it!!
                 ;;[org.zeromq/jzmq "2.2.0"]
                 [org.jeromq/jeromq "0.3.0-SNAPSHOT"]
                 [org.zeromq/cljzmq "0.1.1" :exclusions [org.zeromq/jzmq]]]
  :plugins [[lein-midje "3.0.0"]]
  :profiles {:dev {:dependencies [[midje "1.5.1"]]}}
  ;; TODO: Want to set *warn-on-reflection* to true, at least for unit tests.
  ;; Probably also for general dev.
  :repl-options {:init-ns user}
  :repositories {;"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"
                 "sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
                 })
