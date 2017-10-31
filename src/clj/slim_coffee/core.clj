(ns slim-coffee.core
  (:require [mount.core :as mount]
            [bidi.bidi :as bidi]
            [bidi.ring :as br]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]
            [clojure.core.async :as async]
            [org.httpkit.server :as httpkit]
            [slim-coffee.handlers :refer
             [vote-handler! new-bean-handler! move-handler!]]
            [slim-coffee.util :refer
             [transit-to-string parse-transit-string make-unique-id repeat-into-elem]]
            [clojure.java.io :as io])
  (:gen-class))

(defn init-data! []
  (def s-atom (atom nil))
  ;; put specs on these
  (def game-ids (atom #{}))
  (def game-to-clients (atom {})) ;; map of game to set of websocket
  (def game-to-sections (atom {})) ;; map of game to {:maps {:section-id beans}}
  (def game-to-votes (atom {}))
  (def game-to-beans (atom {})) ;; map of game to {:maps {:bean-id data}}
  (def client-to-game (atom {})))

(defn init-game! [game-id]
  (let [section-ids (repeat-into-elem make-unique-id #{} 3)
        id-map (into {} (map #(vector % [])) section-ids)]
    (swap! game-ids conj game-id)
    (swap! game-to-beans assoc game-id {})
    (swap! game-to-sections assoc game-id {:maps id-map})
    (swap! game-to-sections assoc-in [game-id :vec] (vec section-ids))
    (swap! game-to-votes assoc game-id {})))

(defn remember-channel! [game-id channel]
  (swap! client-to-game assoc channel game-id)
  (swap! game-to-clients update game-id #(set (conj %1 %2)) channel))

(defn forget-channel! [game-id channel status]
  (swap! client-to-game dissoc channel)
  (swap! game-to-clients update game-id #(set (remove #{%2} %1)) channel))

(defn notify-clients [msg clients]
  (doseq [channel clients]
    (httpkit/send! channel msg)))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello world yo!"})

(defn not-found [req]
  {:status  404
   :headers {"Content-Type" "text/html"}
   :body    "page not found!"})

(defn get-ws-payload [game-id]
  (let [bean-data (get @game-to-beans game-id)
        section-data (get @game-to-sections game-id)]
    {:bean bean-data :section section-data}))

;; [:game-id :action-id payload]
(defmulti respond-to-action!
  (fn [[_ id]] id))

(defmethod respond-to-action! :vote [[game-id _ data]]
  (let [{:keys [bean-id]} data]
    (vote-handler! game-id bean-id game-to-votes)))

(defmethod respond-to-action! :move [[game-id _ data]]
  (let [{:keys [bean-id old-sec-id new-sec-id]} data]
    (move-handler! game-id bean-id old-sec-id new-sec-id game-to-sections)))

(defmethod respond-to-action! :new-bean [[game-id _ data]]
  (let [{:keys [bean-data]} data
        sec-id (first (get-in @game-to-sections [game-id :vec]))
        bean-id (make-unique-id (set (keys (get-in @game-to-beans [:1 :maps]))))]
    (new-bean-handler! game-id bean-id bean-data sec-id game-to-sections game-to-beans)))

(defmethod respond-to-action! :no-op [[_ _ _]]
  (identity nil))

(defn send-data [game-id]
  (notify-clients (transit-to-string (get-ws-payload game-id))
                  (get @game-to-clients game-id)))

;; app loop
(def app-chan (async/chan 20))

(def app-loop
  (async/go-loop []
    (let [unparsed (async/<! app-chan)
          data (parse-transit-string unparsed)
          [game-id _] data]
      (respond-to-action! data)
      (send-data game-id)
      (recur))))

(defn websocket-handler [req]
  (httpkit/with-channel req channel
    (httpkit/on-close channel (fn [status]
                                (forget-channel! (@client-to-game channel)
                                                 channel status)))
    ;; spec: [:game-id :action-type payload]
    (httpkit/on-receive channel (fn [data]
                                  (let [parsed (parse-transit-string data)]
                                    (when (not (get @client-to-game channel))
                                      (let [board-id (first parsed)]
                                        (remember-channel! board-id channel)
                                        (when (not (contains? @game-ids board-id))
                                          (init-game! board-id)
                                          (swap! game-ids conj board-id))))
                                    (async/put! app-chan data))))))

(def page-handler (atom nil))
(def handler (atom nil))

(defn reset-handler! []
  (reset! handler
          (br/make-handler ["/" {"app.html" app
                                 "ws" websocket-handler
                                 "index.html" @page-handler
                                 "main.js" @page-handler
                                 "style.css" @page-handler
                                 "main.out" {true @page-handler}
                                 true not-found}])))

(defn start-server [port]
  (httpkit/run-server @handler {:port port}))

(defn stop-server [mount-server]
  (when-not (nil? @mount-server)
    (@mount-server :timeout 100)))

(mount/defstate server
  :start (reset! s-atom (start-server 8080))
  :stop (stop-server s-atom))

(defn dev-main []
  (.mkdirs (io/file "target" "public"))
  (init-data!)
  ;; do something with main.out here as well
  (reset! page-handler (wrap-file {} "target/public"))
  (reset-handler!)
  (mount/start))

(defn -main [& args]
  (init-data!)
  (reset! page-handler (wrap-resource {} "public"))
  (reset-handler!)
  (mount/start))
