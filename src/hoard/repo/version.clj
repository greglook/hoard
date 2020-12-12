(ns hoard.repo.version
  (:require
    [clojure.spec.alpha :as s]
    [hoard.repo.index :as index])
  (:import
    java.time.LocalDateTime
    java.time.temporal.ChronoField))


;; ## Specs

;; Unique time-based identifier for the version.
(s/def ::id
  string?)


;; Number of file entries present in the version.
(s/def ::count
  nat-int?)


;; Total byte size of files represented in the version.
(s/def ::size
  nat-int?)


;; Map of version metadata attributes.
(s/def ::meta
  (s/keys :req [::id]
          :opt [::count
                ::size]))


;; Sequence of index data entries.
(s/def ::index
  (s/coll-of ::index/entry :kind vector?))



;; ## Utilities

(defn gen-id
  "Generate a new version index identifier."
  []
  (let [alphabet "23456789abcdefghjkmnpqrstuvwxyz"
        now (LocalDateTime/now)]
    (format "%04d%02d%02d-%05d-%s"
            (.get now ChronoField/YEAR)
            (.get now ChronoField/MONTH_OF_YEAR)
            (.get now ChronoField/DAY_OF_MONTH)
            (.get now ChronoField/SECOND_OF_DAY)
            (apply str (repeatedly 5 #(rand-nth alphabet))))))
