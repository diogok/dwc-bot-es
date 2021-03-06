(ns dwc-bot-es.config
  (:require [clj-http.lite.client :as http])
  (:require [clojure.java.io :as io])
  (:require [environ.core :refer (env)])
  (:require [taoensso.timbre :as log]))

(defn config-file
  []
  (let [env   (io/file (or (env :config) "/etc/biodiv/config.ini"))
        base  (io/resource "config.ini")]
    (if (.exists env)
      env
      base)))

(defn cfg
  ([] 
    (with-open [rdr (io/reader (config-file))]
      (->> (line-seq rdr)
           (map #(.trim %))
           (filter #(and (not (nil? %)) (not (empty? %))))
           (map (fn [line] ( .split line "=" )))
           (map (fn [pair] [(keyword (.toLowerCase (.trim (first pair)))) (.trim (last pair))]))
           (map (fn [kv] {(first kv) (or (env (first kv)) (last kv))}))
           (reduce merge {}))))
  ([k] ((cfg) k)))


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
  (let [env   (io/file (or (env :dwc-bot) "/etc/biodiv/dwc-bot.list"))
        base  (io/resource "dwc-bot.list")]
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
        (log/info (str "Waiting: " (cfg :elasticsearch)))
        (let [r (http/get (cfg :elasticsearch)  {:throw-exceptions false})]
          (if (= 200 (:status r))
            (reset! done true)
            (Thread/sleep 1000)))
        (catch Exception e 
          (do
            (log/warn (.toString e))
            (Thread/sleep 1000)))))
    (log/info (str "Done: " (cfg :elasticsearch)))))

(defn setup
  ([] (setup "occurrence")
      (setup "taxon"))
  ([row-type]
    (wait-es)
    (let [mapping (slurp (io/resource (str row-type "_mapping.json" )))]
      (try
        (let [r-idx (http/get (str (cfg :elasticsearch) "/" (cfg :index)) {:throw-exceptions false})
              r-typ (http/get (str (cfg :elasticsearch) "/" (cfg :index) "/_mapping/" row-type) {:throw-exceptions false})]
          (if (= 404 (:status r-idx))
            (log/info 
              (:body
                (http/put (str (cfg :elasticsearch) "/" (cfg :index)) {:throw-exceptions false}))))
          (if (or (= 404 (:status r-typ)) (= "{}" (:body r-typ)))
            (log/info 
              (:body
                (http/put (str (cfg :elasticsearch) "/" (cfg :index) "/_mapping/" row-type)
                  {:body  mapping
                   :throw-exceptions false
                   :headers {"Content-Type" "application/json"}})))))
        (catch Exception e (log/error e))))))

