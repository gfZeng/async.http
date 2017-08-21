(ns async.http
  (:require [clojure.string :as str]))

(defmacro defhttp [& ms]
  `(do
     ~@(for [m ms]
         `(defn ~m
            ([url#] (~m url# nil))
            ([url# opts#]
             (~m url# opts# nil))
            ([url# opts# p#]
             (~m url# opts# p# nil))
            ([url# opts# p# ex-handler#]
             (fetch
              url# (merge {:method ~(keyword
                                     (str/lower-case
                                      (name m)))}
                          opts#)
              p# ex-handler#))))))

#?(:clj (load "http-impl"))
