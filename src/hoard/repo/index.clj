(ns hoard.repo.index
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [multiformats.hash :as mhash])
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
  inst?)


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
  (s/coll-of ::entry :kind vector?))



;; ## Utilities

;; TODO: write some index data manipulation functions:
;; - generate various index structures, e.g. by path, content-id, crypt-id
;; - find an entry by matching attributes



;; ## File Format

(defn- format-header
  "Format a row of column headers."
  [columns]
  (->>
    columns
    (map (comp name :name) columns)
    (str/join "\t")))


(defn- format-row
  "Format a row of TSV using the given column definitions. Returns the row
  string encoding the entry data."
  [columns entry]
  (->>
    columns
    (map (fn encode-cell
           [column]
           (if-some [value (get entry (:name column))]
             (let [encode (:encode column str)]
               (encode value))
             "")))
    (str/join "\t")))


(defn- parse-row
  "Parse a row of TSV using the given column definitions. Returns the entry
  data decoded from the row."
  [columns row]
  (->>
    (str/split row #"\t")
    (map vector columns)
    (keep (fn decode-cell
            [column cell]
            (when-not (str/blank? cell)
              [(:name column)
               (let [decode (:decode column identity)]
                 (decode cell))])))
    (into {})))


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
    :decode #(Long/parseLong %)}
   {:name :permissions
    :decode #(Integer/parseInt %)}
   {:name :modified-at
    :decode #(Instant/parse %)}
   {:name :content-id
    :encode mhash/hex
    :decode mhash/parse}
   {:name :crypt-id
    :encode mhash/hex
    :decode mhash/parse}
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
    (println (format-header v1-columns))
    (->>
      entries
      (sort-by :path)
      (map (partial format-row v1-columns))
      (run! println))
    (flush)))


(defn- v1-read-lines
  "Read the contents of an index file. Returns a vector of the entry data."
  [lines]
  (let [expected (format-header v1-columns)
        [header & rows] lines]
    (when (not= expected header)
      (throw (ex-info (str "Unexpected header reading v1 index: "
                           (pr-str header))
                      {:header header
                       :expected expected})))
    (mapv (partial parse-row v1-columns) rows)))


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
      (v1-read-lines lines)

      ;; else
      (throw (ex-info (str "Unsupported index file version: "
                           version)
                      {:version version})))))
