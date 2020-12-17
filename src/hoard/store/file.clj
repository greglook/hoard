(ns hoard.store.file
  "Local filesystem repository implementation.

  The filesystem in a repository is laid out like so:

      root
      ├── meta.properties
      ├── archive
      │   ├── foo
      │   │   ├── 20201204-01482-abcd
      │   │   ├── 20201210-57391-defg
      │   │   └── ...
      │   └── bar
      │       └── ...
      └── data
          ├── meta.properties
          └── blocks
              ├── 11140000
              │   ├── debc06fba391088613aafb041a23f0cb8f5ceaad9b487e2928897a75933778
              │   ├── b2c7eef7421670bd4aca894ed27a94c8219e181d7b63006bea3038240164c1
              │   └── ...
              ├── 11140001
              │   └── ...
              └── ...

  The individual version files are TSV which start with a single line giving
  the file format version, followed by a row of version metadata, followed by
  the index data. The files are gzipped, then encrypted."
  (:require
    [blocks.store.file :refer [file-block-store]]
    [clojure.java.io :as io]
    [hoard.repo.archive :as archive]
    [hoard.repo.index :as index]
    [hoard.repo.version :as version]
    [hoard.store.core :as store])
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
    {::archive/name (.name archive-dir)
     ::archive/versions (list-version-meta archive-dir)}))



;; ## Repository Type

(defrecord FileArchiveStore
  [^File root]

  store/ArchiveStore

  (list-archives
    [this query]
    (mapv archive-dir-meta (.listFiles root)))


  (get-archive
    [this archive-name]
    (archive-dir-meta (io/file root archive-name)))


  (read-version
    [this archive-name version-id]
    (let [version-file (io/file root archive-name version-id)]
      (when (.exists version-file)
        (io/input-stream version-file))))


  (store-version!
    [this archive-name version-id content]
    (let [version-file (io/file root archive-name version-id)]
      (io/make-parents version-file)
      (io/copy content version-file)
      (version-file-meta version-file)))


  (remove-version!
    [this archive-name version-id]
    (let [version-file (io/file root archive-name version-id)]
      (if (.exists version-file)
        (do (.delete version-file) true)
        false))))


(alter-meta! #'->FileArchiveStore assoc :private true)
(alter-meta! #'map->FileArchiveStore assoc :private true)


(defn file-repository
  "Construct a new in-memory data repository."
  [opts]
  (let [root (io/file (:root opts))]
    (identity
      (assoc opts
             :archives (->FileArchiveStore (io/file root "archive"))
             :blocks (file-block-store (io/file root "data"))))))
