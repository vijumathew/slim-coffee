(ns slim-coffee.core
  (:require [mount.core :as mount]
            [bidi.bidi :as bidi]
            [bidi.ring :as br]
            [org.httpkit.server :as httpkit])
  (:gen-class))


;; use mount instead of atom
(defonce s-atom (atom nil))
(defonce channels (atom #{}))

(defn remember-channel! [channel]
  (swap! channels conj channel))

(defn forget-channel! [channel status]
  (swap! channels #(remove #{channel} %)))

(defn notify-clients [msg]
  (doseq [channel @channels]
    (httpkit/send! channel msg)))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello world yo!"})

(defn other-app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello world friend!"})

(defn websocket-handler [req]
  (httpkit/with-channel req channel
    (remember-channel! channel)
    (httpkit/on-close channel (fn [status]
                                (println "channel closed")
                                (forget-channel! channel status)))
    (if (httpkit/websocket? channel)
      (println "WebSocket channel")
      (println "HTTP channel"))
    (httpkit/on-receive channel (fn [data]
                                  (println data)
                                  (notify-clients data)
                                  (httpkit/send! channel data)))))

(def handler
  (atom 
   (br/make-handler ["/" {"index.html" app
                          "ws" websocket-handler
                          true other-app}])))

(defn start-server [port]
  (httpkit/run-server @handler {:port port}))

(defn stop-server [mount-server]
  (when-not (nil? @mount-server)
    (@mount-server :timeout 100)))

(mount/defstate server
  :start (reset! s-atom (start-server 8080))
  :stop (stop-server s-atom))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (mount/start))
