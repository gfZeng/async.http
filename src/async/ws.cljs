(ns async.ws
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :as a :refer [>! <! put! take! chan]]
            [cljs.core.async.impl.protocols :as impl]
            [clojure.string :as str]))

(defn duplex [up down]
  (reify
    impl/ReadPort
    (take! [_ fn-handler]
      (impl/take! down fn-handler))
    impl/WritePort
    (put! [_ val fn-handler]
      (impl/put! up val fn-handler))
    impl/Channel
    (close! [_]
      (impl/close! up)
      (impl/close! down))
    (closed? [_]
      (and (impl/closed? up)
           (impl/closed? down)))

    IMeta
    (-meta [this] (.-meta this))
    ILookup
    (-lookup [this k]
      (-lookup this k nil))
    (-lookup [_ k not-found]
      (case k
        (:up :sink)     up
        (:down :source) down
        nil))))

(defn chan-ws [ch] (-> ch meta :ws))

(def close! impl/close!)

(defn websocket
  ([spec] (websocket spec (chan) (chan)))
  ([spec sink source] (websocket spec (duplex sink source)))
  ([{:as   spec
     :keys [uri binary-type auto-reconnect?]
     :or   {uri spec}}
    {:as   duplex
     :keys [source sink]}]
   (let [ws (js/WebSocket. uri)]
     (doto ws
       (aset "binaryType" binary-type)
       (aset "onopen" (fn [_]
                        (go-loop []
                          (when (= (.-readyState ws) js/WebSocket.OPEN)
                            (if-let [x (<! sink)]
                              (do (.send ws x)
                                  (recur))
                              (.close ws))))))
       (aset "onerror" (fn [event]
                         (js/console.error ws "got erorr" event)))
       (aset "onclose" (fn [event]
                         (if-not auto-reconnect?
                           (a/close! duplex)
                           (when-not (impl/closed? duplex)
                             (js/console.warn ws "unexpected close with code:" (.-code event)
                                              "reason:" (.-reason event))
                             (websocket spec duplex)))))
       (aset "onmessage" (fn [event]
                           (when-not (put! source (.-data event))
                             (.close ws)))))
     (alter-meta! duplex assoc :ws ws)
     duplex)))

;; transforms
(defn jsonstr->edn [x]
  (-> x js/JSON.parse (js->clj :keywordize-keys true)))

(defn end->jsonstr [x]
  (-> x clj->js js/JSON.stringify))
