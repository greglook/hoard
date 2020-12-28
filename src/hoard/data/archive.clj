(ns hoard.data.archive
  "Functions for managing the configuration and state in an archive's working
  tree."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [hoard.data.version :as version]
    [hoard.file.core :as f]
    [hoard.file.ini :as ini]
    [hoard.file.tsv :as tsv]
    [multiformats.hash :as multihash])
  (:import
    java.io.File
    java.time.Instant))


;; ## Data Specs

;; Unique identifying name for the archive.
(s/def ::name
  string?)


;; Root file location of the archive.
(s/def ::root
  (partial instance? File))


;; When the archive was first initialized.
(s/def ::created-at
  inst?)


;; Command used to encode files before storage.
(s/def ::encode-command
  (s/coll-of string? :kind vector?))


;; Command used to decode files from storage.
(s/def ::decode-command
  (s/coll-of string? :kind vector?))


;; Set of file ignore patterns.
(s/def ::ignore
  (s/coll-of string? :kind set?))


;; Sequence of versions of the archive.
(s/def ::versions
  (s/coll-of ::version/meta :kind vector?))



;; ## Archive Config

(defn- hoard-file
  "Return the file representing the given path into the archive's hidden
  `.hoard` directory."
  ^File
  [archive & path]
  (apply io/file (::root archive) ".hoard" path))


(defn read-config
  "Load configuration from the `.hoard` directory."
  [archive]
  (let [config-file (hoard-file archive "config")]
    (when (and (f/file? config-file) (f/readable? config-file))
      (into {}
            (map (fn namespace-key
                   [[k v]]
                   [(keyword "hoard.data.archive" (name k))
                    (case k
                      :created-at
                      (try
                        ;; TODO: auto-parse in ini ns?
                        (Instant/parse v)
                        (catch Exception ex
                          ;; TODO: warn
                          nil))

                      ;; TODO: support quoting?
                      (:encode-command :decode-command)
                      (str/split v #" +")

                      ;; else
                      v)]))
            (ini/read config-file)))))



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


(defn read-ignores
  "Read a file of ignore patterns, or nil if the file does not exist or is not
  readable."
  [archive]
  (let [ignore-file (hoard-file archive "ignore")]
    (when (and (f/file? ignore-file) (f/readable? ignore-file))
      (with-open [patterns (io/reader ignore-file)]
        (into #{}
              (comp
                (map str/trim)
                (remove str/blank?)
                (remove #(str/starts-with? % "#")))
              (line-seq patterns))))))



;; ## Archive Versions

(defn version-file
  "Return the file object representing the identified version. Makes no
  guarantee that the file or its parents are present."
  ^File
  [archive version-id]
  (hoard-file archive "versions" version-id))


(defn list-versions
  "List the versions present in an archive directory."
  [archive]
  (let [versions-dir (hoard-file archive "versions")]
    (into []
          (map version/file-meta)
          (f/list-files versions-dir))))


(defn read-version
  "Read a version from the archive by id."
  [archive version-id]
  (let [file (version-file archive version-id)]
    (when (and (f/file? file) (f/readable? file))
      (merge
        (version/file-meta file)
        (version/read-data file)))))


(defn write-version!
  "Write a version data structure to a file in the archive."
  [archive version]
  (let [version-id (::version/id version)
        file (version-file archive version-id)]
    (io/make-parents file)
    (try
      (with-open [out (io/output-stream file)]
        (version/write-data! out version))
      (catch Exception ex
        (f/safely-delete! file)
        (throw ex)))
    version))



;; ## Archive Directories

(defn- load-archive
  "Load a map of archive configuration from the given directory which holds a
  `.hoard` directory."
  [root]
  (let [base {::root (f/canonical root)}]
    (merge (read-config base)
           base
           {::ignore (or (read-ignores base) #{})
            ::versions (list-versions base)})))


(defn find-root
  "Find up from the given directory to locate the hoard archive root. Returns a
  map of information about the archive, or nil if no archive root is found.

  The search will will terminate after `limit` recursions or once it hits the
  filesystem root or a directory the user can't read."
  [dir]
  (loop [dir (f/canonical dir)
         limit 100]
    (when (and dir
               (f/directory? dir)
               (f/readable? dir)
               (pos? limit))
      (if (f/directory? (io/file dir ".hoard"))
        (load-archive dir)
        (recur (f/parent dir) (dec limit))))))



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
    (io/make-parents file)
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



;; ## Code Assignment

(defn- coded-lookup
  "Take data from the most recent `n` versions and produce a lookup map from
  content-id to coded-id."
  [archive n]
  (into {}
        (comp
          (take n)
          (map #(read-version archive (::version/id %)))
          (mapcat ::version/index)
          (filter (every-pred :coded-id :content-id))
          (map (juxt :coded-id :content-id)))
        (reverse (::versions archive))))


(defn- assign-coded-id
  "Using a lookup mapping of content-id to coded-id, update the given entry
  with a `:coded-id` value if a match is found."
  [content->coded entry]
  (if-let [coded-id (some-> entry :content-id content->coded)]
    (assoc entry :coded-id coded-id)
    entry))



;; ## Version Construction

(defn index-tree
  "Build an index of the file tree under the root."
  [archive]
  (let [cache-file (hoard-file archive "cache" "tree")
        tree-cache (read-cache cache-file)
        content->coded (coded-lookup archive 3)
        stats (->>
                (scan-files archive)
                (map (partial hash-file tree-cache))
                (map (partial assign-coded-id content->coded))
                (sort-by :path)
                (vec))
        tree-cache' (build-cache stats)]
    (when (not= tree-cache tree-cache')
      (write-cache! cache-file tree-cache'))
    stats))
