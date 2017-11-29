
(in-ns 'async.http)

(require '[clojure.core.async :as a
           :refer [put! chan go go-loop >! <!]]
         '[clojure.core.async.impl.protocols :as impl]
         '[clojure.java.io :as io]
         '[clojure.data.json :as json]
         '[aleph.http :as http]
         '[manifold.deferred :as d]
         '[manifold.stream :as s]
         '[byte-streams :as bs]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[taoensso.timbre :refer [error warn]])

(import [java.net URLEncoder URLDecoder])

(defn default-exception-handler
  [e]
  (error e "uncaught exception"))

(definline jsonstr->edn [s]
  `(json/read-str ~s :key-fn keyword :bigdec true))

(definline edn->jsonstr [s]
  `(json/write-str ~s))

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
      #"transit" (throw (ex-info "not supported yet" {:request/format content-type}))
      #"edn"     (pr-str body)
      #"json"    (json/write-str body)
      body)))

(defn- deserialize [body content-type]
  (if (or (nil? body)
          (string? body)
          (nil? content-type))
    body
    (condp re-find content-type
      #"transit" (throw (ex-info "not supported yet" {:request/format content-type}))
      #"edn"     (edn/read (io/reader body))
      #"json"    (json/read (io/reader body) :key-fn keyword :bigdec true)
      body)))

(defn fetch
  ([url]
   (fetch url nil))
  ([url opts]
   (fetch url opts nil))
  ([url opts p]
   (fetch url opts p nil))
  ([url opts p exh]
   (let [p    (or p (a/promise-chan))
         exh  (or exh default-exception-handler)
         opts (assoc opts
                     :url url
                     :request-method (:request-method opts :get)
                     :body (serialize
                            (:body opts)
                            (-> opts :headers (header-val "content-type"))))]
     (-> (http/request opts)
         (d/chain'
          #(put! p (deserialize
                    (:body %)
                    (-> % :headers (header-val "content-type")))))
         (d/catch' exh)
         (d/catch' default-exception-handler))
     p)))

(defhttp GET POST PUT PATCH DELETE OPTION HEAD)


(definline url-encode [s]
  `(URLEncoder/encode ~s "UTF-8"))

(definline url-decode [s]
  `(URLDecoder/decode ~s "UTF-8"))

(defn- channel-forms [chs]
  `(impl/Channel
    (~'close! [_] (run! impl/close! ~chs))
    (~'closed? [_] (every? impl/closed? ~chs))))

(defn- readport-forms [ch]
  `(impl/ReadPort
    (~'take! [_ fn-handler#]
     (impl/take! ~ch fn-handler#))))

(defn- writeport-forms [ch]
  `(impl/WritePort
    (~'put! [_ val# fn-handler#]
     (impl/put! ~ch val# fn-handler#))))

(defn- pub-forms [p]
  `(a/Pub
    (~'sub* [_ v# ch# close?#]
     (a/sub* ~p v# ch# close?#))
    (~'unsub* [_ v# ch#]
     (a/unsub* ~p v# ch#))
    (~'unsub-all* [_] (a/unsub-all* ~p))
    (~'unsub-all* [_ v#] (a/unsub-all* ~p v#))))

(defn- mult-forms [m]
  `(a/Mult
    (~'tap* [_ ch# close?#]
     (a/tap* ~m ch# close?#))
    (~'untap* [_ ch#]
     (a/untap* ~m ch#))
    (~'untap-all* [_]
     (a/untap-all* ~m))))

(defn hybrid [& {:keys [read write pub mult channels]}]
  (let [chs (cond->> channels
              read  (cons read)
              write (cons write)
              true  (seq))]
    ((eval `(fn ~'[read* write* pub* mult* chs*]
              (reify
                ~@(when chs
                    (channel-forms 'chs*))
                ~@(when read
                    (readport-forms 'read*))
                ~@(when write
                    (writeport-forms 'write*))
                ~@(when pub
                    (pub-forms 'pub*))
                ~@(when mult
                    (mult-forms 'mult*)))))
     read write pub mult (set chs))))

(defn websocket* [uri duplex opts]
  (let [conn   (http/websocket-client uri opts)
        mdata  (meta duplex)
        sink   (:async/sink mdata)
        mult   (:async/mult mdata)
        source (a/tap mult (chan))
        events (:ws/events mdata)]
    (-> conn
        (d/chain'
         (fn [conn]
           (reset! (:async/ws mdata) conn)
           (when (:auto-reconnect? opts true)
             (s/on-closed
              conn (fn []
                     (a/put! events [:close conn])
                     (when-not (or (impl/closed? sink) (impl/closed? source))
                       (warn "websocket unexpected close:" uri)
                       (websocket* uri duplex opts)))))
           (s/connect conn (s/->sink sink) {:downstream? false})
           (s/connect (s/->source source) conn {:upstream? true})
           (a/put! events [:open conn])))
        (d/catch' (:exception-handler opts default-exception-handler))
        (d/catch' default-exception-handler))
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
                       :channels [events])
        duplex (vary-meta duplex assoc
                          :async/ws (atom nil)
                          :async/sink sink
                          :async/source source
                          :async/mult  (a/mult source)
                          :ws/events events)]
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
            (warn "unknow events")
            (recur opens closes conn)))))
    (websocket* uri duplex spec)))


(defn listen! [ws type key fn]
  (assert (#{:on-open :on-close} type) (pr-str type))
  (a/put! (-> ws meta :ws/events) [type [key fn]]))

(defn remove-listen!
  ([ws type]
   (remove-listen! ws type nil))
  ([ws type key]
   (let [type (keyword "remove" (name type))]
     (a/put! (-> ws meta :ws/events) [type key]))))

(defn on-open [ws key fn]
  (listen! ws :on-open key fn))

(defn on-close [ws key fn]
  (listen! ws :on-close key fn))
