(ns cmr.indexer.services.index-service
  "Provide functions to index concept"
  (:require [clojure.string :as s]
            [clj-time.core :as t]
            [cmr.common.time-keeper :as tk]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.concepts :as cs]
            [cmr.common.date-time-parser :as date]
            [cmr.common.cache :as cache]
            [cmr.common.mime-types :as mt]
            [cmr.common.util :as util]
            [cmr.common.config :refer [defconfig]]
            [cmr.transmit.metadata-db :as meta-db]
            [cmr.transmit.metadata-db2 :as meta-db2]
            [cmr.transmit.index-set :as tis]
            [cmr.transmit.echo.rest :as rest]
            [cmr.transmit.cubby :as cubby]
            [cmr.indexer.data.elasticsearch :as es]
            [cmr.elastic-utils.connect :as es-util]
            [cmr.umm.core :as umm]
            [cmr.transmit.metadata-db :as mdb]
            [cmr.message-queue.services.queue :as queue]
            [cheshire.core :as cheshire]
            [cmr.indexer.data.index-set :as idx-set]
            [cmr.indexer.config :as config]
            [cmr.acl.acl-fetcher :as acl-fetcher]
            [cmr.common.services.errors :as errors]
            [cmr.message-queue.config :as qcfg]
            [cmr.indexer.data.elasticsearch :as es]
            [cmr.common.lifecycle :as lifecycle]
            [clojure.edn :as edn]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [cmr.indexer.data.concept-parser :as cp]))

(defconfig use-doc-values-fields
  "Indicates whether search fields should use the doc-values fields or not. If false the field data
  cache fields will be used. This is a temporary configuration to toggle the feature off if there
  are issues. It is duplicated from the search application."
  {:type Boolean
   :default true})

(def query-field->elastic-doc-values-fields
  "Maps the query-field names to the field names used in elasticsearch when using doc-values. Field
  names are excluded from this map if the query field name matches the field name in elastic search."
  {:granule {:provider-id :provider-id-doc-values
             :collection-concept-id :collection-concept-id-doc-values}})

(defn query-field->elastic-field
  "Returns the elastic field name for the equivalent query field name. Duplicated the mappings from
  the search application here."
  [field concept-type]
  (if (use-doc-values-fields)
    (get-in query-field->elastic-doc-values-fields [concept-type field] field)
    field))

(defn filter-expired-concepts
  "Remove concepts that have an expired delete-time."
  [batch]
  (filter (fn [concept]
            (let [delete-time-str (get-in concept [:extra-fields :delete-time])
                  delete-time (when delete-time-str
                                (date/parse-datetime delete-time-str))]
              (or (nil? delete-time)
                  (t/after? delete-time (tk/now)))))
          batch))

(defmulti prepare-batch
  "Returns the batch of concepts into elastic docs for bulk indexing."
  (fn [context batch options]
    (cs/concept-id->type (:concept-id (first batch)))))

(defmethod prepare-batch :default
  [context batch options]
  (es/prepare-batch context (filter-expired-concepts batch) options))

(defmethod prepare-batch :collection
  [context batch options]
  ;; Get the tag associations as well.
  (let [batch (map (fn [concept]
                     (let [tag-associations (mdb/get-tag-associations-for-collection
                                              context concept)]
                       (assoc concept :tag-associations tag-associations)))
                   batch)]
    (es/prepare-batch context (filter-expired-concepts batch) options)))

(defn bulk-index
  "Index many concepts at once using the elastic bulk api. The concepts to be indexed are passed
  directly to this function - it does not retrieve them from metadata db (tag associations for
  collections WILL be retrieved, however). The bulk API is invoked repeatedly if necessary -
  processing batch-size concepts each time. Returns the number of concepts that have been indexed.

  Valid options:
  * :all-revisions-index? - true indicates this should be indexed into the all revisions index
  * :force-version? - true indicates that we should overwrite whatever is in elasticsearch with the
  latest regardless of whether the version in the database is older than the _version in elastic."
  ([context concept-batches]
   (bulk-index context concept-batches nil))
  ([context concept-batches options]
   (reduce (fn [num-indexed batch]
             (let [batch (prepare-batch context batch options)]
               (es/bulk-index-documents context batch options)
               (+ num-indexed (count batch))))
           0
           concept-batches)))

(defn- indexing-applicable?
  "Returns true if indexing is applicable for the given concept-type and all-revisions-index? flag.
  Indexing is applicable for all concept types if all-revisions-index? is false and only for
  collection concept type if all-revisions-index? is true."
  [concept-type all-revisions-index?]
  (if (or (not all-revisions-index?)
          (and all-revisions-index? (contains?  #{:collection :tag-association} concept-type)))
    true
    false))

(def REINDEX_BATCH_SIZE 2000)

(defn reindex-provider-collections
  "Reindexes all the collections in the providers given.

  The optional all-revisions-index? will cause the following behavior changes:
  * nil - both latest and all revisions will be indexed.
  * true - only all revisions will be indexed
  * false - only the latest revisions will be indexed"
  ([context provider-ids]
   (reindex-provider-collections context provider-ids
                                 {:all-revisions-index? nil :refresh-acls? true :force-version? false}))
  ([context provider-ids {:keys [all-revisions-index? refresh-acls? force-version?]}]

   (when refresh-acls?
     ;; Refresh the ACL cache.
     ;; We want the latest permitted groups to be indexed with the collections
     (acl-fetcher/refresh-acl-cache context))

   (doseq [provider-id provider-ids]
     (when (or (nil? all-revisions-index?) (not all-revisions-index?))
       (info "Reindexing latest collections for provider" provider-id)
       (let [latest-collection-batches (meta-db/find-in-batches
                                         context
                                         :collection
                                         REINDEX_BATCH_SIZE
                                         {:provider-id provider-id :latest true})]
         (bulk-index context latest-collection-batches {:all-revisions-index? false
                                                        :force-version? force-version?})))

     (when (or (nil? all-revisions-index?) all-revisions-index?)
       ;; Note that this will not unindex revisions that were removed directly from the database.
       ;; We will handle that with the index management epic.
       (info "Reindexing all collection revisions for provider" provider-id)
       (let [all-revisions-batches (meta-db/find-in-batches
                                     context
                                     :collection
                                     REINDEX_BATCH_SIZE
                                     {:provider-id provider-id})]
         (bulk-index context all-revisions-batches {:all-revisions-index? true
                                                    :force-version? force-version?}))))))

(defn reindex-tags
  "Reindexes all the tags. Only the latest revisions will be indexed"
  [context]
  (info "Reindexing tags")
  (let [latest-tag-batches (meta-db/find-in-batches
                             context
                             :tag
                             REINDEX_BATCH_SIZE
                             {:latest true})]
    (bulk-index context latest-tag-batches)))

(defn- log-ingest-to-index-time
  "Add a log message indicating the time it took to go from ingest to completed indexing."
  [{:keys [concept-id revision-date]}]
  (let [now (tk/now)
        rev-datetime (f/parse (f/formatters :date-time) revision-date)]
    ;; Guard against revision-date that is set to the future by a provider or a test.
    (if (t/before? rev-datetime now)
      ;; WARNING: Splunk is dependent on this log message. DO NOT change this without updating
      ;; Splunk searches used by ops.
      (info (format "Concept [%s] took [%d] ms from start of ingest to become visible in search."
                    concept-id
                    (t/in-millis (t/interval rev-datetime now))))
      (warn (format
              "Cannot compute time from ingest to search visibility for [%s] with revision date [%s]."
              concept-id
              revision-date)))))

(defmulti get-elastic-version-with-tag-associations
  "Returns the elastic version of the concept and its tag associations"
  (fn [context concept tag-associations]
    (:concept-type concept)))

(defmethod get-elastic-version-with-tag-associations :default
  [context concept tag-associations]
  (:revision-id concept))

(defmethod get-elastic-version-with-tag-associations :collection
  [context concept tag-associations]
  (es/get-elastic-version (assoc concept :tag-associations tag-associations)))

(defmulti get-elastic-version
  "Returns the elastic version of the concept"
  (fn [context concept]
    (:concept-type concept)))

(defmethod get-elastic-version :default
  [context concept]
  (:revision-id concept))

(defmethod get-elastic-version :collection
  [context concept]
  (let [tag-associations (mdb/get-tag-associations-for-collection context concept)]
    (get-elastic-version-with-tag-associations context concept tag-associations)))

(defmulti get-tag-associations
  "Returns the tag associations of the concept"
  (fn [context concept]
    (:concept-type concept)))

(defmethod get-tag-associations :default
  [context concept]
  nil)

(defmethod get-tag-associations :collection
  [context concept]
  (mdb/get-tag-associations-for-collection context concept))

(defmulti index-concept
  "Index the given concept with the parsed umm record."
  (fn [context concept parsed-concept options]
    (:concept-type concept)))

(defmethod index-concept :default
  [context concept parsed-concept options]
  (let [{:keys [all-revisions-index?]} options
        {:keys [concept-id revision-id concept-type]} concept]
    (when (indexing-applicable? concept-type all-revisions-index?)
      (info (format "Indexing concept %s, revision-id %s, all-revisions-index? %s"
                    concept-id revision-id all-revisions-index?))
      (let [concept-mapping-types (idx-set/get-concept-mapping-types context)
            delete-time (get-in parsed-concept [:data-provider-timestamps :delete-time])]
        (when (or (nil? delete-time) (t/after? delete-time (tk/now)))
          (let [tag-associations (get-tag-associations context concept)
                elastic-version (get-elastic-version-with-tag-associations
                                  context concept tag-associations)
                tag-associations (map cp/parse-concept (filter #(not (:deleted %)) tag-associations))
                concept-indexes (idx-set/get-concept-index-names context concept-id revision-id
                                                                 options concept)
                es-doc (es/parsed-concept->elastic-doc context
                                                       (assoc concept :tag-associations tag-associations)
                                                       parsed-concept)
                elastic-options (-> options
                                    (select-keys [:all-revisions-index? :ignore-conflict?])
                                    (assoc :ttl (when delete-time
                                                  (t/in-millis (t/interval (tk/now) delete-time)))))]
            (es/save-document-in-elastic
              context
              concept-indexes
              (concept-mapping-types concept-type)
              es-doc
              concept-id
              revision-id
              elastic-version
              elastic-options)))))))

(defmethod index-concept :tag-association
  [context concept parsed-concept options]
  (let [{{:keys [associated-concept-id associated-revision-id]} :extra-fields} concept
        coll-concept (if associated-revision-id
                       (meta-db/get-concept context associated-concept-id associated-revision-id)
                       (meta-db/get-latest-concept context associated-concept-id))
        parsed-coll-concept (cp/parse-concept coll-concept)]
    (index-concept context coll-concept parsed-coll-concept options)))

(defn index-concept-by-concept-id-revision-id
  "Index the given concept and revision-id"
  [context concept-id revision-id options]
  (when-not (and concept-id revision-id)
    (errors/throw-service-error
      :bad-request
      (format "Concept-id %s and revision-id %s cannot be null" concept-id revision-id)))

  (let [{:keys [all-revisions-index?]} options
        concept-type (cs/concept-id->type concept-id)]
    (when (indexing-applicable? concept-type all-revisions-index?)
      (let [concept (meta-db/get-concept context concept-id revision-id)
            parsed-concept (cp/parse-concept concept)]
        (index-concept context concept parsed-concept options)
        (log-ingest-to-index-time concept)))))

(defmulti delete-concept
  "Delete the concept with the given id"
  (fn [context concept-id revision-id options]
    (cs/concept-id->type concept-id)))

(defmethod delete-concept :default
  [context concept-id revision-id options]
  ;; Assuming ingest will pass enough info for deletion
  ;; We should avoid making calls to metadata db to get the necessary info if possible
  (let [{:keys [all-revisions-index?]} options
        concept-type (cs/concept-id->type concept-id)
        concept (meta-db/get-concept context concept-id revision-id)
        elastic-version (get-elastic-version context concept)]
    (when (indexing-applicable? concept-type all-revisions-index?)
      (info (format "Deleting concept %s, revision-id %s, all-revisions-index? %s"
                    concept-id revision-id all-revisions-index?))
      (let [index-names (idx-set/get-concept-index-names
                          context concept-id revision-id options)
            concept-mapping-types (idx-set/get-concept-mapping-types context)
            elastic-options (select-keys options [:all-revisions-index? :ignore-conflict?])]
        (if all-revisions-index?
          ;; save tombstone in all revisions collection index
          (let [es-doc (es/parsed-concept->elastic-doc context concept (:extra-fields concept))]
            (es/save-document-in-elastic
              context index-names (concept-mapping-types concept-type)
              es-doc concept-id revision-id elastic-version elastic-options))
          ;; delete concept from primary concept index
          (do
            (es/delete-document
              context index-names (concept-mapping-types concept-type)
              concept-id revision-id elastic-version elastic-options)
            ;; propagate collection deletion to granules
            (when (= :collection concept-type)
              (doseq [index (idx-set/get-granule-index-names-for-collection context concept-id)]
                (es/delete-by-query
                  context
                  index
                  (concept-mapping-types :granule)
                  {:term {(query-field->elastic-field :collection-concept-id :granule)
                          concept-id}})))))))))

(defmethod delete-concept :tag-association
  [context concept-id revision-id options]
  (let [concept (meta-db/get-concept context concept-id revision-id)]
    ;; When tag association is deleted, we want to re-index the associated collection.
    ;; This is the same thing we do when a tag association is update. So we call the same function.
    (index-concept context concept nil options)))

(defn force-delete-all-collection-revision
  "Removes a collection revision from the all revisions index"
  [context concept-id revision-id]
  (let [index-names (idx-set/get-concept-index-names
                      context concept-id revision-id {:all-revisions-index? true})
        concept-mapping-types (idx-set/get-concept-mapping-types context)
        elastic-options {:ignore-conflict? false
                         :all-revisions-index? true}]
    (es/delete-document
      context
      index-names
      (concept-mapping-types :collection)
      concept-id
      revision-id
      nil ;; Null is sent in as the elastic version because we don't want to set a version for this
      ;; delete. The collection is going to be gone now and should never be indexed again.
      elastic-options)))

(defn delete-provider
  "Delete all the concepts within the given provider"
  [context provider-id]
  ;; Only collections and granules are unindexed here. Other concepts related to the provider
  ;; may be unindexed in other places when a :provider-delete message is handled,
  ;; e.g. unindexing access groups in access-control-app.
  (info (format "Deleting provider-id %s" provider-id))
  (let [{:keys [index-names]} (idx-set/get-concept-type-index-names context)
        concept-mapping-types (idx-set/get-concept-mapping-types context)]
    ;; delete the collections
    (es/delete-by-query
      context
      (get-in index-names [:collection :collections])
      (concept-mapping-types :collection)
      {:term {(query-field->elastic-field :provider-id :collection) provider-id}})
    ;; delete all revisions of collections
    (es/delete-by-query
      context
      (get-in index-names [:collection :all-collection-revisions])
      (concept-mapping-types :collection)
      {:term {(query-field->elastic-field :provider-id :collection) provider-id}})
    ;; delete the granules
    (doseq [index-name (idx-set/get-granule-index-names-for-provider context provider-id)]
      (es/delete-by-query
        context
        index-name
        (concept-mapping-types :granule)
        {:term {(query-field->elastic-field :provider-id :granule) provider-id}}))))

(defn reset
  "Delegates reset elastic indices operation to index-set app as well as resetting caches"
  [context]
  (cache/reset-caches context)
  (es/reset-es-store context)
  (cache/reset-caches context))

(defn update-indexes
  "Updates the index mappings and settings."
  [context]
  (cache/reset-caches context)
  (es/update-indexes context)
  (cache/reset-caches context))

(def health-check-fns
  "A map of keywords to functions to be called for health checks"
  {:elastic_search #(es-util/health % :db)
   :echo rest/health
   :cubby cubby/get-cubby-health
   :metadata-db meta-db2/get-metadata-db-health
   :index-set tis/get-index-set-health
   :rabbit-mq (fn [context]
                (when-let [qb (get-in context [:system :queue-broker])]
                  (queue/health qb)))})

(defn health
  "Returns the health state of the app."
  [context]
  (let [dep-health (util/remove-nil-keys (util/map-values #(% context) health-check-fns))
        ok? (every? :ok? (vals dep-health))]
    {:ok? ok?
     :dependencies dep-health}))
