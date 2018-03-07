(ns slim-coffee-client.core
  (:require-macros [cljs.core.async.macros :as async])
  (:require [rum.core :as r]
            [cognitect.transit :as t]
            [javelin.core :as j]
            [bidi.bidi :as bidi]
            [pushy.core :as pushy]
            [clojure.core.async :as async]
            [slim-coffee-client.data :refer
             [make-vote-obj make-new-bean-obj make-move-obj]]
            [slim-coffee.ui :as ui]
            [slim-coffee-client.websockets :as ws]
            [cognitect.transit]))

(enable-console-print!)

;;data
(defonce bean-map (j/cell {}))
(defonce section-map (j/cell {}))
(defonce name-map (j/cell {}))
(defonce current-route (j/cell {}))
(defonce board-id (j/cell nil))
(defonce bean-or-section (j/cell :bean))
(defonce active-bean-id (j/cell nil))

;;api
(defn upload-bean [board-id value]
  (ws/send-transit-msg!
   (make-new-bean-obj @value)))

(defn upload-move [board-id bean-id old-sec new-sec]
  (ws/send-transit-msg!
   (make-move-obj board-id bean-id old-sec new-sec)))

(defn init-board [id]
  (ws/send-transit-msg! [id :init-board]))

(defn refresh-board [board-id]
  (ws/send-transit-msg! [board-id :no-op]))

(defn respond-to-ws! [{:keys [:bean :section]}]
  (let [bean-data (:maps bean)
        section-data (:maps section)
        name-data (:names section)]
    (print 'data)
    (reset! bean-map bean-data)
    (reset! name-map name-data)
    (reset! section-map section-data)))

(comment (defn upload-vote [board-id bean-id]
           (ws/send-transit-msg! (make-vote-obj @board-id bean-id))))

(def move-ui-chan (async/chan))

;;util
(defn get-next-message [desired? input-chan]
  (async/go-loop []
    (let [msg (async/<! input-chan)]
      (if (desired? (first msg))
        msg
        (recur)))))

;; move bean actions
(defn make-move-loop [move-ui-chan]
  (async/go-loop []
    (let [bean-obj (<! (get-next-message #{:bean-click} move-ui-chan))
          old-sec-obj (<! (get-next-message #{:section-click} move-ui-chan))]
      (reset! bean-or-section :section)
      (reset! active-bean-id (second bean-obj))
      (let [new-sec-obj (<! (get-next-message #{:section-click} move-ui-chan))
            bean-id (second bean-obj)
            old-sec (second old-sec-obj)
            new-sec (second new-sec-obj)]
        (upload-move @board-id bean-id old-sec new-sec)
        (reset! bean-or-section :bean)
        (reset! active-bean-id nil)
        (recur)))))

(def move-loop (make-move-loop move-ui-chan))

(defn section-click [id]
  (async/put! move-ui-chan [:section-click id]))

(defn bean-click [id]
  (async/put! move-ui-chan [:bean-click id]))

;;routes
(def app-routes
  ["/" {["board/" [ #"\d+" :board-id ] ] :board
        "" :welcome}])

(defn set-page! [current-route match]
  ;;(.log js/console (clj->js match))
  (reset! current-route match))

(def history
  (pushy/pushy (partial set-page! @current-route) (partial bidi/match-route app-routes)))

;;app state
(defn enter-board! [value]
  ;;(.log js/console (str "/board/" @value))
  (pushy/set-token! history (str "/board/" @value))
  (reset! board-id (js/parseInt @value))
  (init-board (js/parseInt @value)))

;;ui
(def my-bean (partial ui/bean-note active-bean-id bean-or-section bean-click))
(def my-section (partial ui/section bean-map my-bean))

(r/defc game [board-id]
  [:div.game-container
   (ui/message-input (partial upload-bean board-id))
   (ui/sec-container bean-or-section section-map name-map section-click my-section)])

(r/defc app < r/reactive []
  (let [handler (:handler (r/react current-route))]
    (if (= handler :board)
      (if (nil? (r/react board-id))
        [:div "no game by that id"]
        (game (r/react board-id)))
      (ui/welcome enter-board!))))

(pushy/start! history)

(let [current-url (.-host js/window.location)]
  (ws/make-websocket! (str "ws://" current-url "/ws") respond-to-ws!
                      #(when-not (nil? @board-id) (refresh-board @board-id))))

;;server side loading
(if-let [elem (.getElementById js/document "data" )]
  (let [reader (cognitect.transit/reader :json)
        data (cognitect.transit/read reader (.-innerText elem))]
    (reset! board-id (:id data))
    (respond-to-ws! data))
  nil)

(r/mount (app) (.getElementById js/document "app"))

;;routing,api,util,data (server-side),ui-highlighting-loop, ui-rendering loop
;;enter-board from ui, push-state -> app-route-state
