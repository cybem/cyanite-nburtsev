(ns org.spootnik.cyanite.es_path
  "Implements a path store which tracks metric names backed by elasticsearch"
  (:require [clojure.tools.logging :refer [error warn info debug]]
            [clojure.string        :refer [split] :as str]
            [org.spootnik.cyanite.path :refer [Pathstore]]
            [org.spootnik.cyanite.util :refer [partition-or-time distinct-by
                                               go-forever go-catch counter-inc!
                                               too-many-paths-ex]]
            [org.spootnik.cyanite.es-client :as internal-client]
            [clojurewerkz.elastisch.native :as esn]
            [clojurewerkz.elastisch.native.index :as esni]
            [clojurewerkz.elastisch.native.document :as esnd]
            [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esri]
            [clojurewerkz.elastisch.rest.document :as esrd]
            [clojurewerkz.elastisch.rest.bulk :as esrb]
            [clojurewerkz.elastisch.rest.response :as esrr]
            [clojure.core.async :as async :refer [<! >! go chan]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def ES_DEF_TYPE "path")
(def ES_TYPE_MAP {ES_DEF_TYPE {:_all { :enabled false }
                               :_source { :compress false }
                               :properties {:tenant {:type "string" :index "not_analyzed"}
                                            :path {:type "string" :index "not_analyzed"}}}})
;cache disabled, see impact of batching
(def ^:const store-to-depth 2)
(def sub-path-cache (atom #{}))
(def ^:const period 46)

(defn path-depth
  "Get the depth of a path, with depth + 1 if it ends in a period"
  [path]
  (loop [cnt 1
         from-dex 0]
    (let [dex (.indexOf path period from-dex)]
      (if (= dex -1)
        cnt
        (recur (inc cnt) (inc dex))))))

(defn element
  [path depth leaf tenant]
  {:path path :depth depth :tenant tenant :leaf leaf})

(defn es-all-paths
  "Generate a collection of docs of {path: 'path', leaf: true} documents
  suitable for writing to elastic search"
  ([^String path tenant]
     (let [cache @sub-path-cache]
       (loop [acc []
              depth 1
              from-dex 0]
         (let [nxt-dex (.indexOf path period from-dex)
               leaf (= -1 nxt-dex)]
           (if leaf
             (cons (element path depth leaf tenant)
                   acc)
             (let [sub-path (.substring path 0 nxt-dex)
                   drop (and (>= store-to-depth depth)
                             (cache sub-path))]
               (recur
                (if drop
                  acc
                  (cons
                   (element sub-path depth leaf tenant)
                   acc))
                (inc depth)
                (inc nxt-dex)))))))))

(defn process-wildcards
  "Convert Graphite wildcards to regular expression"
  [path]
  (-> path
      ;; Wildcards
      (str/replace #"\.|\*|\?" {"." "\\." "*" ".*" "?" ".?"})
      ;; Lists
      (str/replace #"\{|\}|," {"{" "(" "}" ")" "," "|"})
      ;; Ranges
      (str/replace #"\[(\d+)-(\d+)\]"
                   (fn [[_ r1s r2s]]
                     (let [r1i (Integer/parseInt r1s)
                           r2i (Integer/parseInt r2s)
                           r1 (apply min [r1i r2i])
                           r2 (apply max [r1i r2i])]
                       (format "(%s)" (str/join "|" (range r1 (inc r2)))))))))

(defn build-es-filter
  "generate the filter portion of an es query"
  [path tenant leafs-only]
  (let [depth (path-depth path)
        p (process-wildcards path)
        f (vector
           {:range {:depth {:from depth :to depth}}}
           {:term {:tenant tenant}}
           {:regexp {:path p :_cache true}})]
    (if leafs-only (conj f {:term {:leaf true}}) f)))

(defn build-es-query
  "generate an ES query to return the proper result set"
  [path tenant leafs-only]
  {:filtered {:filter {:bool {:must (build-es-filter path tenant leafs-only)}}}})

(defn search
  "search for a path"
  [query scroll tenant path leafs-only & [threshold]]
  (let [es-query (build-es-query path tenant leafs-only)]
    (try+
     (let [res (query :query es-query
                      :size 100
                      :search_type "query_then_fetch"
                      :scroll "1m")
           paths-count (esrr/total-hits res)]
       (if (and threshold (> paths-count threshold))
         (throw (too-many-paths-ex :path-search paths-count))
         (map #(:_source %) (scroll res))))
     (catch [:status 400] {:keys [body]}
       (let [err (str "Elasticsearch returns error 400 for the query: " es-query)]
         (warn (:throwable &throw-context) err)
         {:error err})))))

(defn add-path
  "write a path into elasticsearch if it doesn't exist"
  [write-key path-exists? tenant path]
  (let [paths (es-all-paths path tenant)]
    (dorun (map #(if (not (path-exists? (:path %)))
                   (write-key (:path %) %)) paths))))

(defn dont-exist
  [conn index type]
  (fn [paths dos-and-donts-processor]
    (when-let [ids (seq (map #(hash-map :_id (or (str (:tenant %) "_" (:path %)) %)) paths))]
      (internal-client/multi-get
       conn index type ids
       (fn [resp]
         (let [found (set (map :_id (remove nil? resp)))]
           (dos-and-donts-processor
            (reduce (fn [[exist dont] p]
                      (if (found (or (str (:tenant p) "_" (:path p)) p))
                        [(cons p exist) dont]
                        [exist (cons p dont)]))
                    [[] []]
                    paths))))))))

(defn bulk-update
  [conn index type]
  (fn [paths]
    (internal-client/multi-update
     conn index type paths
     #(debug "Failed bulk update, full response: " %))))

(defn es-rest
  [{:keys [index url chan_size batch_size query_paths_threshold]
    :or {index "cyanite_paths" url "http://localhost:9200" chan_size 10000
         batch_size 300 query_paths_threshold nil}}]
  (let [full-path-cache (atom #{})
        conn (esr/connect url)
        dontexistsfn (dont-exist conn index ES_DEF_TYPE)
        bulkupdatefn (bulk-update conn index ES_DEF_TYPE)
        existsfn (partial esrd/present? conn index ES_DEF_TYPE)
        updatefn (partial esrd/put conn index ES_DEF_TYPE)
        scrollfn (partial esrd/scroll-seq conn)
        queryfn (partial esrd/search conn index ES_DEF_TYPE)]
    (if (not (esri/exists? conn index))
      (esri/create conn index :mappings ES_TYPE_MAP))
    (reify Pathstore
      (register [this tenant path]
                (add-path updatefn existsfn tenant path))
      (channel-for [this]
        (let [es-chan (chan chan_size)
              es-chan-p (partition-or-time batch_size es-chan batch_size 10)
              all-paths (chan chan_size)
              all-paths-p (partition-or-time batch_size all-paths batch_size 5)
              create-path (chan chan_size)
              create-path-p (partition-or-time batch_size create-path batch_size 5)]
          (go-forever
           (let [ps (<! es-chan-p)]
             (go-catch
               (doseq [p ps]
                 (doseq [ap (distinct-by :path (es-all-paths (get p 0) (get p 1)))]
                   (>! all-paths ap))))))
          (go-forever
           (let [ps (<! all-paths-p)]
             (dontexistsfn
              ps
              (fn [[exist dont]]
                (go-catch
                 (debug "Fnd " (count exist) ", creating " (count dont))
                 (counter-inc! :index.create (count dont))
                 (doseq [p dont]
                   (>! create-path p))
                 (when (seq exist)
                   (let [cached-sub-paths @sub-path-cache
                         sub-paths-to-store ((comp set (partial map :path) filter)
                                             #(and (>= store-to-depth (:depth %))
                                                   (not (cached-sub-paths (:path %))))
                                             exist)]
                     (swap! sub-path-cache clojure.set/union sub-paths-to-store))))))))
          (go-forever
           (let [ps (<! create-path-p)]
             (bulkupdatefn ps)))
          es-chan))
      (prefixes [this tenant path]
        (search queryfn scrollfn tenant path false query_paths_threshold))
      (lookup [this tenant path]
        (map :path (search queryfn scrollfn tenant path true query_paths_threshold))))))

(defn es-native
  [{:keys [index host port cluster_name chan_size]
    :or {index "cyanite" host "localhost" port 9300 cluster_name "elasticsearch"
         chan_size 10000}}]
  (let [hosts (map #(vector % port) (if (sequential? host) host [host]))
        conn (esn/connect hosts {"cluster.name" cluster_name})
        existsfn (partial esnd/present? conn index ES_DEF_TYPE)
        updatefn (partial esnd/async-put conn index ES_DEF_TYPE)
        scrollfn (partial esnd/scroll-seq conn)
        queryfn (partial esnd/search conn index ES_DEF_TYPE)]
    (info (format "creating elasticsearch path store: %s"
                  (str/join ", " (map #(str/join ":" %) hosts))))
    (if (not (esni/exists? conn index))
      (esni/create conn index :mappings ES_TYPE_MAP))
    (reify Pathstore
      (register [this tenant path]
        (add-path updatefn existsfn tenant path))
      (channel-for [this]
        (let [es-chan (chan chan_size)
              all-paths (chan chan_size)
              create-path (chan chan_size)]
          (go-forever
            (let [p (<! es-chan)]
              (doseq [ap (es-all-paths (get p 0) (get p 1))]
                (>! all-paths ap))))
          (go-forever
            (let [p (<! all-paths)]
              (when-not (existsfn (str (:tenant p) "_" (:path p)))
                (>! create-path p))))
          (go-forever
            (let [p (<! create-path)]
              (updatefn (str (:tenant p) "_" (:path p)) p)))
          es-chan))
      (prefixes [this tenant path]
                (search queryfn scrollfn tenant path false))
      (lookup [this tenant path]
              (map :path (search queryfn scrollfn tenant path true))))))
