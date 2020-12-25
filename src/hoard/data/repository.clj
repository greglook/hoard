(ns hoard.data.repository
  "Functions for managing repositories of stored data."
  (:require
    [blocks.core :as block]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [hoard.data.archive :as archive]
    [hoard.data.version :as version]
    [hoard.file.tsv :as tsv]
    [manifold.deferred :as d]
    [multiformats.hash :as multihash])
  (:import
    (java.io
      InputStream
      OutputStream
      PipedInputStream
      PipedOutputStream)
    java.util.concurrent.TimeUnit
    (java.util.zip
      GZIPInputStream
      GZIPOutputStream)
    org.apache.commons.io.input.CountingInputStream
    org.apache.commons.io.output.CountingOutputStream))


;; ## Data Specs

;; Unique identifying name for the repo.
(s/def ::name
  string?)


;; Type of repository.
(s/def ::type
  simple-keyword?)


;; List of archives with some versions stored in the repo.
(s/def ::archives
  (s/coll-of ::archive/name :kind set?))



;; ## Storage Protocol

(defprotocol VersionStore
  "Storage protocol for at-rest archive version data."

  (-list-archives
    [store opts]
    "List metadata about the archives present in the store. Returns a sequence
    (possibly lazy) which contains the metadata maps in no particular order.")

  (-get-archive
    [store archive-name]
    "Retrieve information about a specific archive. Returns nil if no such
    archive is present. Versions are returned in time-ascending order.")

  (-stat-version
    [store archive-name version-id]
    "Retrieve rough statistics on a specific version of an archive. Returns nil if
    no such version or archive is present.")

  (-read-version
    [store archive-name version-id]
    "Open an input stream over the byte contents of a stored version.")

  (-store-version!
    [store archive-name version-id content]
    "Store data from the provided content stream as a new version. Returns a
    map of metadata about the written version.")

  (-remove-version!
    [store archive-name version-id]
    "Removes a version of an archive from the repository. Returns true if the
    version was present and removed, false otherwise."))



;; ## Process Piping

(defn- stopwatch
  "Create a delay which yields the number of milliseconds between the time this
  function was called and when it was realized."
  []
  (let [start (System/nanoTime)]
    (delay (/ (- (System/nanoTime) start) 1e6))))


(defn- piped-streams
  "Construct a pair of connected output and input streams, forming a pipe.
  Returns a tuple of the output stream sink and the input stream source."
  [buffer-size]
  (let [pipe-src (PipedInputStream. (int buffer-size))
        pipe-sink (PipedOutputStream. pipe-src)]
    [pipe-sink pipe-src]))


(defn- future-copy
  "Copy all of the data from `in` to `out` on a new thread, closing `in` after.
  Does *not* close `out`. Returns a future which yields true when the copy is
  complete."
  [^InputStream in ^OutputStream out]
  (future
    (try
      (io/copy in out)
      true
      (finally
        (.close in)))))


(defn- pipe-process
  "Pipe the provided stream of input data through a process invoked with the
  given arguments. Writes output data to the given output stream. Returns a
  deferred which yields information about the transfer on success."
  [command ^InputStream in ^OutputStream out]
  (d/future
    ;; TODO: what to do if the process needs human input?
    ;; Graphical pinentry programs work
    (let [elapsed (stopwatch)
          process (.start (ProcessBuilder. ^java.util.List command))
          stdin (CountingOutputStream. (.getOutputStream process))
          stdout (CountingInputStream. (.getInputStream process))
          input-copier (future-copy in stdin)
          output-copier (future-copy stdout out)]
      (if (.waitFor process 60 TimeUnit/SECONDS)
        (do
          @input-copier
          @output-copier)
        (do
          (future-cancel input-copier)
          (future-cancel output-copier)
          (.destroy process)))
      (let [exit (.exitValue process)]
        (merge
          {:success? (zero? exit)
           :elapsed @elapsed
           :input-bytes (.getByteCount stdin)
           :output-bytes (.getByteCount stdout)}
          (when-not (zero? exit)
            {:exit exit})
          (let [stderr (slurp (.getErrorStream process))]
            (when-not (str/blank? stderr)
              {:error stderr})))))))



;; ## Version IO

(defn- encode-version
  "Writes a sequence of version index data to the given output stream, after
  compressing it and running it through the given command. Returns a deferred
  which yields the pipe output when finished."
  [^OutputStream out encode-command version]
  (d/future
    (let [[pipe-sink pipe-src] (piped-streams 4096)
          gzip-out (GZIPOutputStream. pipe-sink)
          count-out (CountingOutputStream. gzip-out)
          encode (pipe-process encode-command pipe-src out)]
      (version/write-data! count-out version)
      (.close count-out)
      (->
        @encode
        (assoc :raw-size (.getByteCount count-out))
        (set/rename-keys
          {:input-bytes :compressed-size
           :output-bytes :encoded-size})))))


(defn- decode-version
  "Read version index data from the given input stream. Returns a deferred
  which yields the process result with the version data under `:version` on
  success."
  [^InputStream in decode-command]
  ;; WARNING: the GZIP input stream seems to read some bytes to determine the
  ;; encoding of the stream *during construction*, so if it is created before
  ;; the process which fills the pipe, the thread will deadlock.
  (let [[pipe-sink pipe-src] (piped-streams 4096)]
    (d/chain
      (d/zip
        (pipe-process decode-command in pipe-sink)
        (d/future
          (let [gzip-in (GZIPInputStream. pipe-src)
                count-in (CountingInputStream. gzip-in)
                version (version/read-data count-in)]
            (.close count-in)
            [version (.getByteCount count-in)])))
      (fn combine
        [[proc [version raw-size]]]
        (-> proc
            (assoc :raw-size raw-size
                   :version version)
            (set/rename-keys
              {:input-bytes :encoded-size
               :output-bytes :compressed-size}))))))



;; ## File IO

(defn- store-file!
  "Write a file from the input to the block store after encoding it with the
  command. Returns the process results with the stored `:block` key."
  [block-store encode-command file]
  (let [[pipe-sink pipe-src] (piped-streams 4096)
        file-in (io/input-stream file)]
    (->
      (d/zip
        (pipe-process encode-command file-in pipe-sink)
        (block/store! block-store pipe-src))
      (d/chain
        (fn [[proc block]]
          (assoc proc :block block)))
      (d/finally
        #(.close file-in)))))


(defn- read-file
  "Read a file from the block store at the given id, decoding it with the
  command."
  [block-store decode-command block-id]
  ,,,)



;; ## Basic API

(defn list-archives
  "List metadata about the archives present in the repository. Returns a
  deferred which yields a sequence of the metadata maps ordered by name."
  [repo query]
  ,,,)


(defn get-archive
  "Retrieve information about a specific archive. Returns a deferred which
  yields metadata about the archive, or nil if no such archive is present.
  Versions are returned in time-ascending order."
  [repo archive-name]
  ,,,)


(defn get-version
  "Fetch a full version from the repository. Returns a deferred which yields
  the version data, or nil if no such version exists."
  [repo archive-name version-id]
  ,,,)


(defn remove-version!
  "Remove a version of an archive from the repository. Returns a deferred which
  yields true if the version was present and removed, false otherwise."
  [repo archive-name version-id]
  ,,,)



;; ## Version Storage

(defn- store-index-files!
  "Store the given index into the block-store. Returns the updated index
  sequence, with every entry which has a `:content-id` given a matching
  `:coded-id`."
  [block-store encode-command file-stats]
  (d/loop [index []
           stats file-stats]
    (if (seq stats)
      ;; Process the next file.
      (let [entry (first stats)]
        (if-let [content-id (:content-id entry)]
          ;; Check if block store already has data.
          (d/chain
            (when-let [block-id (:coded-id entry)]
              (block/get block-store block-id))
            (fn store-file
              [block]
              (if block
                {:block block}
                (store-file! block-store encode-command (:file entry))))
            :block
            (fn add-coded
              [block]
              (let [entry' (assoc entry :coded-id (:id block))]
                (d/recur (conj index entry') (rest stats)))))
          ;; No content in entry.
          (d/recur (conj index entry) (rest stats))))
      ;; Done processing index
      (version/create-version index))))


;; Creating a new Version
;; 1. (archive/index-tree archive) => index
;; 2. (store-index-files! block-store (::archive/encode-command archive) index) => version-params
;; 3. (store-version! version-store (::archive/name archive) version-params) => version
;; 4. (archive/store-version! archive version) => archive'


(defn diff-version
  [last-version index]
  ;; compare entries from the version and the current index
  ;; - removed: entry in version and not in index
  ;; - added: entry in index and not in version
  ;; - type: entries have different types
  ;; - diff: entries have different contents (or link targets)
  ;; - meta: entries have different permissions or last-modified time
  ,,,)
