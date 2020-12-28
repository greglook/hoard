(ns hoard.store.file
  "Local filesystem repository implementation."
  (:require
    [blocks.store.file :refer [file-block-store]]
    [clojure.java.io :as io]
    [hoard.data.archive :as archive]
    [hoard.data.repository :as repo]
    [hoard.data.version :as version]
    [hoard.file.core :as f])
  (:import
    java.io.File
    java.time.Instant))


;; ## Utilities

(defn- reserved-id?
  "True if the provided version identifier is reserved for internal use."
  [id]
  (= "config" id))


(defn- version-file-meta
  "Return a map of metadata about a version file."
  [^File file]
  (let [version-id (f/file-name file)]
    {::version/id version-id
     ::version/size (f/size file)
     ::version/created-at (version/parse-id-inst version-id)}))


(defn- list-version-meta
  "List the version metadata from an archive directory."
  [^File archive-dir]
  (->>
    (f/list-files archive-dir)
    (remove (comp reserved-id? f/file-name))
    (map version-file-meta)
    (sort-by ::version/id)
    (vec)))


(defn- archive-dir-meta
  "Load the metadata about an archive directory, including a list of version
  metadata."
  [^File archive-dir]
  (when (f/exists? archive-dir)
    {::archive/name (f/file-name archive-dir)
     ::archive/versions (list-version-meta archive-dir)}))



;; ## Archive Store

(defrecord FileArchiveStore
  [^File root]

  repo/ArchiveStore

  (-list-archives
    [this query]
    (map archive-dir-meta (f/list-files root)))


  (-get-archive
    [this archive-name]
    (archive-dir-meta (io/file root archive-name)))


  (-get-archive-config
    [this archive-name]
    (let [config-file (io/file root archive-name "config")]
      (when (f/exists? config-file)
        (slurp config-file))))


  (-store-archive-config!
    [store archive-name content]
    (let [config-file (io/file root archive-name "config")]
      (io/make-parents config-file)
      (spit config-file content)))


  (-stat-version
    [store archive-name version-id]
    (when-not (reserved-id? version-id)
      (let [version-file (io/file root archive-name version-id)]
        (when (f/exists? version-file)
          (version-file-meta version-file)))))


  (-read-version
    [this archive-name version-id]
    (when-not (reserved-id? version-id)
      (let [version-file (io/file root archive-name version-id)]
        (when (f/exists? version-file)
          (io/input-stream version-file)))))


  (-store-version!
    [this archive-name version-id content]
    (when (reserved-id? version-id)
      (throw (ex-info (format "Cannot store version for reserved file name '%s'"
                              version-id)
                      {::version/id version-id})))
    (let [version-file (io/file root archive-name version-id)]
      (io/make-parents version-file)
      (io/copy content version-file)
      (version-file-meta version-file)))


  (-remove-version!
    [this archive-name version-id]
    (let [version-file (io/file root archive-name version-id)]
      (cond
        (reserved-id? version-id)
        false

        (not (f/exists? version-file))
        false

        :else
        (do (f/delete! version-file)
            true)))))


(alter-meta! #'->FileArchiveStore assoc :private true)
(alter-meta! #'map->FileArchiveStore assoc :private true)



;; ## Repository

(defn file-repository
  "Construct a new local file data repository."
  [opts]
  (let [root (io/file (:root opts))]
    (repo/component-repository
      (assoc opts
             :archives (->FileArchiveStore (io/file root "archive"))
             :blocks (file-block-store (io/file root "data"))))))
