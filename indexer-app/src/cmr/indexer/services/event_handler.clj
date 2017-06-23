(ns cmr.indexer.services.event-handler
  "Provides functions for subscribing to and handling events."
  (:require
   [cmr.common.lifecycle :as lifecycle]
   [cmr.indexer.config :as config]
   [cmr.indexer.services.index-service :as indexer]
   [cmr.indexer.data.collection-granule-aggregation-cache :as cgac]
   [cmr.message-queue.queue.queue-protocol :as queue-protocol]
   [cmr.common.log :refer (debug info warn error)]
   [cmr.common.services.errors :as errors]
   [cmr.common.concepts :as cc]))

;; Isolating provider events from other ingest events to prevent them from ever being processed
;; by the normal ingest handlers as that can lead to a swamped ingest queue. These handlers
;; are reserved for the provider queue which is configured to handle long processing times.
(defmulti handle-provider-event
  "Handle the various actions that can be requested for a provider via the provider-queue"
  (fn [context msg]
    (keyword (:action msg))))

(defmethod handle-provider-event :provider-collection-reindexing
  [context {:keys [provider-id force-version? all-revisions-index?]}]
  ;; We set the refresh acls flag to false because the ACLs should have been refreshed as part
  ;; of the ingest job that kicks this off.
  (indexer/reindex-provider-collections
    context [provider-id] {:all-revisions-index? all-revisions-index?
                           :refresh-acls? false
                           :force-version? force-version?}))

(defmethod handle-provider-event :refresh-collection-granule-aggregation-cache
  [context {:keys [granules-updated-in-last-n]}]
  (cgac/refresh-cache context granules-updated-in-last-n))

(defmethod handle-provider-event :provider-delete
  [context {:keys [provider-id]}]
  (indexer/delete-provider context provider-id))

;; Default ignores the provider event. There may be provider events we don't care about.
(defmethod handle-provider-event :default
  [_ _])

(defmulti handle-ingest-event
  "Handle the various actions that can be requested via the indexing queue"
  (fn [context all-revisions-index? msg]
    (keyword (:action msg))))

;; Default ignores the ingest event. There may be ingest events we don't care about.
(defmethod handle-ingest-event :default
  [_ _ _])

(defmethod handle-ingest-event :concept-update
  [context all-revisions-index? {:keys [concept-id revision-id]}]
  (if (= :humanizer (cc/concept-id->type concept-id))
    (indexer/update-humanizers context)
    (indexer/index-concept-by-concept-id-revision-id
     context concept-id revision-id {:ignore-conflict? true
                                     :all-revisions-index? all-revisions-index?})))

(defmethod handle-ingest-event :concept-delete
  [context all-revisions-index? {:keys [concept-id revision-id]}]
  (when-not (= :humanizer (cc/concept-id->type concept-id))
    (indexer/delete-concept
      context concept-id revision-id {:ignore-conflict? true
                                      :all-revisions-index? all-revisions-index?})))

(defmethod handle-ingest-event :concept-revision-delete
  [context all-revisions-index? {:keys [concept-id revision-id]}]
  (when-not (= :humanizer (cc/concept-id->type concept-id))
    (do
      ;; We should never receive a message that's not for the all revisions index
      (when-not all-revisions-index?
        (errors/internal-error!
          (format (str "Received :concept-revision-delete event that wasn't for the all revisions "
                       "index.  concept-id: %s revision-id: %s")
                  concept-id revision-id)))
      (indexer/force-delete-all-collection-revision context concept-id revision-id))))

(defn subscribe-to-events
  "Subscribe to event messages on various queues"
  [context]
  (let [queue-broker (get-in context [:system :queue-broker])]
    (dotimes [n (config/provider-queue-listener-count)]
      (queue-protocol/subscribe queue-broker
                                (config/provider-queue-name)
                                #(handle-provider-event context %)))
    (dotimes [n (config/index-queue-listener-count)]
      (queue-protocol/subscribe queue-broker
                                (config/index-queue-name)
                                #(handle-ingest-event context false %)))
    (dotimes [n (config/all-revisions-index-queue-listener-count)]
      (queue-protocol/subscribe queue-broker
                                (config/all-revisions-index-queue-name)
                                #(handle-ingest-event context true %)))))
