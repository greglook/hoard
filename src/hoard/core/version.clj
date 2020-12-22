(ns hoard.core.version
  (:require
    [clojure.spec.alpha :as s]
    [hoard.repo.index :as index])
  (:import
    (java.time
      Instant
      LocalDateTime
      ZoneOffset)
    java.time.temporal.ChronoField))


;; ## Specs

;; Unique time-based identifier for the version.
(s/def ::id
  string?)


;; Storage size in bytes used by the version data.
(s/def ::size
  nat-int?)


;; Time the version was created.
(s/def ::created-at
  inst?)


;; Number of file entries present in the version.
(s/def ::tree-count
  nat-int?)


;; Total byte size of files represented in the version.
(s/def ::tree-size
  nat-int?)


;; Map of version metadata attributes.
(s/def ::meta
  (s/keys :req [::id
                ::size
                ::created-at]
          :opt [::tree-count
                ::tree-size]))


;; Sequence of index data entries.
(s/def ::index
  ::index/entries)



;; ## Utilities

(defn gen-id
  "Generate a new version index identifier."
  ([]
   (gen-id (Instant/now)))
  ([^Instant now]
   (let [alphabet "23456789abcdefghjkmnpqrstuvwxyz"
         local-now (LocalDateTime/ofInstant now ZoneOffset/UTC)]
     (format "%04d%02d%02d-%05d-%s"
             (.get local-now ChronoField/YEAR)
             (.get local-now ChronoField/MONTH_OF_YEAR)
             (.get local-now ChronoField/DAY_OF_MONTH)
             (.get local-now ChronoField/SECOND_OF_DAY)
             (apply str (repeatedly 5 #(rand-nth alphabet)))))))


(defn parse-id-inst
  "Parse a version identifier and return the instant in time it was created.
  Returns nil if the id pattern is not recognized."
  [id]
  (when-let [[_ year month day seconds] (re-matches #"^(\d{4})(\d\d)(\d\d)-(\d{5})-[a-z0-9]+$" id)]
    (let [seconds (Integer/parseInt seconds)
          ldt (LocalDateTime/of
                (Integer/parseInt year)
                (Integer/parseInt month)
                (Integer/parseInt day)
                (int (/ seconds 3600))
                (int (/ (mod seconds 3600) 60))
                (int (mod seconds 60)))]
      (.toInstant ldt ZoneOffset/UTC))))
