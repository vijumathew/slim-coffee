(ns slim-coffee.handlers
  (:require [ring.middleware.file :refer [wrap-file]]
            [rum.core]
            [slim-coffee.ui :as ui]))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello world yo!"})

(defn not-found [req]
  {:status  404
   :headers {"Content-Type" "text/html"}
   :body    "page not found!"})

(defn remove-from-path [handler substr]
  (fn [request]
    (reset! temp request)
    (let [uri (:uri request)
          new-uri (if (clojure.string/starts-with? uri substr)
                (clojure.string/replace-first uri substr "")
                uri)
          req (assoc request :uri new-uri)
          response (handler req)]
      response)))

(def p-handler (ring.middleware.file/wrap-file {} "target/public"))

(defn index-app [body data req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (rum.core/render-static-markup (ui/index-page body data))})
