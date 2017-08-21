
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
         '[taoensso.timbre :refer [error]])

(import [java.net URLEncoder URLDecoder])

(defn default-exception-handler
  [e]
  (error e))

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
      #"json"    (json/read (io/reader body) :key-fn keyword)
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
         (d/catch' exh))
     p)))

(defhttp GET POST PUT PATCH DELETE OPTION HEAD)


(definline url-encode [s]
  `(URLEncoder/encode ~s "UTF-8"))

(definline url-decode [s]
  `(URLDecoder/decode ~s "UTF-8"))

(defn- channel-forms [& chs]
  (let [chs (vec (remove nil? chs))]
    `(impl/Channel
      (~'close! [_] (run! impl/close! ~chs))
      (~'closed? [_] (every? impl/closed? ~chs)))))

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

(defn hybrid [& {:keys [read write pub mult]}]
  ((eval `(fn ~'[read* write* pub* mult*]
            (reify
              ~@(if (or read write)
                  (channel-forms
                   (when read 'read*)
                   (when write 'write*)))
              ~@(if read
                  (readport-forms 'read*))
              ~@(if write
                  (writeport-forms 'write*))
              ~@(if pub
                  (pub-forms 'pub*))
              ~@(if mult
                  (mult-forms 'mult*)))))
   read write pub mult))


(defn websocket [uri & {:keys [mult? pub?] :as spec}]
  (let [conn   @(http/websocket-client uri)
        sink   (or (:read/ch spec)  (chan 10))
        source (or (:write/ch spec) (chan 10))
        read   (when-not (or mult? pub?)
                 sink)
        mult   (when mult? (a/mult sink))
        pub    (when pub?
                 (if mult
                   (a/pub (a/tap mult (a/chan 10)) :channel)
                   (a/pub sink :channel)))]
    (s/connect conn (s/->sink sink))
    (s/connect (s/->source source) conn)
    (with-meta
      (hybrid :read read :write source :mult mult :pub pub)
      {:aleph/ws conn})))

