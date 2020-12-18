(ns hoard.stuff
  "Work on the function instead of the form."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hoard.file.tsv :as tsv]
    [manifold.deferred :as d]
    [multiformats.hash :as multihash])
  (:import
    (java.io
      File
      InputStream
      OutputStream)
    (java.nio.file
      FileVisitOption
      Files
      LinkOption
      Path)
    (java.nio.file.attribute
      FileTime
      PosixFilePermission)
    java.util.concurrent.TimeUnit
    org.apache.commons.io.input.CountingInputStream
    org.apache.commons.io.output.CountingOutputStream))


(defn stopwatch
  "Create a delay which yields the number of milliseconds between the time this
  function was called and when it was realized."
  []
  (let [start (System/nanoTime)]
    (delay (/ (- (System/nanoTime) start) 1e6))))



;; ## File Permissions

(def ^:private permission-order
  "File permission enum values, in ascending bit position order."
  [PosixFilePermission/OTHERS_EXECUTE
   PosixFilePermission/OTHERS_WRITE
   PosixFilePermission/OTHERS_READ
   PosixFilePermission/GROUP_EXECUTE
   PosixFilePermission/GROUP_WRITE
   PosixFilePermission/GROUP_READ
   PosixFilePermission/OWNER_EXECUTE
   PosixFilePermission/OWNER_WRITE
   PosixFilePermission/OWNER_READ])


(defn- permissions->bits
  "Convert a set of file permission enums to an integer bitmask."
  [permissions]
  (loop [bits 0
         idx 0]
    (if (< idx 9)
      (recur (if (contains? permissions (nth permission-order idx))
               (bit-or bits (bit-shift-left 1 idx))
               bits)
             (inc idx))
      bits)))


(defn- bits->permissions
  "Convert an integer bitmask to a set of file permission enums."
  [bits]
  (into #{}
        (keep (fn test-bit
                [idx]
                (when-not (zero? (bit-and bits (bit-shift-left 1 idx)))
                  (nth permission-order idx))))
        (range 9)))


(defn permission-string
  "Convert a permission bitmask into a human-friendly string."
  [bits]
  (->> (range 9)
       (map (fn test-bit
              [i]
              (if (zero? (bit-and bits (bit-shift-left 1 i)))
                "-"
                (case (int (mod i 3))
                  0 "x"
                  1 "w"
                  2 "r"))))
       (reverse)
       (apply str)))



;; ## File Walking

(defn- file-stats
  "Return a map of stats about a file at the given path."
  [^File file]
  (let [path (.toPath file)
        size (Files/size path)
        no-follow-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        permissions (permissions->bits (Files/getPosixFilePermissions path no-follow-links))
        modified-at (.toInstant (Files/getLastModifiedTime path no-follow-links))]
    (cond
      (Files/isSymbolicLink path)
      {:type :symlink
       :target (str (Files/readSymbolicLink path))
       :permissions permissions
       :modified-at modified-at}

      (Files/isRegularFile path no-follow-links)
      {:type :file
       :size size
       :permissions permissions
       :modified-at modified-at}

      (Files/isDirectory path no-follow-links)
      {:type :directory
       :permissions permissions
       :modified-at modified-at}

      :else
      {:type :unknown})))


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
                       (= rule (.getName file))

                       (str/starts-with? rule File/pathSeparator)
                       (= (.getCanonicalPath file)
                          (str (.getCanonicalPath root)
                               File/pathSeparator
                               (if (str/ends-with? rule File/pathSeparator)
                                 (subs rule 0 (dec (count rule)))
                                 rule)))

                       :else
                       (str/ends-with?
                         (.getCanonicalPath file)
                         (if (str/ends-with? rule File/pathSeparator)
                           (subs rule 0 (dec (count rule)))
                           rule))))
                   ignored))))


(defn- walk-files
  "Walk a filesystem tree, starting at the root. Returns a lazy sequence of the
  given file stats, followed by its children in a depth-first fashion."
  [ignore? ^File file]
  (when-not (ignore? file)
    (cons
      (assoc (file-stats file) :file file)
      (when (.isDirectory file)
        (mapcat (partial walk-files ignore?)
                (.listFiles file))))))


(defn scan-files
  "Walk a filesystem depth-first, returning a sequence of file metadata. This
  includes the (relative) path, file type, size, permissions, and modified
  time."
  [^File root ignored]
  (let [ignore? (ignored-predicate root (conj (or ignored #{}) ".hoard"))
        root-path (.toPath root)]
    (->>
      (walk-files ignore? root)
      (drop 1)
      (map (fn relativize-path
             [stats]
             (let [file ^File (:file stats)
                   rel-path (.relativize root-path (.toPath file))]
               (assoc stats :path (str rel-path))))))))


(defn hash-file
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


(def cache-columns
  "Sequence of columns for cache records."
  [{:name :path}
   {:name :size
    :decode tsv/parse-long}
   {:name :modified-at
    :decode tsv/parse-inst}
   {:name :content-id
    :encode multihash/hex
    :decode multihash/parse}])


(defn read-cache
  "Read cache data from a file, returning a map from paths to cache records."
  [^File file]
  (when (and file (.exists file))
    (with-open [input (io/input-stream file)]
      (into (sorted-map)
            (map (juxt :path identity))
            (tsv/read-data input cache-columns)))))


(defn write-cache!
  "Write cache data to a file."
  [file stats]
  (when (and file (seq stats))
    (with-open [output (io/output-stream file)]
      (tsv/write-data!
        output
        cache-columns
        (filter :content-id stats)))))


(defn build-index
  "Build an index of the file tree under the root."
  [cache-file ignored root]
  (let [cache (read-cache cache-file)
        stats (->>
                (scan-files root ignored)
                (map (partial hash-file cache))
                (sort-by :path)
                (vec))]
    (write-cache! cache-file stats)
    stats))



;; ## Process Piping

(defn pipe-process
  "Pipe the provided stream of input data through a process invoked with the
  given arguments. Writes output data to the given output stream. Returns a
  deferred which yields information about the transfer on success."
  [command ^InputStream in ^OutputStream out]
  ;; TODO: what to do if the process needs human input?
  (let [elapsed (stopwatch)
        process (.start (ProcessBuilder. ^java.util.List command))
        stdin (CountingOutputStream. (.getOutputStream process))
        stdout (CountingInputStream. (.getInputStream process))
        input-copier (future
                       (io/copy in stdin)
                       (.close stdin)
                       (.close in))
        output-copier (future
                        (io/copy stdout out)
                        (.close stdout)
                        (.close out))]
    (if (.waitFor process 60 TimeUnit/SECONDS)
      (do
        @input-copier
        @output-copier)
      (do
        (future-cancel input-copier)
        (future-cancel output-copier)
        (.destroy process)))
    (let [exit (.exitValue process)]
      {:exit exit
       :success? (zero? exit)
       :stderr (slurp (.getErrorStream process))
       :elapsed @elapsed
       :input-bytes (.getByteCount stdin)
       :output-bytes (.getByteCount stdout)})))
