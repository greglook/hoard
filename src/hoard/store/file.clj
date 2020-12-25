(ns hoard.store.file
  "Local filesystem repository implementation."
  (:require
    [blocks.store.file :refer [file-block-store]]
    [clojure.java.io :as io]
    [hoard.data.archive :as archive]
    [hoard.data.repository :as repo]
    [hoard.data.version :as version])
  (:import
    java.io.File
    java.time.Instant))


;; ## Utilities

(defn- version-file-meta
  "Return a map of metadata about a version file."
  [^File file]
  (let [version-id (.getName file)]
    {::version/id version-id
     ::version/size (.length file)
     ::version/created-at (version/parse-id-inst version-id)}))


(defn- list-version-meta
  "List the version metadata from an archive directory."
  [^File archive-dir]
  (->>
    (.listFiles archive-dir)
    (map version-file-meta)
    (sort-by ::version/id)
    (vec)))


(defn- archive-dir-meta
  "Load the metadata about an archive directory, including a list of version
  metadata."
  [^File archive-dir]
  (when (.exists archive-dir)
    {::archive/name (.getName archive-dir)
     ::archive/versions (list-version-meta archive-dir)}))



;; ## Repository Type

(defrecord FileVersionStore
  [^File root]

  repo/VersionStore

  (-list-archives
    [this query]
    (mapv archive-dir-meta (.listFiles root)))


  (-get-archive
    [this archive-name]
    (archive-dir-meta (io/file root archive-name)))


  (-stat-version
    [store archive-name version-id]
    ,,,)


  (-read-version
    [this archive-name version-id]
    (let [version-file (io/file root archive-name version-id)]
      (when (.exists version-file)
        (io/input-stream version-file))))


  (-store-version!
    [this archive-name version-id content]
    (let [version-file (io/file root archive-name version-id)]
      (io/make-parents version-file)
      (io/copy content version-file)
      (version-file-meta version-file)))


  (-remove-version!
    [this archive-name version-id]
    (let [version-file (io/file root archive-name version-id)]
      (if (.exists version-file)
        (do (.delete version-file) true)
        false))))


(alter-meta! #'->FileVersionStore assoc :private true)
(alter-meta! #'map->FileVersionStore assoc :private true)


(defn file-repository
  "Construct a new in-memory data repository."
  [opts]
  (let [root (io/file (:root opts))]
    (identity
      (assoc opts
             :versions (->FileVersionStore (io/file root "archive"))
             :blocks (file-block-store (io/file root "data"))))))
