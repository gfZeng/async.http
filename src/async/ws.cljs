(ns async.ws
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :as a :refer [>! <! put! take! chan]]
            [cljs.core.async.impl.protocols :as impl]
            [cljs.core.async.impl.buffers   :as buffers]
            [clojure.string :as str]
            [cognitect.transit :as t]))

(defn jsonstr->edn [x]
  (-> x js/JSON.parse (js->clj :keywordize-keys true)))

(defn end->jsonstr [x]
  (-> x clj->js js/JSON.stringify))

(def json-format {:read  jsonstr->edn
                  :write end->jsonstr})

(def identity-format {:read  identity
                      :write identity})

(def transit-format
  (let [rdr (t/reader :json)
        wrt (t/writer :json)]
    {:read  #(t/read rdr %)
     :write #(t/writer wrt %)}))

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
        not-found))))

(defn chan-ws [ch] (-> ch meta :ws))

(def close! impl/close!)

(defn remove-listen!
  ([duplex type]
   (alter-meta! duplex update :listeners dissoc type))
  ([duplex type k]
   (alter-meta! duplex update-in [:listeners type] dissoc k)))

(defn listen!
  ([duplex]
   (listen! duplex (chan)))
  ([duplex ch]
   (let [k        (gensym)
         ch       (if (fn? ch)
                    (a/promise-chan (filter ch))
                    ch)
         buf      (.-buf ch)
         promise? (instance? buffers/PromiseBuffer buf)]
     (listen! duplex :message k
              (fn [msg]
                (when (or (not (put! ch msg))
                          (and promise?
                               (pos? (count buf))))
                  (remove-listen! duplex :message k))))
     ch))
  ([duplex type key handler]
   (alter-meta! duplex assoc-in [:listeners type key] handler)))

(defn run-listeners [duplex type msg]
  (doseq [[_ lst] (-> duplex meta :listeners (get type))]
    (lst msg)))

(defn websocket
  ([spec] (websocket spec (chan) (chan)))
  ([spec sink source]
   (websocket spec (duplex sink source)))
  ([{:as   spec
     :keys [uri binary-type auto-reconnect?]
     :or   {uri spec}}
    duplex]
   (when-let [ws (-> duplex meta :ws)] (.close ws))
   (let [ws                   (js/WebSocket. uri)
         {:keys [read write]} (:format spec identity-format)
         source               (:source duplex)
         sink                 (:sink   duplex)]
     (alter-meta! duplex assoc :ws ws)
     (doto ws
       (aset "binaryType" binary-type)
       (aset "onopen" (fn [event]
                        (run-listeners duplex :open event)
                        (go-loop []
                          (when (= (.-readyState ws) js/WebSocket.OPEN)
                            (if-let [x (<! sink)]
                              (do (.send ws (write x))
                                  (recur))
                              (.close ws))))))
       (aset "onerror" (fn [event]
                         (run-listeners duplex :error event)
                         (js/console.error ws "got erorr" event)))
       (aset "onclose" (fn [event]
                         (js/console.warn
                          ws "unexpected close with code:" (.-code event)
                          "reason:" (.-reason event))
                         (if-not auto-reconnect?
                           (a/close! duplex)
                           (if (impl/closed? duplex)
                             (run-listeners duplex :close event)
                             (websocket spec duplex)))))
       (aset "onmessage" (fn [event]
                           (let [data (read (.-data event))]
                             (run-listeners duplex :message data)
                             (when-not (put! source data)
                               (.close ws))))))
     duplex)))
