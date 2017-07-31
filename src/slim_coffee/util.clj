(ns slim-coffee.util
  (:require [cognitect.transit :as t])
  (:import (java.io ByteArrayInputStream
                    ByteArrayOutputStream)))

(defn transit-to-string [data]
  (let [out (ByteArrayOutputStream. 4096)
        writer (t/writer out :json)]
    (t/write writer data)
    (.toString out)))

(defn parse-transit-string [data]
  (let [in (ByteArrayInputStream. (.getBytes data "UTF-8"))
        reader (t/reader in :json)]
    (t/read reader)))

(defn make-unique-id
  ([] (make-unique-id #{}))
  ([ids]
   (letfn [(new-id []
             (keyword (str (java.util.UUID/randomUUID))))]
     (loop [id (new-id)]
       (if (contains? ids id)
         (recur (new-id))
         id)))))

(defn repeat-into-elem [f init count]
  (loop [s init n count]
    (if (> n 0)
      (recur (conj s (f s)) (dec n))
      s)))
