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

                 [org.clojure/clojure        "1.9.0-alpha16" :scope "provide"]
                 [org.clojure/clojurescript  "1.9.562"       :scope "provide"]
                 [org.clojure/core.async     "0.3.443"       :scope "provide"]])

(require
 '[adzerk.boot-cljs      :refer [cljs]]
 '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
 '[adzerk.boot-reload    :refer [reload]]
 '[pandeiro.boot-http    :refer [serve]])

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
                 reload {:on-jsload 'async.ws-test/init})
  identity)

(deftask dev
  "Simple alias to run application in development mode"
  []
  (comp (testing)
     (run)))


