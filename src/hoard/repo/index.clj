(ns hoard.repo.index
  (:require
    [clojure.spec.alpha :as s]
    [multiformats.hash :as mhash])
  (:import
    multiformats.hash.Multihash))


;; ## Specs

;; Unique time-based identifier for the version index.
(s/def ::id
  string?)


;; Number of file entries present in the index.
(s/def ::count
  nat-int?)


;; Total byte size of files represented in the index.
(s/def ::size
  nat-int?)


;; Map of index metadata attributes.
(s/def ::meta
  (s/keys :req [::id]
          :opt [::count
                ::size]))


;; Relative path of the file in the tree being archived.
(s/def :hoard.repo.index.entry/path
  string?)


;; Original size in bytes of the file being archived.
(s/def :hoard.repo.index.entry/size
  nat-int?)


;; Multihash digest of the original file content.
(s/def :hoard.repo.index.entry/content-id
  #(instance? Multihash %))


;; Multihash digest of the encrypted file content.
(s/def :hoard.repo.index.entry/crypt-id
  #(instance? Multihash %))


;; Map of data for an index entry.
(s/def :hoard.repo.index.entry/data
  (s/keys :req-un [:hoard.repo.index.entry/path
                   :hoard.repo.index.entry/size
                   :hoard.repo.index.entry/content-id
                   :hoard.repo.index.entry/crypt-id]))


;; Sequence of index data entries.
(s/def ::entries
  (s/coll-of :hoard.repo.index.entry/data :kind vector?))



;; ## Utilities

(defn gen-id
  "Generate a new version index identifier."
  []
  ;; TODO: want {yyyy}{mm}{dd}-{seconds-in-day}-{4 random letters}
  (throw (RuntimeException. "NYI")))
