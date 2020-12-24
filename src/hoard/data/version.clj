(ns hoard.data.version
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [hoard.file.tsv :as tsv]
    [hoard.file.core :as f]
    [multiformats.hash :as multihash])
  (:import
    (java.time
      Instant
      LocalDateTime
      ZoneOffset)
    java.time.temporal.ChronoField
    multiformats.hash.Multihash))


;; ## Version Identifier

;; Unique time-based identifier for the version.
(s/def ::id
  string?)


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



;; ## Version Metadata

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


(defn file-meta
  "Construct a map of version metadata from a file."
  [file]
  (let [id (f/file-name file)]
    {::id id
     ::size (f/size file)
     ::created-at (parse-id-inst id)}))



;; ## Index Entries

;; Relative path of the file in the tree being archived.
(s/def :hoard.data.version.index/path
  string?)


;; Type of file entry being archived.
(s/def :hoard.data.version.index/type
  #{:directory :file :symlink})


;; Original size in bytes of the file being archived.
(s/def :hoard.data.version.index/size
  nat-int?)


;; Octal bitmask representing the entry's permissions.
(s/def :hoard.data.version.index/permissions
  (s/int-in 0 512))


;; Last modified time of the file being archived.
(s/def :hoard.data.version.index/modified-at
  inst?)


;; If the file is a link, the target path.
(s/def :hoard.data.version.index/target
  string?)


;; Multihash digest of the original file content.
(s/def :hoard.data.version.index/content-id
  #(instance? Multihash %))


;; Multihash digest of the encoded file content.
(s/def :hoard.data.version.index/coded-id
  #(instance? Multihash %))


;; Map of data for an index entry.
(s/def ::index-entry
  (s/keys :req-un [:hoard.data.version.index/path
                   :hoard.data.version.index/type
                   :hoard.data.version.index/permissions
                   :hoard.data.version.index/modified-at]
          :opt-un [:hoard.data.version.index/size
                   :hoard.data.version.index/target
                   :hoard.data.version.index/content-id
                   :hoard.data.version.index/coded-id]))


;; Sequence of index entry data.
(s/def ::index
  (s/coll-of ::index-entry :kind sequential?))



;; ## File Format

;; ### v1

;; Indexes are stored as line-based text files in their plain form. The first
;; line of the file gives the file version. The second line contains
;; the column headers. Every subsequent line is an entry in the index,
;; containing the column values.

(def ^:private ^:const v1-format
  "hoard.data.version/v1")


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
   {:name :coded-id
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
    (println v1-format)
    (flush))
  (tsv/write-data! out v1-columns entries))


;; ### General Format

(defn write-data!
  "Write version data to the given output."
  [out version]
  (v1-write! out (::index version)))


(defn read-data
  "Read index header and entry data."
  [in]
  (with-open [reader (io/reader in)]
    (let [[format-header & lines] (line-seq reader)]
      (condp = format-header
        v1-format
        {::index (vec (tsv/read-lines v1-columns lines))}

        ;; else
        (throw (ex-info (str "Unsupported version file format: "
                             format-header)
                        {:header format-header}))))))
