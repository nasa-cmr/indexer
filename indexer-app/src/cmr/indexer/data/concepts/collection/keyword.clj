(ns cmr.indexer.data.concepts.collection.keyword
  "Contains functions to create keyword fields"
  (:require
    [clojure.string :as str]
    [cmr.common.concepts :as concepts]
    [cmr.common.util :as util]
    [cmr.indexer.data.concepts.attribute :as attrib]
    [cmr.indexer.data.concepts.collection.opendata :as opendata]
    [cmr.indexer.data.concepts.collection.science-keyword :as sk]
    [cmr.umm-spec.location-keywords :as lk]
    [cmr.umm-spec.util :as su]))

;; NOTE -  The following fields are marked as deprecated in the UMM documenation
;; and are therefore not used for keyword searches in the CMR:
;;    SuggestedUsage (ECHO10)
;;

;; Regex to split strings with special characters into multiple words for keyword searches
(def keywords-separator-regex #"[!@#$%^&()\-=_+{}\[\]|;'.,\\\"/:<>?`~* ]")

;; Aliases for NEAR_REAL_TIME
(def nrt-aliases
  ["near_real_time","nrt","near real time","near-real time","near-real-time","near real-time"])

(defn prepare-keyword-field
  [field-value]
  "Convert a string to lowercase then separate it into keywords"
  (when field-value
    (let [field-value (str/lower-case field-value)]
      (into [field-value] (str/split field-value keywords-separator-regex)))))

(defn create-keywords-field
  [concept-id collection other-fields]
  "Create a keyword field for keyword searches by concatenating several other fields
  into a single string"
  (let [{:keys [platform-long-names instrument-long-names entry-id]} other-fields
        {short-name :ShortName version-id :Version entry-title :EntryTitle
         collection-data-type :CollectionDataType summary :Abstract
         temporal-keywords :TemporalKeywords platforms :Platforms
         ancillary-keywords :AncillaryKeywords
         directory-names :DirectoryNames
         iso-topic-categories :ISOTopicCategories
         version-description :VersionDescription
         related-urls :RelatedUrls} collection
        processing-level-id (get-in collection [:ProcessingLevel :Id])
        processing-level-id (when-not (= su/not-provided processing-level-id)
                              processing-level-id)
        spatial-keywords (lk/location-keywords->spatial-keywords
                          (:LocationKeywords collection))
        projects (for [{:keys [ShortName LongName]} (:Projects collection)]
                   {:short-name ShortName :long-name LongName})
        provider-id (:provider-id (concepts/parse-concept-id concept-id))
        collection-data-type (if (= "NEAR_REAL_TIME" collection-data-type)
                               nrt-aliases
                               collection-data-type)
        project-long-names (map :long-name projects)
        project-short-names (map :short-name projects)
        directory-long-names (map :LongName directory-names)
        directory-short-names (map :ShortName directory-names)
        contact-personnel (opendata/get-contact-personnel collection)
        contact-personnel-first-names (map :FirstName contact-personnel)
        contact-personnel-last-names (map :LastName contact-personnel)
        contact-mechanisms (map #(:Value (first %))
                                (map #(get-in % [:ContactInformation :ContactMechanisms]) contact-personnel))
        platforms (map util/map-keys->kebab-case
                       (when-not (= su/not-provided-platforms platforms) platforms))
        platform-short-names (map :short-name platforms)
        instruments (mapcat :instruments platforms)
        instrument-short-names (keep :short-name instruments)
        instrument-techiques (keep :technique instruments)
        sensors (mapcat :sensors instruments)
        sensor-short-names (keep :short-name sensors)
        sensor-long-names (keep :long-name sensors)
        sensor-techniques (keep :technique sensors)
        characteristics (mapcat :characteristics platforms)
        char-names (keep :name characteristics)
        char-descs (keep :description characteristics)
        two-d-coord-names (map :TilingIdentificationSystemName
                               (:TilingIdentificationSystems collection))
        data-centers (map :ShortName (:DataCenters collection))
        science-keywords (mapcat sk/science-keyword->keywords (:ScienceKeywords collection))
        attrib-keywords (mapcat #(attrib/psa->keywords (util/map-keys->kebab-case %))
                                (:AdditionalAttributes collection))
        related-url-urls (mapcat :URLs related-urls)
        related-url-titles (map :Title related-urls)
        related-url-descriptions (map :Description related-urls)
        all-fields (flatten (conj [concept-id]
                                  ancillary-keywords
                                  attrib-keywords
                                  char-descs
                                  char-names
                                  collection-data-type
                                  contact-personnel-first-names
                                  contact-personnel-last-names
                                  contact-mechanisms
                                  data-centers
                                  directory-long-names
                                  directory-short-names
                                  entry-id
                                  entry-title
                                  instrument-long-names
                                  instrument-short-names
                                  instrument-techiques
                                  iso-topic-categories
                                  platform-long-names
                                  platform-short-names
                                  processing-level-id
                                  project-long-names
                                  project-short-names
                                  provider-id
                                  related-url-descriptions
                                  related-url-titles
                                  related-url-urls
                                  science-keywords
                                  sensor-long-names
                                  sensor-short-names
                                  sensor-techniques
                                  short-name
                                  spatial-keywords
                                  summary
                                  temporal-keywords
                                  two-d-coord-names
                                  version-description
                                  version-id))
        split-fields (set (mapcat prepare-keyword-field all-fields))]
    (str/join " " split-fields)))
