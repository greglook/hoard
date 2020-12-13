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
                   :hoard.repo.index.entry/size
                   :hoard.repo.index.entry/permissions
                   :hoard.repo.index.entry/modified-at
                   :hoard.repo.index.entry/content-id
                   :hoard.repo.index.entry/crypt-id]))


;; Sequence of entry data.
(s/def ::entries
  (s/coll-of ::entry :kind vector?))



;; ## File Format

;; ### v1

;; Indexes are stored as line-based text files which are gzipped and then
;; encrypted.
;;
;; The first line of the file gives the file version, which for v1 is
;; `hoard.repo.index/v1`. The second line contains tab-separated key/value
;; pairs of index header metadata. Every subsequent line is an entry in the
;; index, containing the column values.

(def ^:private ^:const v1-version
  "hoard.repo.index/v1")


(defn- v1-format-header
  "Convert a map of index metadata into a header line."
  [metadata entries]
  (->>
    (assoc metadata
           :count (count entries)
           :size (apply + (keep :size entries)))
    (map #(str (name (first %)) "=" (second %)))
    (str/join "\t")))


(defn- v1-parse-header
  "Parse a line of header metadata from a v1 file."
  [header-line]
  (->>
    (str/split header-line #"\t")
    (map #(str/split % #"="))
    (map (juxt (comp keyword first) second))
    (into {})))


(defn- v1-format-row
  "Convert an index entry map into a string row data."
  [entry]
  (str/join
    "\t"
    [(mhash/hex (:content-id entry))
     (mhash/hex (:crypt-id entry))
     (:path entry)
     (name (:type entry))
     (:size entry)
     (:permissions entry)
     (:modified-at entry)]))


(defn- v1-parse-row
  "Parse a string of row data into an index entry map."
  [row]
  (let [[content-id crypt-id path file-type size permissions modified-at] (str/split row #"\t")]
    {:content-id (mhash/parse content-id)
     :crypt-id (mhash/parse crypt-id)
     :path path
     :type (keyword file-type)
     :size (Long/parseLong size)
     :permissions (Integer/parseInt permissions)
     :modified-at (Instant/parse modified-at)}))


(defn v1-write!
  "Write the header and index entries to the given output stream."
  [out metadata entries]
  (when-not (s/valid? ::entries entries)
    (throw (ex-info (str "Cannot write invalid index entry data: "
                         (s/explain-str ::entries entries))
                    {})))
  (binding [*out* (io/writer out)]
    (println v1-version)
    (println (v1-format-header metadata entries))
    (->>
      entries
      (sort-by :path)
      (map v1-format-row)
      (run! println))
    (flush)))


(defn- v1-read-lines
  "Read the contents of an index file. Returns a vector containing the header
  metadata as the first entry and the entry data as the second entry."
  [lines]
  (let [[header & rows] lines]
    [(v1-parse-header header)
     (mapv v1-parse-row rows)]))


;; ### General Format

(defn read-header
  "Read header metadata from the index file input content."
  [in]
  (binding [*in* (io/reader in)]
    (let [version (read-line)]
      (condp = version
        v1-version
        (v1-parse-header (read-line))

        ;; else
        (throw (ex-info (str "Unsupported index file version: "
                             version)
                        {:version version}))))))


(defn read-data
  "Read index header metadata and entry data."
  [in]
  (let [[version & lines] (line-seq (io/reader in))]
    (condp = version
      v1-version
      (v1-read-lines lines)

      ;; else
      (throw (ex-info (str "Unsupported index file version: "
                           version)
                      {:version version})))))
