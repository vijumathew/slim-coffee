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
            [clojure.java.io :as io]
            [slim-coffee.ui :as ui]
            [rum.core])
  (:gen-class))

(defn init-data! []
  (def s-atom (atom nil))
  ;; put specs on these
  (def game-ids (atom #{}))
  (def game-to-clients (atom {})) ;; map of game to set of websocket
  ;; map of game to {:maps {:section-id beans} :vec [order] :names {:sec-id name}}
  (def game-to-sections (atom {}))
  (def game-to-votes (atom {}))
  (def game-to-beans (atom {})) ;; map of game to {:maps {:bean-id data}}
  (def client-to-game (atom {})))

(defn init-game! [game-id]
  (let [section-ids (repeat-into-elem make-unique-id #{} 3)
        id-map (into {} (map #(vector % [])) section-ids)
        ordered-ids (vec section-ids)
        names '("to discuss" "discussing" "discussed")]
    (swap! game-ids conj game-id)
    (swap! game-to-beans assoc game-id {})
    (swap! game-to-sections assoc game-id {:maps id-map})
    (swap! game-to-sections assoc-in [game-id :vec] ordered-ids)
    (swap! game-to-sections assoc-in [game-id :names] (zipmap ordered-ids names))
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
(defmethod respond-to-action! :init-board [[_ _ _]]
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
                                  (let [[board-id action t] (parse-transit-string data)]
                                    (when (and (not (contains? @game-ids board-id))
                                               (= action :init-board))
                                      (init-game! board-id)
                                      (swap! game-ids conj board-id))
                                    (when (and (not (get @client-to-game channel))
                                               (contains? @game-ids board-id))
                                      (remember-channel! board-id channel))
                                    (when (contains? @game-ids board-id)
                                      (async/put! app-chan data)))))))

(rum.core/defc server-game [game-id]
  (let [{m-bean :bean section :section} (get-ws-payload game-id)
        server-bean
        (partial ui/bean-note (atom nil) (atom nil) identity)
        server-section
        (partial ui/section (atom (:maps m-bean)) server-bean)]
    (if (nil? m-bean)
      [:div "no game by that id"]
      [:div.game-container
       (ui/message-input identity)
       (ui/sec-container (atom nil) (atom (:maps section)) (atom (:names section)) identity server-section)])))

(def p-handler (ring.middleware.file/wrap-file {} "target/public"))

(defn index-app [body data req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (rum.core/render-static-markup (ui/index-page body data))})

(def m-routes ["/" [["" :index]
                    ["index.html" :index] ;;use page index.html
                    ["board/" {[:id ""] :board}]
                    ["ws" :ws]
                    ["main.js" :page]
                    ["style.css" :page]
                    ["main.out" [[true :page]]]
                    [true :not-found]]])

(defmulti route-handler-internal
  (fn [match _] (:handler match)))
(defmethod route-handler-internal :index [_ request]
  (index-app (rum.core/render-html (ui/welcome identity)) nil request))
(defmethod route-handler-internal :page [_ request] (p-handler request))
(defmethod route-handler-internal :board [match request]
  (let [game-id-str (get-in match [:route-params :id])
        board-id (try (Integer/parseInt game-id-str)
                      (catch Exception e nil))
        payload
        (transit-to-string (assoc (get-ws-payload board-id) :id board-id))]
    (index-app (rum.core/render-html
                (server-game board-id))
               (if-not (nil? (:bean (get-ws-payload board-id)))
                 payload
                 nil)
               request)))
(defmethod route-handler-internal :ws [match request] (websocket-handler request))
(defmethod route-handler-internal :not-found [_ request] (not-found request))
;; use hierarchy to wrap with wrap-file middlewarea

(defn route-handler [request]
  (let [path (:uri request)
        data (bidi.bidi/match-route m-routes path)]
    (route-handler-internal data request)))

(mount.core/defstate server
  :start (org.httpkit.server/run-server #'route-handler {:port 8081})
  :stop (when-not (nil? server) (server :timeout 100)))

(defn dev-main []
  ;;(.mkdirs (io/file "target" "public"))
  (init-data!)
  ;; do something with main.out here as well
  ;;(reset! page-handler (wrap-file {} "target/public"))
  ;;(reset-handler!)
  (mount/start))

(defn -main [& args]
  (init-data!)
  ;; TODO update this
  (reset! page-handler (wrap-resource {} "public"))
  (reset-handler!)
  (mount/start))
