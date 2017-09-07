(ns tools.crypto
  (:require [clojure.java.io :as io])
  (:import [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [javax.xml.bind DatatypeConverter]))

(definline hex [^"[B" bs]
  `(DatatypeConverter/printHexBinary ~bs))

(defn md5 [s]
  (-> (MessageDigest/getInstance "MD5")
      (.digest (.getBytes s "UTF-8"))
      (hex)))

(defn hmacsha256 [key s]
  (.. java.util.Base64
      (getEncoder)
      (encodeToString
       (->
        (doto (Mac/getInstance "HmacSHA256")
          (.init (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")))
        (.doFinal (.getBytes s "UTF-8"))))))

(defn sha256 [s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s "UTF-8"))
      (hex)))

(comment
  (md5 "abc")
  (url-encode "a+b")
  )
