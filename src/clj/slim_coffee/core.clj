(ns slim-coffee.core
  (:require [mount.core :as mount]
            [bidi.bidi :as bidi]
            [bidi.ring :as br]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]
            [clojure.core.async :as async]
            [org.httpkit.server :as httpkit]
            [slim-coffee.handlers :as handlers]
            [slim-coffee.util :refer
             [transit-to-string parse-transit-string make-unique-id repeat-into-elem]]
            [clojure.java.io :as io]
            [slim-coffee.ui :as ui]
            [rum.core]
            [slim-coffee.data :as data]
            [slim-coffee.routes :as routes]
            )
  (:gen-class))



(defn notify-clients [msg clients]
  (doseq [channel clients]
    (httpkit/send! channel msg)))

(defn send-data! [game-id]
  (notify-clients (transit-to-string (data/get-ws-payload game-id))
                  (data/get-clients game-id)))

;; app loop
(def app-chan (async/chan 20))

(def app-loop
  (async/go-loop []
    (let [unparsed (async/<! app-chan)
          data (parse-transit-string unparsed)
          [game-id _] data]
      (data/respond-to-action! data)
      (send-data! game-id)
      (recur))))

(defn websocket-handler [req]
  (httpkit/with-channel req channel
    (httpkit/on-close channel (fn [status]
                                (data/forget-channel! channel status)))
    ;; spec: [:game-id :action-type payload]
    (httpkit/on-receive channel (fn [data]
                                  (data/websocket-receive data channel)
                                  (let [[board-id _ _] (parse-transit-string data)]
                                    (when (data/contains-board-id? board-id)
                                      (async/put! app-chan data)))))))

(defmethod routes/route-handler-internal :ws [match request] (websocket-handler request))

(mount.core/defstate server
  :start (org.httpkit.server/run-server #'routes/route-handler {:port 8081})
  :stop (when-not (nil? server) (server :timeout 100)))

(defn dev-main []
  ;;(.mkdirs (io/file "target" "public"))
  ;; do something with main.out here as well
  ;;(reset! page-handler (wrap-file {} "target/public"))
  ;;(reset-handler!)
  (mount/start #'slim-coffee.data/data)
  (mount/start #'slim-coffee.core/server))

(defn -main [& args]
  (init-data!)
  ;; TODO update this
  (reset! page-handler (wrap-resource {} "public"))
  (reset-handler!)
  (mount/start))
