(ns tools.crypto)

(def crypto (js/require "crypto"))

(defn hmacsha256 [key x]
  (.. crypto
      (createHmac "SHA256" key)
      (update x)
      (digest "base64")))

(defn md5 [x]
  (.. crypto
      (createHash "MD5")
      (update x)
      (digest "hex")))
