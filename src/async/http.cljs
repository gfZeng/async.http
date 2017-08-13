(ns async.http
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [async.http :refer [defhttp]])
  (:require [cljs.core.async :as a :refer [>! <! put! take! chan]]
            [cljs.core.async.impl.protocols :as impl]
            [cljs.core.async.impl.buffers   :as buffers]
            [cljs.reader :refer [read-string]]
            [clojure.string :as str]
            [cognitect.transit :as t]))

(defn jsonstr->edn [x]
  (-> x js/JSON.parse (js->clj :keywordize-keys true)))

(defn edn->jsonstr [x]
  (-> x clj->js js/JSON.stringify))

(def json-format {:read  jsonstr->edn
                  :write edn->jsonstr})

(def identity-format {:read  identity
                      :write identity})

(def transit-format
  (let [rdr (t/reader :json)
        wrt (t/writer :json)]
    {:read  #(t/read rdr %)
     :write #(t/writer wrt %)}))

(def edn-format
  {:read  read-string
   :write pr-str})

(defn comp-format [& fmts]
  {:read  (->> (map :read fmts)
               (remove nil?)
               (apply comp))
   :write (->> (map :write fmts)
               (remove nil?)
               (apply comp))})

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

(defn pub!
  ([ws topic-fn] (pub! ws topic-fn (constantly nil)))
  ([ws topic-fn buf-fn]
   (let [mul (a/mult (:source ws))
         pub (a/pub (a/tap mul (a/chan 10)) topic-fn buf-fn)]
     (alter-meta! ws assoc
                  :async/pub pub
                  :async/mul mul)
     (specify! ws
       impl/ReadPort
       (take! [_ fn-handler]
         (throw
          (ex-info "no support take directly after pub, please use a/tap"
                   {:causes :ws-pubify})))

       a/Mux
       (muxch* [this] this)
       a/Mult
       (tap* [_ ch close?] (a/tap* mul ch close?))
       (untap* [_ ch] (a/untap* mul ch))
       (untap-all* [_] (a/untap-all* mul))

       a/Pub
       (sub* [_ topic ch close?]
         (a/sub* pub topic ch close?))
       (unsub* [_ topic ch]
         (a/unsub* pub topic ch))
       (unsub-all*
         ([_ topic]
          (a/unsub-all* pub topic))
         ([_]
          (a/unsub-all* pub)))))))

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
  ([duplex key handler]
   (listen! duplex :message key handler))
  ([duplex type key handler]
   (alter-meta! duplex assoc-in [:listeners type key] handler)))

(defn run-listeners [duplex type msg]
  (doseq [[_ lst] (-> duplex meta :listeners (get type))]
    (lst msg)))

(defn websocket
  ([spec] (websocket spec (chan 10) (chan 10)))
  ([spec sink source]
   (websocket spec (duplex sink source)))
  ([{:as   spec
     :keys [uri binary-type auto-reconnect?]
     :or   {uri             spec
            auto-reconnect? true}}
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

(defn get-header
  ([headers k]
   (get-header headers k nil))
  ([headers k not-found]
   (if-not (map? headers)
     (or (.get headers k) not-found)
     (let [kname (name k)
           lk    (str/lower-case kname)]
       (or (headers k)
           (headers kname)
           (headers lk)
           (some (fn [[k' v]]
                   (when (= lk (str/lower-case (name k')))
                     v))
                 headers)
           not-found)))))

(defn infer-format
  ([headers]
   (infer-format nil headers))
  ([opts headers]
   (if-some [fmt (:response/format opts)]
     (name fmt)
     (re-find
      #"transit|edn|json|text"
      (get-header headers "Content-Type" "text")))))

(defn format-body [x opts]
  (if (or (nil? x) (string? x))
    x
    (let [fmt (if-some [fmt (:request/format opts)]
                (name fmt)
                (infer-format (:headers opts)))]
      (case fmt
        "transit"
        ((:write transit-format) x)
        "edn"
        ((:write edn-format) x)
        "json"
        (if (coll? x)
          ((:write json-format) x)
          (js/JSON.stringify x))
        x))))

(defn fetch
  ([url]
   (fetch url nil))
  ([url opts]
   (fetch url opts nil))
  ([url opts p]
   (fetch url opts p nil))
  ([url opts p ex-handler]
   (let [opts       (or opts {})
         body       (format-body (:body opts) opts)
         format     (volatile! nil)
         p          (or p (a/promise-chan))
         ex-handler (or ex-handler js/console.error)]
     (-> (js/fetch url (doto (clj->js opts)
                         (aset "body" body)))
         (.then (fn [res]
                  (let [fmt (infer-format opts (.-headers res))]
                    (vreset! format fmt)
                    (case fmt
                      ("transit" "text" "edn")
                      (.text res)

                      ("json" "!kw-json")
                      (.json res)

                      ("array-buffer" "arrayBuffer")
                      (.arrayBuffer res)

                      "blob"
                      (.blob res)

                      ("form-data" "formData")
                      (.formData res)

                      res))))
         (.then (fn [body]
                  (put! p
                        (case @format
                          "transit"
                          ((:read transit-format) body)

                          "edn"
                          ((:read edn-format) body)

                          "json"
                          (js->clj body :keywordize-keys true)

                          "!kw-json"
                          (js->clj body)

                          body))))
         (.catch (fn [e] (ex-handler e))))
     p)))

(defhttp GET POST PUT PATCH DELETE OPTION HEAD)

(def url-encode js/encodeURIComponent)
(def url-decode js/decodeURIComponent)
