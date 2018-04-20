(ns async.http
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [async.http :refer [defhttp]])
  (:require [cljs.core.async :as a :refer [>! <! put! take! chan]]
            [cljs.core.async.impl.protocols :as impl]
            [cljs.core.async.impl.buffers   :as buffers]
            [cljs.reader :refer [read-string]]
            [clojure.string :as str]
            [cognitect.transit :as t]
            ["https" :as https]
            ["http" :as http]))


(defn default-exception-handler
  [e]
  (js/console.error e "uncaught exception"))

(defn jsonstr->edn [x]
  (-> x js/JSON.parse (js->clj :keywordize-keys true)))

(defn edn->jsonstr [x]
  (-> x clj->js js/JSON.stringify))

(def NODE? (js/eval "typeof window === 'undefined'"))

(when (and NODE? (not js/global.WebSocket))
  (set! js/global.WebSocket
        (try (js/require "ws") (catch js/Error e))))

(when (and NODE? (not js/global.fetch))
  (set! js/global.fetch
        (try (js/require "node-fetch") (catch js/Error e))))

(def transit-reader (t/reader :json))
(def transit-writer (t/writer :json))
(defn encode-transit [x]
  (t/write transit-writer x))
(defn decode-transit [x]
  (t/read transit-reader x))

(defn hybrid [& {:as hy :keys [read write pub mult channels]}]
  (let [chs (cond->> channels
              read  (cons read)
              write (cons write)
              true  (seq))]
    (cond-> hy
      chs   (specify! impl/Channel
              (close! [_] (run! impl/close! chs))
              (closed? [_] (every? impl/closed? chs)))
      read  (specify! impl/ReadPort
              (take! [_ fn-handler]
                (impl/take! read fn-handler)))
      write (specify! impl/WritePort
              (put! [_ val fn-handler]
                (impl/put! write val fn-handler)))
      pub   (specify! a/Pub
              (sub* [_ v ch close?]
                (a/sub* pub v ch close?))
              (unsub* [_ v ch]
                (a/unsub* pub v ch))
              (unsub-all*
                ([_] (a/unsub-all* pub))
                ([_ v] (a/unsub-all* pub v))))
      mult  (specify! a/Mult
              (tap* [_ ch close?]
                (a/tap* mult ch close?))
              (untap* [_ ch]
                (a/untap* mult ch))
              (untap-all* [_]
                (a/untap-all* mult))))))

(defn websocket* [uri duplex opts]
  (let [ws     (js/WebSocket. uri)
        mdata  (meta duplex)
        sink   (:async/sink mdata)
        mult   (:async/mult mdata)
        source (a/tap mult (chan))
        events (:ws/events mdata)
        dexh   (fn [e]
                 (default-exception-handler e)
                 (a/close! duplex))]
    (doto ws
      (aset "binaryType" (:binaryType opts))
      (aset "onopen" (fn [event]
                       (reset! (:async/ws mdata) ws)
                       (go-loop []
                         (when-some [x (<! source)]
                           (try
                             (.send ws x)
                             (catch js/Error e
                               (js/console.error e)))
                           (recur)))))
      (aset "onerror" (fn [event]
                        (try
                          ((:exception-handler opts dexh) event)
                          (catch js/Error e
                            (dexh e)))))
      (aset "onclose" (fn [event]
                        (a/close! source)
                        (when-not (or (impl/closed? sink) (impl/closed? source))
                          (js/console.warn
                           uri "unexpected close with code:" (.-code event)
                           "reason:" (.-reason event))
                          (websocket* uri duplex opts))))
      (aset "onmessage" (fn [event]
                          (a/put! sink (.-data event)))))
    duplex))

(defn websocket [uri & {:keys [mult? topic-fn] :as spec}]
  (let [sink   (or (:read/ch spec)  (chan 10))
        source (or (:write/ch spec) (chan 10))
        read   (when-not (or mult? topic-fn)
                 sink)
        mult   (when mult? (a/mult sink))
        pub    (when topic-fn
                 (if mult
                   (a/pub (a/tap mult (a/chan 10)) topic-fn)
                   (a/pub sink topic-fn)))
        events (chan)
        duplex (hybrid :read read :write source
                       :mult mult :pub pub
                       :channels [events])]
    (alter-meta! duplex assoc
                 :async/ws (atom nil)
                 :async/sink sink
                 :async/source source
                 :async/mult  (a/mult source)
                 :ws/events events)
    (go-loop [opens  {}
              closes {}
              conn   nil]
      (when-some [[evt x] (<! events)]
        (case evt
          :open     (do
                      (run! #(%) (vals opens))
                      (recur opens closes x))
          :close    (do
                      (run! #(%) (vals closes))
                      (recur opens closes nil))
          :on-open  (do
                      (when conn ((second x)))
                      (recur (conj opens x) closes conn))
          :on-close (recur opens (conj closes x) conn)

          :remove/on-open
          (if x
            (recur (dissoc opens x) closes conn)
            (recur {} closes conn))
          :remove/on-close
          (if x
            (recur opens (dissoc closes x) conn)
            (recur opens {} conn))
          (do
            (js/console.warn "unknow events")
            (recur opens closes conn)))))
    (websocket* uri duplex spec)))

(defn header-val [headers k]
  (or (get headers k)
      (let [k (str/lower-case k)]
        (some (fn [[k' v]]
                (when (= (str/lower-case k') k)
                  v))
              headers))))

(defn- serialize [body content-type]
  (if (or (nil? body)
          (string? body)
          (nil? content-type))
    body
    (condp re-find content-type
      #"transit" (encode-transit body)
      #"edn"     (pr-str body)
      #"json"    (edn->jsonstr body)
      body)))

(defn- deserialize [body content-type]
  (if (or (nil? body)
          (string? body)
          (nil? content-type))
    body
    (condp re-find content-type
      #"transit" (decode-transit body)
      #"edn"     (read-string body)
      #"json"    (jsonstr->edn body)
      body)))

(defn infer-format [content-type]
  (re-find #"transit|edn|json|text" content-type))

(let [agent-opts #js {:keepAlive      true
                      :keepAliveMsecs 1500
                      :maxSockets     16}]
  (def http-agent
    (http/Agent. agent-opts))

  (def https-agent
    (https/Agent. agent-opts)))

(defn fetch
  ([url]
   (fetch url nil))
  ([url opts]
   (fetch url opts nil))
  ([url opts p]
   (fetch url opts p nil))
  ([url opts p ex-handler]
   (let [opts   (if (str/starts-with? url "https")
                  (merge {:timeout 6e4
                          :agent   https-agent} opts)
                  (merge {:timeout 6e4
                          :agent   http-agent} opts))
         body   (serialize (:body opts)
                           (header-val (:headers opts)
                                       "Content-Type"))
         fmt    (volatile! nil)
         p      (or p (a/promise-chan))
         dexh   (fn [e]
                  (default-exception-handler e)
                  (a/close! p))
         method (-> opts (:method :get) (name) (str/upper-case))]
     (-> (js/fetch url (doto (clj->js opts)
                         (aset "body" body)
                         (aset "method" method)))
         (.then (fn [res]
                  (->> (.get (.-headers res) "content-type")
                       (infer-format)
                       (vreset! fmt))
                  (case @fmt
                    ("transit" "text" "edn")
                    (.text res)

                    ("json")
                    (.json res)

                    res)))
         (.then (fn [body]
                  (if body
                    (put! p
                          (case @fmt
                            "transit"
                            (decode-transit body)

                            "edn"
                            (read-string body)

                            "json"
                            (js->clj body :keywordize-keys true)

                            body))
                    (a/close! p))))
         (.catch (or ex-handler dexh))
         (.catch dexh))
     p)))

(defhttp GET POST PUT PATCH DELETE OPTION HEAD)

(def url-encode js/encodeURIComponent)
(def url-decode js/decodeURIComponent)
