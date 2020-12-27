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
    [hoard.data.work :as work]
    [hoard.file.core :as f]
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

(defprotocol ArchiveStore
  "Storage protocol for at-rest archive data."

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


;; TODO: component lifecycle behavior?
(defrecord ComponentRepository
  [archives blocks])


(alter-meta! #'->ComponentRepository assoc :private true)
(alter-meta! #'map->ComponentRepository assoc :private true)


(defn component-repository
  "Construct a new repository using separate archive and block data backing
  components."
  [opts]
  (map->ComponentRepository opts))



;; ## Configuration File

(defn config-file
  "Resolve the location of the configuration file by checking the
  `XDG_CONFIG_HOME`, and `HOME` environment variables. Returns the config file,
  without checking whether it exists."
  []
  (let [cfg-home (System/getenv "XDG_CONFIG_HOME")
        home (System/getenv "HOME")]
    (if-not (str/blank? cfg-home)
      (io/file cfg-home "hoard" "config")
      (io/file home ".config" "hoard" "config"))))


(defn- update-some
  "Update an associative data structure by applying `f` to the current value
  and `args`, if the current value at `k`. Returns the structure unchanged if
  `k` is not present."
  [m k f & args]
  (if (find m k)
    (apply update m k f args)
    m))


(defn repo-config
  "Parse the configuration for a repository from the config data. Returns the
  config merged with defaults, or nil if no such repository is configured."
  [config repo-name]
  (when-let [repo-config (get-in config [:repository (keyword repo-name)])]
    (-> (:defaults config)
        (merge repo-config)
        (update-some :type keyword)
        (update-some :trim.keep-days #(Integer/parseInt (str %)))
        (update-some :trim.keep-versions #(Integer/parseInt (str %))))))



;; ## Process Piping

(defn- piped-streams
  "Construct a pair of connected output and input streams, forming a pipe.
  Returns a tuple of the output stream sink and the input stream source."
  [buffer-size]
  (let [pipe-src (PipedInputStream. (int buffer-size))
        pipe-sink (PipedOutputStream. pipe-src)]
    [pipe-sink pipe-src]))


(defn- future-copy
  "Copy all of the data from `in` to `out` on a new thread, closing both
  streams afterward. Returns a future which yields true when the copy is
  complete."
  [^InputStream in ^OutputStream out]
  (future
    (try
      (io/copy in out)
      true
      (finally
        (.close in)
        (.close out)))))


(defn- pipe-process
  "Pipe the provided stream of input data through a process invoked with the
  given arguments. Writes output data to the given output stream. Returns a
  deferred which yields information about the transfer on success."
  [command ^InputStream in ^OutputStream out]
  (d/future
    ;; TODO: what to do if the process needs human input?
    ;; Graphical pinentry programs work
    (let [elapsed (work/stopwatch)
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

(defn- push-version!
  "Push a version from the archive to the repository."
  [repo archive version-id]
  (work/with-file-input [raw-in (archive/version-file archive version-id)]
    {:label (str "Push version " version-id " to repository")}
    (let [[proc-sink proc-src] (piped-streams 4096)
          [store-sink store-src] (piped-streams 4096)
          gzip-out (GZIPOutputStream. proc-sink)
          count-out (CountingOutputStream. gzip-out)]
      (->
        (d/zip
          (d/future
            (try
              (io/copy raw-in count-out)
              (finally
                (.close count-out))))
          (if-let [command (::archive/encode-command archive)]
            (pipe-process command proc-src store-sink)
            (future-copy proc-src store-sink))
          (d/future
            (-store-version!
              (:archives repo)
              (::archive/name archive)
              version-id
              store-src)))
        (d/chain
          (fn [[_ proc-data version-meta]]
            [proc-data version-meta]))))))


(defn- pull-version!
  "Pull a version from the repo to the archive."
  [repo archive version-id]
  #_
  (with-open [coded-in (-read-version
                         (:archives repo)
                         (::archive/name archive)
                         version-id)]
    ,,,))


#_
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


#_
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
  [block-store encode-command file-in]
  (d/chain
    (if encode-command
      (let [[pipe-sink pipe-src] (piped-streams 4096)]
        (d/zip
          (pipe-process encode-command file-in pipe-sink)
          (block/store! block-store pipe-src)))
      (d/zip nil (block/store! block-store file-in)))
    (fn [[proc block]]
      (assoc proc :block block))))


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

(defn plan-version
  "Produce a plan for capturing the local archive state as a new version in the
  repository. Returns a deferred which yields the index of file entries with
  associated `::action` keywords."
  [repo archive]
  (d/chain
    (d/future
      (archive/index-tree archive))
    (fn check-blocks
      [file-stats]
      (d/zip file-stats
             (block/get-batch
               (:blocks repo)
               (keep :coded-id file-stats))))
    (fn plan-files
      [[file-stats coded-blocks]]
      (let [stored? (into #{} (keep :id) coded-blocks)]
        (mapv
          (fn add-action
            [stats]
            (cond
              ;; No content in this file, nothing to do.
              (nil? (:content-id stats))
              (assoc stats ::action :none)

              ;; Coded data is already available in repo.
              (stored? (:coded-id stats))
              (assoc stats ::action :reuse)

              ;; Otherwise no known coded id or coded data unavailable.
              :else
              (assoc stats ::action :store)))
          file-stats)))))


(defn- store-index-files!
  "Store the given index into the block-store. Returns the updated index
  sequence, with every entry which has a `:content-id` given a matching
  `:coded-id`."
  [repo archive plan]
  ;; TODO: should be able to choose parallelism here
  (work/for-progress [[content-id entry] (into {}
                                               (comp
                                                 (filter #(= :store (::action %)))
                                                 (map (juxt :content-id identity)))
                                               plan)]
    {:label "Store encoded files to repository"}
    (work/with-file-input [file-in (:file entry)]
      {:label (str "Encode file " (:path entry))}
      (let [result @(store-file!
                      (:blocks repo)
                      (::archive/encode-command archive)
                      file-in)]
        [content-id (:id (:block result))]))))


(defn create-version!
  "Execute the provided planned index in order to create a new version."
  [repo archive plan]
  (let [content->coded (into {} (store-index-files! repo archive plan))
        index (into []
                    (map (fn assign-coded
                           [entry]
                           (if-let [coded-id (content->coded (:content-id entry))]
                             (assoc entry :coded-id coded-id)
                             entry)))
                    plan)
        version (version/create index)
        version-id (::version/id version)]
    (work/watch {:label "Write version to archive"}
      (archive/write-version! archive version))
    (push-version! repo archive version-id)
    version))






;; ## Other

#_
(defn diff-version
  [last-version index]
  ;; compare entries from the version and the current index
  ;; - removed: entry in version and not in index
  ;; - added: entry in index and not in version
  ;; - type: entries have different types
  ;; - diff: entries have different contents (or link targets)
  ;; - meta: entries have different permissions or last-modified time
  ,,,)
