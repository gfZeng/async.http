(set-env!
 :source-paths    #{"src"}
 :dependencies '[[adzerk/boot-cljs          "2.0.0"  :scope "test"]
                 [adzerk/boot-cljs-repl     "0.3.3"  :scope "test"]
                 [adzerk/boot-reload        "0.5.1"  :scope "test"]
                 [gfzeng/boot-http          "0.7.7"  :scope "test"]
                 [com.cemerick/piggieback   "0.2.1"  :scope "test"]
                 [org.clojure/tools.nrepl   "0.2.13" :scope "test"]
                 [weasel                    "0.7.0"  :scope "test"]
                 [aleph                     "0.4.3"  :scope "test"]

                 [adzerk/bootlaces          "0.1.13" :scope "test"]

                 [org.clojure/clojure        "1.9.0"         :scope "provide"]
                 [org.clojure/clojurescript  "1.9.562"       :scope "provide"]

                 [aleph "0.4.3"]
                 [com.taoensso/timbre "4.10.0"]
                 [cheshire "5.8.0"]
                 [org.clojure/core.async     "0.3.443"]
                 [com.cognitect/transit-cljs "0.8.239"]])

(require
 '[adzerk.boot-cljs      :refer [cljs]]
 '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
 '[adzerk.boot-reload    :refer [reload]]
 '[pandeiro.boot-http    :refer [serve]]
 '[adzerk.bootlaces      :refer :all])

(def +version+ "0.1.13")

(bootlaces! +version+)

(task-options!
 pom  {:project 'async.http
       :version +version+
       :url     "https://github.com/gfZeng/async.http"
       :scm     {:url "https://github.com/gfZeng/async.http"}
       :license {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask build []
  (comp (cljs)))

(deftask run []
  (comp (serve)
     (watch)
     (cljs-repl)
     (reload)
     (build)))

(deftask testing []
  (merge-env! :source-paths #{"test"})
  (task-options! cljs   {:optimizations :none}
                 serve  {:adapter 'aleph
                         :handler 'async.server/echo-handler}
                 reload {:on-jsload 'async.http-test/init})
  identity)

(deftask dev
  "Simple alias to run application in development mode"
  []
  (comp (testing)
     (run)))


