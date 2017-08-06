(ns async.http)

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
             (async.http/fetch
              url# (merge {:method ~(name m)} opts#)
              p# ex-handler#))))))
