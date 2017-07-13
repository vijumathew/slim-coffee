(ns slim-coffee.core
  (:require [mount.core :as mount]
            [bidi.bidi :as bidi]
            [bidi.ring :as br]
            [org.httpkit.server :as httpkit])
  (:gen-class))


;; use mount instead of atom
(defonce s-atom (atom nil))

;; app data
(defonce game-ids (atom []))
(defonce game-to-clients (atom {})) ;; map of game to set of websocket
(defonce game-to-sections (atom {})) ;; map of game to {:maps {:section-id beans}}
(defonce game-to-votes (atom {}))
(defonce game-to-beans (atom {}))

(defn make-unique-id
  ([] (make-unique-id #{}))
  ([ids]
   (letfn [(new-id []
             (keyword (str (java.util.UUID/randomUUID))))] 
     (loop [id (new-id)]
       (if (contains? ids id)
         (recur (new-id))
         id)))))

(defn vote-handler! [{:keys [game-id bean-id]}]
  (let [vote-data (get @game-to-votes game-id)
        votes (or (get vote-data bean-id)
                   0)]
    (swap! game-to-votes assoc game-id
           (assoc vote-data bean-id (inc votes)))))

(defn vec-remove
  [coll elem]
  (vec-remove-index coll (.indexOf coll elem)))

(defn vec-remove-index
  "remove elem in coll"
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn move-handler! [{:keys [game-id bean-id old-sec-id new-sec-id]}]
  (let [game-data (get @game-to-sections game-id)
        section-data (:maps game-data)
        updated-new (conj (get section-data new-sec-id) bean-id)
        updated-old (vec-remove (get section-data old-sec-id) bean-id)
        updated-section-data (-> section-data
                                 (assoc old-sec-id updated-old)
                                 (assoc new-sec-id updated-new))
        updated-game-data (assoc game-data :maps updated-section-data)]
    (swap! game-to-sections assoc game-id updated-game-data)))

(defn new-bean-handler! [{:keys [game-id bean-id bean-data sec-id]}]
  (let [game-section-data (get @game-to-sections game-id)
        section-data (:maps game-section-data)
        game-bean-data (get @game-to-beans game-id)
        bean-map (:maps game-bean-data)
        new-bean-map (assoc bean-map bean-id bean-data)
        updated-game-section-data (assoc game-section-data :maps
                                         (update section-data sec-id conj bean-id))]
    (swap! game-to-sections assoc game-id updated-game-section-data)
    (swap! game-to-beans assoc game-id (assoc game-bean-data :maps new-bean-map))))

(defn remember-channel! [channel]
  (swap! channels conj channel))

(defn forget-channel! [channel status]
  (swap! channels #(remove #{channel} %)))

(defn notify-clients [msg]
  (doseq [channel @channels]
    (httpkit/send! channel msg)))

(defn app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello world yo!"})

(defn other-app [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello world friend!"})

(defn websocket-handler [req]
  (httpkit/with-channel req channel
    (remember-channel! channel)
    (httpkit/on-close channel (fn [status]
                                (println "channel closed")
                                (forget-channel! channel status)))
    (if (httpkit/websocket? channel)
      (println "WebSocket channel")
      (println "HTTP channel"))
    (httpkit/on-receive channel (fn [data]
                                  (println data)
                                  (notify-clients data)
                                  (httpkit/send! channel data)))))

(def handler
  (atom 
   (br/make-handler ["/" {"index.html" app
                          "ws" websocket-handler
                          true other-app}])))

(defn start-server [port]
  (httpkit/run-server @handler {:port port}))

(defn stop-server [mount-server]
  (when-not (nil? @mount-server)
    (@mount-server :timeout 100)))

(mount/defstate server
  :start (reset! s-atom (start-server 8080))
  :stop (stop-server s-atom))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (mount/start))
