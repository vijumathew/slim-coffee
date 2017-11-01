(ns slim-coffee-client.ui
  (:require [rum.core :as r]
            [javelin.core :as j]))

(defn make-true [my-atom]
  (reset! my-atom true))

(defn make-false [my-atom]
  (reset! my-atom false))

(r/defcs bean < (r/local false ::hover) (r/local false ::active) r/reactive
  [state bean-or-section on-click data id]
  (let [active (::active state)
        is-bean (= (r/react bean-or-section) :bean)
        hover (::hover state)]
    [:li.bean {:class [(when (and @hover is-bean) "bean-hover")]
               :on-click #(on-click id)
               :on-mouse-enter #(make-true hover)
               :on-touch-start #(make-true hover)
               :on-mouse-leave #(make-false hover)
               :on-touch-end #(make-false hover)} [:div data]]))

;; lookup map used to be bean map
(r/defc section < r/reactive [lookup-map child data]
  "maps over data and passes id and lookup-map into child"
  [:ul
   (map #(identity (child (get (r/react lookup-map) %) %)) data)])

(r/defcs section-wrapper < r/reactive (r/local false ::hover)
  [state bean-or-section data name-data section-click child passed-data]
  (let [hover (::hover state)
        is-section (= (r/react bean-or-section) :section)]
    [:div {:class [(when (and @hover is-section) "section-hover") "section"]
           :on-click (fn [] (section-click (first passed-data)))
           :on-mouse-enter #(make-true hover)
           :on-touch-start #(make-true hover)
           :on-mouse-leave #(make-false hover)
           :on-touch-end #(make-false hover)}
     [:div.section-label
      [:label [:span (str (get @name-data (first passed-data)))]]]
     (child (second passed-data))]))

;; data is section-map
(r/defc sec-container < r/reactive [bean-or-section data name-data section-click child]
  "maps over data and passes id into section-click "
  (let [my-section-wrapper (partial section-wrapper
                                    bean-or-section data name-data section-click child)]
    [:div {:class "section-container"}
     (map my-section-wrapper
          (r/react data))]))

(r/defcs message-input < (r/local nil ::value) [state on-click]
  "on-click is a function that gets value-atom passed in"
  (let [value (::value state)]
    [:div.input-container
     [:input
      {:type :text
       :auto-focus true
       :placeholder "new bean"
       :on-change #(reset! value (.. % -target -value)) }]
     [:button
      {:on-click #(when (-> @value empty? not)
                    (on-click value))} "SUBMIT"]]))

(r/defcs welcome < (r/local nil ::value) [state on-click]
  (let [value (::value state)]
    [:div.welcome-container
     [:div "welcome to lean coffee"]
     [:div.input-container
      [:input {:placeholder "enter game id"
               :on-change #(reset! value (.. % -target -value)) }]
      [:button {:on-click #(when (-> @value js/isNaN not)
                             (on-click value))} "SUBMIT"]]]))
