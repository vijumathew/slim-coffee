(ns slim-coffee.core
  (:require [mount.core :as mount]
            [bidi.bidi :as bidi]
            [bidi.ring :as br]
            [clojure.core.async :as async]
            [cognitect.transit :as t]
            [org.httpkit.server :as httpkit])
  (:gen-class))
(import [java.io ByteArrayInputStream ByteArrayOutputStream])

;; use mount instead of atom
(defonce s-atom (atom nil))

;; app data
(defonce game-ids (atom #{}))
(defonce game-to-clients (atom {})) ;; map of game to set of websocket
(defonce game-to-sections (atom {})) ;; map of game to {:maps {:section-id beans}}
(defonce game-to-votes (atom {}))
(defonce game-to-beans (atom {})) ;; map of game to {:maps {:bean-id data}}

(defn vec-remove-index
  "remove elem in coll"
  [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn vec-remove
  [coll elem]
  (vec-remove-index coll (.indexOf coll elem)))

(make-unique-id)

(defn make-unique-id
  ([] (make-unique-id #{}))
  ([ids]
   (letfn [(new-id []
             (keyword (str (java.util.UUID/randomUUID))))] 
     (loop [id (new-id)]
       (if (contains? ids id)
         (recur (new-id))
         id)))))

(defn vote-handler! [game-id bean-id]
  (let [vote-data (get @game-to-votes game-id)
        votes (or (get vote-data bean-id)
                   0)]
    (swap! game-to-votes assoc game-id
           (assoc vote-data bean-id (inc votes)))))

(defn move-handler! [game-id bean-id old-sec-id new-sec-id]
  (let [game-data (get @game-to-sections game-id)
        section-data (:maps game-data)
        updated-new (conj (get section-data new-sec-id) bean-id)
        updated-old (vec-remove (get section-data old-sec-id) bean-id)
        updated-section-data (-> section-data
                                 (assoc old-sec-id updated-old)
                                 (assoc new-sec-id updated-new))
        updated-game-data (assoc game-data :maps updated-section-data)]
    (swap! game-to-sections assoc game-id updated-game-data)))

(defn new-bean-handler! [game-id bean-id bean-data sec-id]
  (let [game-section-data (get @game-to-sections game-id)
        section-data (:maps game-section-data)
        game-bean-data (get @game-to-beans game-id)
        bean-map (:maps game-bean-data)
        new-bean-map (assoc bean-map bean-id bean-data)
        updated-game-section-data (assoc game-section-data :maps
                                         (update section-data sec-id conj bean-id))]
    (swap! game-to-sections assoc game-id updated-game-section-data)
    (swap! game-to-beans assoc game-id (assoc game-bean-data :maps new-bean-map))))

(defn remember-channel! [game-id channel]
  (println game-id)
  (println channel)
  (swap! game-to-clients update game-id #(set (conj %1 %2)) channel))

(defn forget-channel! [game-id channel status]
  (swap! game-to-clients update game-id #(set (remove #{%2} %1)) channel))

(defn notify-clients [msg clients]
  (println msg)
  (println clients)
  (doseq [channel clients]
    (println channel)
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
    (println channel)
    (remember-channel! :1 channel)
    (httpkit/on-close channel (fn [status]
                                (println "channel closed")
                                ;;(forget-channel! channel status)
                                ))
    (httpkit/on-receive channel (fn [data]
                                  (println data)
                                  ;;(notify-clients data)
                                  ;;(httpkit/send! channel data)
                                  ;;adds channel to list of clients
                                  ;; put this in go-loop somehow
                                  (swap! game-to-clients update (first data) conj channel) 
                                  (async/put! app-chan data)))))

;; data getter
(defn get-ws-payload [game-id]
  (let [bean-data (get @game-to-beans game-id)
        section-data (get @game-to-sections game-id)]
    {:bean bean-data :section section-data}))

(defn transit-to-string [data]
  (let [out (ByteArrayOutputStream. 4096)
        writer (t/writer out :json)]
    (t/write writer data)
    (.toString out)))


(defn send-data [game-id]
  (notify-clients (transit-to-string (get-ws-payload game-id))
                  (get @game-to-clients game-id)))

(respond-to-action! [:1 :move {:bean-id :2 :old-sec-id :4 :new-sec-id :5}])
@game-to-beans{:1 {:maps {:2 "hi", :3 "yo", :6 "wassup"}}}
@game-to-sections{:1 {:maps {:4 [:2 :6], :5 [:3]}}}
(send-data :1)

(get-ws-payload :1)

;; app loop
(def app-chan (async/chan 20)) 

(async/go-loop []
  (let [data (async/<! app-chan)
        [game-id _] data]
    (swap! game-ids conj game-id)
    (respond-to-action! data)
    (notify-clients (get-ws-payload game-id)
                    (get @game-to-clients game-id))))

;; these are what's sent over the wire
;; [:game-id :action-id payload]
(defmulti respond-to-action!
  (fn [[_ id]] id))

(defmethod respond-to-action! :vote [[game-id _ data]]
  (let [{:keys [bean-id]} data]
    (vote-handler! game-id bean-id)))

(defmethod respond-to-action! :move [[game-id _ data]]
  (let [{:keys [bean-id old-sec-id new-sec-id]} data]
    (move-handler! game-id bean-id old-sec-id new-sec-id)))

(defmethod respond-to-action! :new-bean [[game-id _ data]]
  (let [{:keys [bean-id bean-data sec-id]} data]
    (new-bean-handler! game-id bean-id bean-data sec-id)))


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
