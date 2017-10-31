(ns slim-coffee-client.ui
  (:require [rum.core :as r]
            [javelin.core :as j]))

(r/defc bean [on-click data id]
  [:div {:on-click #(on-click id)} data])

;; lookup map used to be bean map
(r/defc section < r/reactive [lookup-map child data]
  "maps over data and passes id and lookup-map into child"
  [:ul
   (map #(vector :li (child (get (r/react lookup-map) %) %)) data)])

;; data is section-map
(r/defc sec-container < r/reactive [data section-click child]
  "maps over data and passes id into section-click "
  [:div {:class "section-container"}
   (map #(vector :div {:class "section" :on-click (fn [] (section-click (first %)))}
                 [:label [:span (str (first %))]]
                 (child (second %)))
        (r/react data))])

(r/defcs message-input < (r/local nil ::value) [state on-click]
  "on-click is a function that gets value-atom passed in"
  (let [value (::value state)]
    [:div
     [:input
      {:type :text
       :auto-focus true
       :placeholder "type in a message and press enter"
       :on-change #(reset! value (.. % -target -value)) }]
     [:button
      {:on-click #(on-click value)} "SUBMIT"]]))

(r/defcs welcome < (r/local nil ::value) [state on-click]
  (let [value (::value state)]
    [:div
     [:div "welcome to lean coffee"]
     [:input {:placeholder "enter game id"
              :on-change #(reset! value (.. % -target -value)) }]
     [:button {:on-click #(on-click value)} "SUBMIT"]]))
