(ns tools.crypto
  (:refer-clojure :exclude [hash])
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [javax.xml.bind DatatypeConverter]))

(defn ->bytes [s]
  (if (bytes? s)
    s
    (.getBytes s "UTF-8")))

(definline hex [^"[B" bs]
  `(DatatypeConverter/printHexBinary ~bs))

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
