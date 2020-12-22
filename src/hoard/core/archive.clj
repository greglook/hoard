(ns hoard.core.archive
  "Functions for managing the configuration and state in an archive's working
  tree."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [hoard.core.version :as version]
    [hoard.file.core :as f]
    [hoard.file.tsv :as tsv]
    [multiformats.hash :as multihash])
  (:import
    java.io.File))


;; ## Specs

;; Unique identifying name for the archive.
(s/def ::name
  string?)


;; Root file location of the archive.
(s/def ::root
  (partial instance? File))


;; When the archive was first initialized.
(s/def ::created-at
  inst?)


;; Command used to encrypt files before storage.
(s/def ::encrypt
  (s/coll-of string? :kind vector?))


;; Command used to decrypt files from storage.
(s/def ::decrypt
  (s/coll-of string? :kind vector?))


;; Set of file ignore patterns.
(s/def ::ignore
  (s/coll-of string? :kind set?))


;; Sequence of versions of the archive.
(s/def ::versions
  (s/coll-of ::version/meta :kind vector?))



;; ## Configuration

(defn- read-config
  "Load configuration from a file under the `.hoard` directory."
  [^File file]
  ;; TODO: implement
  {})



;; ## Ignored Files

(defn- ignored-predicate
  "Construct a predicate function which will return true on files that match
  the ignored configuration.

  The ignore rules are expressed as a set of strings:
  - If the value contains no `/` characters, it matches if the candidate file
    name is equal to the value.
  - If the value _starts_ with a `/`, it matches the candidate file at that
    path relative to the root.
  - Otherwise, the value matches if the candidate file path _ends with_ the
    value."
  [^File root ignored]
  ;; OPTIMIZE: there's some precomputation that could happen here, like
  ;; building a set lookup for exact matches.
  ;; TODO: support wildcard globs
  (fn ignore?
    [^File file]
    (boolean (some (fn match
                     [rule]
                     (cond
                       (not (str/includes? rule File/pathSeparator))
                       (= rule (f/file-name file))

                       (str/starts-with? rule File/pathSeparator)
                       (= (f/canonical file)
                          (io/file (f/canonical root)
                                   (f/chomp-separator rule)))

                       :else
                       (str/ends-with?
                         (str (f/canonical file))
                         (f/chomp-separator rule))))
                   ignored))))


(defn- read-ignores
  "Read a file of ignore patterns, or nil if the file does not exist or is not
  readable."
  [^File ignore]
  (when (and (f/file? ignore) (f/readable? ignore))
    (with-open [patterns (io/reader ignore)]
      (into #{}
            (comp
              (map str/trim)
              (remove str/blank?)
              (remove #(str/starts-with? % "#")))
            (line-seq patterns)))))



;; ## Archive Versions

(defn- list-versions
  "List the versions present in an archive directory."
  [^File version-dir]
  (into []
        (map (fn version-meta
               [^File version]
               (let [id (f/file-name version)]
                 {::version/id id
                  ::version/size (f/size version)
                  ::version/created-at (version/parse-id-inst id)})))
        (f/list-files version-dir)))



;; ## Archive Directories

(defn- load-archive
  "Load a map of archive configuration from the given `.hoard` directory."
  [^File dir]
  (assoc (read-config (io/file dir "config"))
         ::root (f/parent (f/canonical dir))
         ::ignore (or (read-ignores (io/file dir "ignore")) #{})
         ::versions (list-versions (io/file dir "versions"))))


(defn- archive-file
  "Return the file representing the given path into the archive's hidden
  directory."
  [archive & path]
  (apply io/file (::root archive) ".hoard" path))


(defn find-root
  "Find up from the given directory to locate the hoard archive root. Returns a
  map of information about the archive, or nil if no archive root is found.

  The search will will terminate after `limit` recursions or once it hits the
  filesystem root or a directory the user can't read."
  [^File dir limit]
  (when (and dir
             (f/directory? dir)
             (f/readable? dir)
             (pos? limit))
    (let [archive-dir (io/file dir ".hoard")]
      (if (f/directory? archive-dir)
        (load-archive archive-dir)
        (recur (f/parent (f/canonical dir)) (dec limit))))))



;; ## File Walking

(defn- file-stats
  "Return a map of stats about a file at the given path."
  [^File file]
  (cond
    (f/symlink? file)
    {:type :symlink
     :file file
     :target (f/link-target file)
     :permissions (f/permissions file)
     :modified-at (f/last-modified file)}

    (f/directory? file)
    {:type :directory
     :file file
     :permissions (f/permissions file)
     :modified-at (f/last-modified file)}

    (f/file? file)
    {:type :file
     :file file
     :size (f/size file)
     :permissions (f/permissions file)
     :modified-at (f/last-modified file)}

    :else
    {:type :unknown
     :file file}))


(defn- scan-files
  "Walk a filesystem depth-first, returning a sequence of file metadata. This
  includes the (relative) path, file type, size, permissions, and modified
  time."
  [archive]
  (let [root (::root archive)
        root-path (.toPath ^File root)
        ignore? (ignored-predicate
                  root
                  (conj (::ignore archive #{}) ".hoard"))]
    (->>
      (f/walk-files ignore? root)
      (drop 1)
      (map file-stats)
      (map (fn relativize-path
             [stats]
             (let [file ^File (:file stats)
                   rel-path (.relativize root-path (.toPath file))]
               (assoc stats :path (str rel-path))))))))



;; ## File Hashing

(def ^:private cache-columns
  "Sequence of columns for cache records."
  [{:name :path}
   {:name :size
    :decode tsv/parse-long}
   {:name :modified-at
    :decode tsv/parse-inst}
   {:name :content-id
    :encode multihash/hex
    :decode multihash/parse}])


(defn- build-cache
  "Construct a cache map from the provided file stats."
  [stats]
  (into (sorted-map)
        (comp
          (filter :content-id)
          (let [cache-keys (mapv :name cache-columns)]
            (map (juxt :path #(select-keys % cache-keys)))))
        stats))


(defn- read-cache
  "Read cache data from a file, returning a map from paths to cache records."
  [^File file]
  (when (f/exists? file)
    (with-open [input (io/input-stream file)]
      (build-cache (tsv/read-data input cache-columns)))))


(defn- write-cache!
  "Write cache data to a file."
  [file cache]
  (when (and file (seq cache))
    (with-open [output (io/output-stream file)]
      (tsv/write-data!
        output
        cache-columns
        (vals cache)))))


(defn- hash-file
  "If the provided map of stats represents a regular file, augment it by
  computing the content hash. Returns the map with a `:content-id` multihash,
  or the original map if it was not a file."
  [cache stats]
  (if (and (identical? :file (:type stats))
           (pos-int? (:size stats)))
    (let [cached (get cache (:path stats))]
      (if (and cached
               (= (:size cached) (:size stats))
               (= (:modified-at cached) (:modified-at stats)))
        (assoc stats :content-id (:content-id cached))
        ;; TODO: measure time spent hashing?
        (with-open [input (io/input-stream (:file stats))]
          (assoc stats :content-id (multihash/sha2-256 input)))))
    stats))



;; ## Index Construction

(defn build-index
  "Build an index of the file tree under the root."
  [archive]
  (let [cache-file (archive-file archive "cache" "tree")
        cache (read-cache cache-file)
        stats (->>
                (scan-files archive)
                (map (partial hash-file cache))
                (sort-by :path)
                (vec))
        cache' (build-cache stats)]
    (when (not= cache cache')
      (write-cache! cache-file cache'))
    stats))
