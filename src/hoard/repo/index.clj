(ns hoard.repo.index
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [hoard.file.tsv :as tsv]
    [multiformats.hash :as multihash])
  (:import
    java.time.Instant
    multiformats.hash.Multihash))


;; ## Specs

;; Relative path of the file in the tree being archived.
(s/def :hoard.repo.index.entry/path
  string?)


;; Type of file entry being archived.
(s/def :hoard.repo.index.entry/type
  #{:directory :file :symlink})


;; Original size in bytes of the file being archived.
(s/def :hoard.repo.index.entry/size
  nat-int?)


;; Octal bitmask representing the entry's permissions.
(s/def :hoard.repo.index.entry/permissions
  (s/int-in 0 512))


;; Last modified time of the file being archived.
(s/def :hoard.repo.index.entry/modified-at
  inst?)


;; If the file is a link, the target path.
(s/def :hoard.repo.index.entry/target
  string?)


;; Multihash digest of the original file content.
(s/def :hoard.repo.index.entry/content-id
  #(instance? Multihash %))


;; Multihash digest of the encrypted file content.
(s/def :hoard.repo.index.entry/crypt-id
  #(instance? Multihash %))


;; Map of data for an index entry.
(s/def ::entry
  (s/keys :req-un [:hoard.repo.index.entry/path
                   :hoard.repo.index.entry/type
                   :hoard.repo.index.entry/permissions
                   :hoard.repo.index.entry/modified-at]
          :opt-un [:hoard.repo.index.entry/size
                   :hoard.repo.index.entry/target
                   :hoard.repo.index.entry/content-id
                   :hoard.repo.index.entry/crypt-id]))


;; Sequence of entry data.
(s/def ::entries
  (s/coll-of ::entry :kind sequential?))



;; ## Utilities

;; TODO: write some index data manipulation functions:
;; - generate various index structures, e.g. by path, content-id, crypt-id
;; - find an entry by matching attributes



;; ## File Format

;; ### v1

;; Indexes are stored as line-based text files in their plain form. The first
;; line of the file gives the file version. The second line contains
;; the column headers. Every subsequent line is an entry in the index,
;; containing the column values.

(def ^:private ^:const v1-version
  "hoard.repo.index/v1")


(def ^:private v1-columns
  "Sequence of column definitions for serialization of index data."
  [{:name :path}
   {:name :type
    :encode name
    :decode keyword}
   {:name :size
    :decode tsv/parse-long}
   {:name :permissions
    :decode tsv/parse-long}
   {:name :modified-at
    :decode tsv/parse-inst}
   {:name :content-id
    :encode multihash/hex
    :decode multihash/parse}
   {:name :crypt-id
    :encode multihash/hex
    :decode multihash/parse}
   {:name :target}])


(defn- v1-write!
  "Write the header and index entries to the given output stream."
  [out entries]
  (when-not (s/valid? ::entries entries)
    (throw (ex-info (str "Cannot write invalid index entry data: "
                         (s/explain-str ::entries entries))
                    {})))
  (binding [*out* (io/writer out)]
    (println v1-version)
    (flush))
  (tsv/write-data! out v1-columns entries))


;; ### General Format

(defn write-data!
  "Write index data to the given output."
  [out entries]
  (v1-write! out entries))


(defn read-data
  "Read index header and entry data."
  [in]
  (let [[version & lines] (line-seq (io/reader in))]
    (condp = version
      v1-version
      (vec (tsv/read-lines v1-columns lines))

      ;; else
      (throw (ex-info (str "Unsupported index file version: "
                           version)
                      {:version version})))))
