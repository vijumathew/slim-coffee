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
            [slim-coffee-client.ui :as ui]
            [slim-coffee-client.websockets :as ws]))

;; enable cljs to print to the JS console of the browser
(enable-console-print!)

(defonce bean-map (j/cell {}))
(defonce section-map (j/cell {}))
(defonce name-map (j/cell {}))
(defonce current-route (j/cell {}))
(defonce board-id (j/cell nil))
(defonce bean-or-section (j/cell :bean))
(defonce active-bean-id (j/cell nil))

(defn upload-bean [value]
  (ws/send-transit-msg!
   (make-new-bean-obj @board-id @value)))

(defn upload-move [bean-id old-sec new-sec]
  (ws/send-transit-msg!
   (make-move-obj @board-id bean-id old-sec new-sec)))

(defn upload-vote [bean-id]
  (ws/send-transit-msg! (make-vote-obj @board-id bean-id)))

(defn refresh-board []
  (ws/send-transit-msg! [@board-id :no-op]))

(defn respond-to-ws! [{:keys [:bean :section]}]
  (let [bean-data (:maps bean)
        section-data (:maps section)
        name-data (:names section)]
    (print 'data)
    (reset! bean-map bean-data)
    (reset! name-map name-data)
    (reset! section-map section-data)))

(def move-ui-chan (async/chan))

;; from core.async dots
(defn async-some [predicate input-chan]
  (async/go-loop []
    (let [msg (async/<! input-chan)]
      (if (predicate (first msg))
        msg
        (recur)))))

(defn get-next-message [msg-name-set input-chan]
  (async-some msg-name-set input-chan))

;; move bean loop
(async/go-loop []
  (let [bean-obj (<! (get-next-message #{:bean-click} move-ui-chan))
        old-sec-obj (<! (get-next-message #{:section-click} move-ui-chan))]
    (reset! bean-or-section :section)
    (reset! active-bean-id (second bean-obj))
    (let [new-sec-obj (<! (get-next-message #{:section-click} move-ui-chan))
          bean-id (second bean-obj)
          old-sec (second old-sec-obj)
          new-sec (second new-sec-obj)]
      (upload-move bean-id old-sec new-sec)
      (reset! bean-or-section :bean)
      (reset! active-bean-id nil)
      (recur))))

(defn section-click [id]
  (async/put! move-ui-chan [:section-click id]))

(defn bean-click [id]
  (async/put! move-ui-chan [:bean-click id]))

(def app-routes
  ["/" {["board/" [ #"\d+" :board-id ] ] :board
        "" :welcome}])

(defn set-page! [match]
  (reset! current-route match)
  (if-let [id (get-in match [:route-params :board-id])]
    (reset! board-id (js/parseInt id))
    (reset! board-id nil)))

(def history
  (pushy/pushy set-page! (partial bidi/match-route app-routes)))

(defn enter-board! [board-id]
  (print (str "/board/" @board-id))
  (pushy/set-token! history (str "/board/" @board-id))
  (refresh-board))

(def my-bean (partial ui/bean bean-or-section bean-click))
(def my-section (partial ui/section bean-map my-bean))

(r/defc game []
  [:div.game-container
   (ui/message-input upload-bean)
   (ui/sec-container bean-or-section section-map name-map section-click my-section)])

(r/defc app < r/reactive []
  (let [handler (:handler (r/react current-route))]
    (if (= handler :board)
      (game)
      (ui/welcome enter-board!))))

(pushy/start! history)

(ws/make-websocket! (str "ws://" "127.0.0.1:8080" "/ws") respond-to-ws!)
(r/mount (app) (.getElementById js/document "app"))
