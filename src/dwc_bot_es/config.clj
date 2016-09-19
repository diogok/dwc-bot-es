(ns dwc-bot-es.config
  (:require [clj-http.lite.client :as http])
  (:require [clojure.java.io :as io])
  (:require [environ.core :refer (env)])
  (:require [taoensso.timbre :as log]))

(def es (or (env :elasticsearch) "http://localhost:9200"))
(def index (or (env :index) "dwc"))

(defn load-base-inputs-0
  "Load a config file list into a list"
  [file] 
  (with-open [rdr (io/reader file)]
    (doall
      (filter 
        #(and (not (empty? %)) (not (nil? %)))
        (map #(.trim %)
        (line-seq rdr))))))

(defn inputs
  "Load sources from defined config file"
  [] 
  (let [env   (io/file (or (env :sources) "/etc/biodiv/sources.list"))
        base  (io/resource "sources.list")]
    (if (.exists env)
      (do 
        (log/info env)
        (load-base-inputs-0 env))
      (do
        (log/info base)
        (load-base-inputs-0 base)))))

(defn wait-es
  "Wait for ElasticSearch to be ready"
  []
  (let [done (atom false)]
    (while (not @done)
      (try 
        (log/info (str "Waiting: " (str es "/" index )))
        (let [r (http/get (str es "/" index) {:throw-exceptions false})]
          (if (= 200 (:status r))
            (reset! done true)
            (Thread/sleep 1000)))
        (catch Exception e 
          (do
            (log/warn (.toString e))
            (Thread/sleep 1000)))))
    (log/info (str "Done: " es))))

(defn setup
  []
  (wait-es)
  (let [mapping (slurp (io/resource "occurrence_mapping.json"))]
    (try
      (log/info 
        (:body
          (http/put (str es "/" index "/_mapping/occurrence")
            {:body  mapping
             :headers {"Content-Type" "application/json"}})))
      (catch Exception e (log/error e)))))
