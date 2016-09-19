(ns dwc-bot-es.core
  (:require [dwc-bot-es.ipt-feed :as ipt-feed])
  (:require [dwc-bot-es.config :as config])
  (:require [dwc-io.archive :as dwca]
            [dwc-io.fixes :as fixes])
  (:require [clojure.data.json :as json])
  (:require [batcher.core :refer :all])
  (:require [clojure.core.async :refer [<! <!! >! >!! go go-loop chan close!]])
  (:require [clj-http.lite.client :as http])
  (:require [taoensso.timbre :as log]))

(defn now
  "Get current timestamp"
  [] (int (/ (System/currentTimeMillis) 1000)))

(defn all-resources
  "Get all resources of all inputs ipts"
  [] (flatten (map ipt-feed/get-resources (config/inputs))))

(defn metadata
  "Assoc metadata to the occurrence"
  [src occ] 
  (assoc occ :id (:occurrenceID occ)
             :identifier (:occurrenceID occ)
             :timestamp (now)
             :source src
             :type "occurrence"))

(defn prepare-occ
  [src occ]
  "Prepare to send to elasticsearch"
   (let [id (:occurrenceID occ)]
     [{:index {:_index config/index :_type (:type occ) :_id id}}
      (metadata src occ)]))

(defn make-body
  [src occs] 
  (->> occs
    (fixes/-fix->)
    (map (partial prepare-occ src))
    (flatten)
    (map json/write-str)
    (interpose "\n")
    (apply str)))

(defn bulk-insert
  "Insert/delete/update the bulk of occurrences"
  [src occs]
  (when (> (count occs) 0)
    (let [body (make-body src occs)]
       (try
           (http/post (str config/es "/" config/index "/_bulk") {:body body})
           (log/info "Saved" (count occs) "occurrences from " src)
         (catch Exception e
           (do (log/warn "Error saving" (.getMessage e))
               (log/warn "Error body" body)
               (log/warn e)))))))

(defn run
  "Run the crawler in a single resource(dwca)"
  [source rec]
   (log/info "->" source)
   (let [waiter (chan 1)
         batch  (batcher {:size (* 1 1024)
                          :fn (partial bulk-insert source)
                          :end waiter})]
     (try
       (dwca/read-archive-stream source
         (fn [occ] (>!! batch occ)))
       (catch Exception e
         (log/warn "Failed to read or process" source e)))
     (close! batch)
     (<!! waiter)))

(defn -main
  "Keep on running the bot on all sources. 
   Return an status atom that can swap to :stop to stop the bot."
  [& args] 
   (config/setup)
   (while true
     (do
       (log/info "Bot Active")
       (let [recs  (all-resources)]
         (log/info "Got" (count recs) "resources")
         (doseq [rec recs]
           (log/info "Resource" rec)
           (try
             (run (:dwca rec) rec)
             (catch Exception e (log/warn "Exception runing" rec e)))))
       (log/info "Will rest.")
       (Thread/sleep (* 1 60 60 1000)))))
