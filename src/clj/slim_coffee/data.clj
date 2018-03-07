(ns slim-coffee.data
  (:require [mount.core :as mount]
            [slim-coffee.util :as util]))

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

;; add :end function
(mount.core/defstate data :start (init-data!))

(defn init-game! [game-id]
  (let [section-ids (util/repeat-into-elem util/make-unique-id #{} 3)
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

(defn forget-channel! [channel status]
  (let [game-id (@client-to-game channel)]
    (swap! client-to-game dissoc channel)
    (swap! game-to-clients update game-id #(set (remove #{%2} %1)) channel)))

(defn contains-board-id? [board-id]
  (contains? @game-ids board-id))

(defn get-ws-payload [game-id]
  (let [bean-data (get @game-to-beans game-id)
        section-data (get @game-to-sections game-id)]
    {:bean bean-data :section section-data}))

(defn get-clients [game-id]
  (get @game-to-clients game-id))

(defn websocket-receive [data channel]
  (let [[board-id action t] (util/parse-transit-string data)
        created (contains? @game-ids board-id)]
    (when (and (not created)
               (= action :init-board))
      (init-game! board-id)
      (swap! game-ids conj board-id))
    (when (and (not (get @client-to-game channel))
               created)
      (remember-channel! board-id channel))))

(defn vote-handler! [game-id bean-id]
  "G-TO-VOTES is atom"
  (let [vote-data (get @game-to-votes game-id)
        votes (or (get vote-data bean-id)
                   0)]
    (swap! game-to-votes assoc game-id
           (assoc vote-data bean-id (inc votes)))))

;; if bean is not on old section, throw error
(defn move-handler! [game-id bean-id old-sec-id new-sec-id]
  (when (and (not (= old-sec-id new-sec-id))
             (util/contains-value? (get (:maps (get @game-to-sections game-id)) old-sec-id) bean-id)) 
    (let [game-data (get @game-to-sections game-id)
          section-data (:maps game-data)
          updated-new (conj (get section-data new-sec-id) bean-id)
          updated-old (util/vec-remove (get section-data old-sec-id) bean-id)
          updated-section-data (-> section-data
                                   (assoc old-sec-id updated-old)
                                   (assoc new-sec-id updated-new))
          updated-game-data (assoc game-data :maps updated-section-data)]
      (swap! game-to-sections assoc game-id updated-game-data))))

(defn new-bean-handler! [game-id bean-data]
  (let [sec-id (first (get-in @game-to-sections [game-id :vec]))
        bean-id (util/make-unique-id (set (keys (get-in @game-to-beans [:1 :maps]))))
        game-section-data (get @game-to-sections game-id)
        section-data (:maps game-section-data)
        game-bean-data (get @game-to-beans game-id)
        bean-map (:maps game-bean-data)
        new-bean-map (assoc bean-map bean-id bean-data)
        updated-game-section-data (assoc game-section-data :maps
                                         (let [current-section (get section-data sec-id)]
                                           (update section-data sec-id
                                                      (comp vec conj) bean-id)))]
    (swap! game-to-sections assoc game-id updated-game-section-data)
    (swap! game-to-beans assoc game-id (assoc game-bean-data :maps new-bean-map))))

(defmulti respond-to-action!
  (fn [[_ id]] id))
(defmethod respond-to-action! :vote [[game-id _ data]]
  (let [{:keys [bean-id]} data]
    (vote-handler! game-id bean-id)))
(defmethod respond-to-action! :move [[game-id _ data]]
  (let [{:keys [bean-id old-sec-id new-sec-id]} data]
    (move-handler! game-id bean-id old-sec-id new-sec-id)))
(defmethod respond-to-action! :new-bean [[game-id _ data]]
  (let [{:keys [bean-data]} data]
    (new-bean-handler! game-id bean-data)))
(defmethod respond-to-action! :no-op [[_ _ _]]
  (identity nil))
(defmethod respond-to-action! :init-board [[_ _ _]]
  (identity nil))
