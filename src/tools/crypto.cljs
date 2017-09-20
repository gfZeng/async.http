(ns tools.crypto
  (:refer-clojure :exclude [hash]))

(def crypto (js/require "crypto"))

(defn hex [x]
  (if (.-digest x)
    (.digest x "hex")
    (-> (js/Buffer. x)
        (.toString "hex"))))

(defn base64 [x]
  (if (.-digest x)
    (.digest x "base64")
    (-> (js/Buffer. x)
        (.toString "base64"))))

(defn hmac [algo key x]
  (.. crypto
      (createHmac algo key)
      (update x "UTF-8")))

(defn hash [algo x]
  (.. crypto
      (createHash algo)
      (update x "UTF-8")))

(defn sha256 [x]
  (hex (hash "SHA256" x)))

(defn md5 [x]
  (hex (hash "MD5" x)))
