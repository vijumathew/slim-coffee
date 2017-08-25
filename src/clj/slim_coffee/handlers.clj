(ns slim-coffee.handlers)

(defn vec-remove-index
  "remove elem in coll"
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn contains-value? [coll element]
  (boolean (some #(= element %) coll)))

(defn vec-remove
  [coll elem]
  (if (contains-value? coll elem)
    (vec-remove-index coll (.indexOf coll elem))
    coll))

(defn vote-handler! [game-id bean-id g-to-votes]
  "G-TO-VOTES is atom"
  (let [vote-data (get @g-to-votes game-id)
        votes (or (get vote-data bean-id)
                   0)]
    (swap! g-to-votes assoc game-id
           (assoc vote-data bean-id (inc votes)))))

;; if bean is not on old section, throw error
(defn move-handler! [game-id bean-id old-sec-id new-sec-id g-to-secs]
  "G-TO-SECS is atom"
  (when (and (not (= old-sec-id new-sec-id))
             (contains-value? (get (:maps (get @g-to-secs game-id)) old-sec-id) bean-id)) 
    (let [game-data (get @g-to-secs game-id)
          section-data (:maps game-data)
          updated-new (conj (get section-data new-sec-id) bean-id)
          updated-old (vec-remove (get section-data old-sec-id) bean-id)
          updated-section-data (-> section-data
                                   (assoc old-sec-id updated-old)
                                   (assoc new-sec-id updated-new))
          updated-game-data (assoc game-data :maps updated-section-data)]
      (swap! g-to-secs assoc game-id updated-game-data))))

(defn new-bean-handler! [game-id bean-id bean-data sec-id g-to-secs g-to-beans]
  (let [game-section-data (get @g-to-secs game-id)
        section-data (:maps game-section-data)
        game-bean-data (get @g-to-beans game-id)
        bean-map (:maps game-bean-data)
        new-bean-map (assoc bean-map bean-id bean-data)
        updated-game-section-data (assoc game-section-data :maps
                                         (let [current-section (get section-data sec-id)]
                                           (update section-data sec-id
                                                      (comp vec conj) bean-id)))]
    (swap! g-to-secs assoc game-id updated-game-section-data)
    (swap! g-to-beans assoc game-id (assoc game-bean-data :maps new-bean-map))))
