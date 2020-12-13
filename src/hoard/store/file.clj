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


;; ## Repository Type

(defrecord FileRepository
  [^File root blocks]

  store/Repository

  (block-store
    [this]
    blocks)


  (list-archives
    [this query]
    (let [archive-dirs (.listFiles (io/file root "archive"))]
      (mapv
        (fn list-arcihve
          [^File archive-dir]
          (let [archive-name (.name archive-dir)
                versions (->>
                           (.listFiles archive-dir)
                           (map #(.getName ^File %))
                           (sort)
                           (mapv #(array-map ::version/id %)))]
            {::archive/name archive-name
             ::archive/versions versions}))
        archive-dirs)))


  (get-archive
    [this archive-name]
    (let [archive-dir (io/file root "archive" archive-name)]
      (when (.exists archive-dir)
        (let [versions (->>
                         (.listFiles archive-dir)
                         (map (fn [^File version-file]
                                ;; TODO: need to decrypt and gunzip here
                                (assoc (index/read-header version-file)
                                       ::version/id (.getName version-file))))
                         (sort-by ::version/id)
                         (vec))]
          ;; TODO: read the version headers?
          {::archive/name archive-name
           ::archive/versions versions}))))


  (read-version
    [this archive-name version-id]
    (let [version-file (io/file root "archive" archive-name version-id)]
      (when (.exists version-file)
        ;; TODO: need to decrypt and gunzip here
        #_
        {::archive/name archive-name
         ::version/id version-id
         ,,,})))


  (create-version!
    [this archive-name index-data]
    (let [version-id (version/gen-id)
          version-file (io/file root "archive" archive-name version-id)
          created-at (Instant/now)
          version {::version/id version-id
                   ::version/created-at created-at
                   ::version/count (count index-data)
                   ::version/size (apply + (keep :size index-data))
                   ::version/index index-data}]
      (io/make-parents version-file)
      ;; TODO: gzip and encrypt
      #_
      (index/write-data!
        version-file
        {:created-at created-at}
        index-data)
      version))


  (remove-version!
    [this archive-name version-id]
    (let [version-file (io/file root "archive" archive-name version-id)]
      (if (.exists version-file)
        (do (.delete version-file) true)
        false))))


(alter-meta! #'->FileRepository assoc :private true)
(alter-meta! #'map->FileRepository assoc :private true)


(defn file-repository
  "Construct a new in-memory data repository."
  [opts]
  (let [root (io/file (:root opts))]
    (map->FileRepository
      (assoc opts
             :root root
             :blocks (file-block-store (io/file root "data"))))))
