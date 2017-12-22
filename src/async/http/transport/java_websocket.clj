(ns async.http.transport.java-websocket
  (:require [async.http :refer [websocket* default-exception-handler]]
            [clojure.core.async :as a
             :refer [go go-loop <! >! chan timeout <!!]]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.string :as str]
            [taoensso.timbre :refer [error warn]])
  (:import [org.java_websocket.client WebSocketClient]
           [org.java_websocket.handshake ServerHandshake]))


(defmethod websocket* (-> (name (ns-name *ns*))
                          (str/split #"\.")
                          (last)
                          (keyword))
  [uri duplex opts]
  (let [mdata  (meta duplex)
        sink   (:async/sink mdata)
        mult   (:async/mult mdata)
        source (a/tap mult (chan))
        events (:ws/events mdata)
        dexh   (fn [e]
                 (default-exception-handler e)
                 (a/close! duplex))

        ^WebSocketClient ws
        (proxy [WebSocketClient] [(java.net.URI. uri)]
          (onOpen [^ServerHandshake handshake]
            (reset! (:async/ws mdata) this)
            (go-loop []
              (when-some [x (<! source)]
                (try
                  (.send this x)
                  (catch Exception e
                    (error e)))
                (recur)))
            (a/put! events [:open this]))
          (onMessage [^String s]
            (a/>!! sink s))
          (onClose [_ ^String s _]
            (a/close! source)
            (a/put! events [:close this])
            (when-not (or (impl/closed? sink) (impl/closed? source))
              (warn "websocket unexpected close:" uri s)
              (websocket* uri duplex opts)))
          (onError [^Exception e]
            (try
              ((:exception-handler opts dexh) e)
              (catch Exception e
                (dexh e)))))]
    (.connect ws)
    duplex))

