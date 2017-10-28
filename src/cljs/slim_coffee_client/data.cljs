(ns slim-coffee-client.data)

(defn make-move-obj [game-id bean-id old-id new-id]
  (vector game-id :move {:bean-id bean-id :old-sec-id old-id :new-sec-id new-id}))

(defn make-new-bean-obj [game-id bean-data]
  (vector game-id :new-bean {:bean-data bean-data}))

(defn make-vote-obj [game-id bean-id]
  (vector game-id :vote {:bean-id bean-id}))
