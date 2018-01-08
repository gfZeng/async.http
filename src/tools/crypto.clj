(ns tools.crypto
  (:refer-clojure :exclude [hash])
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn ->bytes [s]
  (if (bytes? s)
    s
    (.getBytes s "UTF-8")))

(def ^"[C" HEX-CODES (.toCharArray "0123456789ABCDEF"))

(defn hex [^"[B" bs]
  (let [^StringBuilder r (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (aget bs i)]
        (->> (bit-shift-right b 4)
             (bit-and 0xF)
             ^char (aget HEX-CODES)
             (.append r))
        (->> (bit-and b 0xF)
             ^char (aget HEX-CODES)
             (.append r))))
    (.toString r)))

(defn base64 [s]
  (.. java.util.Base64
      (getEncoder)
      (encodeToString (->bytes s))))

(defn hmac [algo key s]
  (-> (doto (Mac/getInstance algo)
        (.init (SecretKeySpec. (.getBytes key "UTF-8") algo)))
      (.doFinal (.getBytes s "UTF-8"))))

(defn hash [algo s]
  (-> (MessageDigest/getInstance algo)
      (.digest (.getBytes s "UTF-8"))))

(defn sha256 [x]
  (hex (hash "SHA-256" x)))

(defn md5 [x]
  (hex (hash "MD5" x)))
