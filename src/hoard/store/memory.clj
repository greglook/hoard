(ns hoard.store.memory
  "In-memory repository implementation."
  (:require
    [blocks.store.memory :refer [memory-block-store]]
    [clojure.java.io :as io]
    [hoard.data.archive :as archive]
    [hoard.data.repository :as repo]
    [hoard.data.version :as version])
  (:import
    (java.io
      ByteArrayInputStream
      ByteArrayOutputStream)
    java.time.Instant))


;; ## Archive Store

(defn- version-meta
  "Select only the metadata keys from the given version."
  [version]
  (select-keys version [::version/id ::version/size ::version/created-at]))


(defn- find-version
  "Return the data map for the identified version, or nil if not present."
  [archives archive-name version-id]
  (->>
    (get archives archive-name)
    (filter #(= version-id (::version/id %)))
    (first)))


(defn- slurp-bytes
  "Read the input source into a byte array."
  ^bytes
  [source]
  (let [out (ByteArrayOutputStream.)]
    (with-open [in (io/input-stream source)]
      (io/copy in out))
    (.toByteArray out)))


(defrecord MemoryArchiveStore
  [memory]

  repo/ArchiveStore

  (-list-archives
    [this query]
    (into []
          (map (fn [[archive-name versions]]
                 {::archive/name archive-name
                  ::archive/versions (mapv version-meta versions)}))
          @memory))


  (-get-archive
    [this archive-name]
    (when-let [versions (get @memory archive-name)]
      ;; TODO: load config and ignores?
      {::archive/name archive-name
       ::archive/versions (mapv version-meta versions)}))


  (-stat-version
    [this archive-name version-id]
    (version-meta (find-version @memory archive-name version-id)))


  (-read-version
    [this archive-name version-id]
    (some->>
      (find-version @memory archive-name version-id)
      (::content)
      (ByteArrayInputStream.)))


  (-store-version!
    [this archive-name version-id source]
    (let [content (slurp-bytes source)
          version {::version/id version-id
                   ::version/created-at (version/parse-id-inst version-id)
                   ::version/size (count content)
                   ::content content}]
      (dosync
        (alter memory update archive-name (fnil conj []) version))
      (version-meta version)))


  (-remove-version!
    [this archive-name version-id]
    (dosync
      (if-let [versions (get @memory archive-name)]
        (let [versions' (into []
                              (remove #(= version-id (::version/id %)))
                              versions)]
          (if (= versions versions')
            false
            (do
              (alter memory assoc archive-name versions')
              true)))
        false))))


(alter-meta! #'->MemoryArchiveStore assoc :private true)
(alter-meta! #'map->MemoryArchiveStore assoc :private true)



;; ## Repository

(defn memory-repository
  "Construct a new in-memory data repository."
  [opts]
  (repo/component-repository
    (assoc opts
           :archives (->MemoryArchiveStore (ref (sorted-map)))
           :blocks (memory-block-store))))
