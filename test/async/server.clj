(ns async.server
  (:require [aleph.http :as http]
            [manifold.stream :as s]
            [ring.util.response :as res]))



(defn echo-handler [req]
  (if (= "/ws" (:uri req))
    (let [s @(http/websocket-connection req)]
      (s/connect (s/map #(str "respond from server: " %) s) s))
    (res/resource-response (:uri req))))
