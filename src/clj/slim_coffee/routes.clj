(ns slim-coffee.routes
  (:require [slim-coffee.data :as data]
            [bidi.bidi]
            [rum.core]
            [slim-coffee.ui :as ui]
            [slim-coffee.handlers :as handlers]
            [slim-coffee.util :refer [transit-to-string]]))

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
  (handlers/index-app (rum.core/render-html (ui/welcome identity)) nil request))
(defmethod route-handler-internal :page [_ request] (handlers/p-handler request))
(defmethod route-handler-internal :board [match request]
  ;; get this from data dependency
  (let [game-id-str (get-in match [:route-params :id])
        board-id (try (Integer/parseInt game-id-str)
                      (catch Exception e nil))
        payload (data/get-ws-payload board-id)]
    (handlers/index-app (rum.core/render-html
                (ui/server-game payload))
               (when-not (nil? (:bean payload))
                 (transit-to-string (assoc payload :id board-id)))
               request)))
(defmethod route-handler-internal :not-found [_ request] (handlers/not-found request))
;; use hierarchy to wrap with wrap-reload middlewarea

(defn route-handler [request]
  (let [path (:uri request)
        data (bidi.bidi/match-route m-routes path)]
    (route-handler-internal data request)))
