(ns async.ws-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [async.ws    :as ws]
            [clojure.core.async :as a :refer [take! put! <! timeout]]))


(defn init
  ([] (init false))
  ([reconnect?]
   ;; return duplex channel
   (let [ch  (ws/websocket {:uri             "ws://localhost:3000/ws"
                            :auto-reconnect? reconnect?})
         pch (ws/listen! ch #(re-find #"1" %))]
     ;; write to channel => ws.send(msg)
     (go
       (println "let go.....")
       (println "got from promise pch" (<! pch)))
     (go-loop [i 0]
       (<! (timeout 500))
       (when (>! ch (str "<div>line " i "</div>"))
         (recur (inc i))))

     ;; read from channel => ws.onmessage(callback)
     (go-loop []
       (if-let [x (<! ch)]
         (do (js/document.write x)
             (recur))
         (do (js/document.write "websocket closed expected<br/>")
             (when-not reconnect?
               (js/document.write "<br/><br/>try again with auto-reconnect<br/>")
               (init true)))))
     (go
       ;; if `:auto-reconnect?` is `false`, the `ch` will be closed
       (<! (timeout 5000))
       (js/document.write "let us close websocket UNEXPECTED ...<br/>")
       (.close (ws/chan-ws ch))
       (<! (timeout 5000))
       (js/document.write "let us close websocket UNEXPECTED second time ...<br/>")
       (.close (ws/chan-ws ch))
       (<! (timeout 10000))
       (js/document.write "let us close websocket EXPECTED ...<br/>")
       (a/close! ch)))))
