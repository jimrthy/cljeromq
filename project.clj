(defproject com.jimrthy/cljeromq "0.1.0-SNAPSHOT"
  :description "Clojure language wrapper on top of the JNI layer over 0MQ"
  :url "https://github.com/jimrthy/cljeromq"
  ;; Q: Can I switch this to EPL?
  :license {:name "Lesser General Public License"
            :url "http://www.gnu.org/licenses/lgpl.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha13"]
                 [org.clojure/test.check "0.9.0"]
                 ;; Note that this very specifically does not get the associated
                 ;; native library (i.e. the JNI layer)
                 ;; Using this version to just use latest local copy because
                 ;; I don't want to try to cope with getting 3.1.0
                 ;; installed and working (it won't build on recent versions
                 ;; of Debian/Ubuntu)
                 [org.zeromq/jzmq "3.1.1-SNAPSHOT"]]
  ;; Because java isn't bright enough to find this without help.
  :jvm-opts [~(str "-Djava.library.path=/usr/local/lib:" (System/getenv "LD_LIBRARY_PATH"))]
  :plugins []
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.10"]
                                  [org.clojure/java.classpath "0.2.3"]]
                   :source-paths ["dev"]}}
  :repl-options {:init-ns user})
