(defproject org.clojars.jimrthy/cljeromq "0.1.1-SNAPSHOT"
  :description "Clojure language wrapper using JNA to access 0MQ"
  :url "https://github.com/jimrthy/cljeromq"
  :license {:name "Lesser General Public License"
            :url "http://www.gnu.org/licenses/lgpl.html"}
  :dependencies [[byte-transforms "0.1.2"]
                 [com.taoensso/timbre "3.1.6"]
                 [net.n01se/clojure-jna "1.0.0"]
                 [org.clojure/clojure "1.6.0"]]
  :plugins [[lein-midje "3.0.0"]]
  :profiles {:dev {:dependencies [[midje "1.6.0"]
                                  [org.clojure/tools.namespace "0.2.4"]
                                  [org.clojure/java.classpath "0.2.2"]]
                   :source-paths ["dev"]}}
  :repl-options {:init-ns user}
  :repositories {;"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"
                 "sonatype-nexus-snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
                 })
