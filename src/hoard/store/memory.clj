(ns hoard.store.memory
  "In-memory repository implementation."
  (:require
    [blocks.store.memory :refer [memory-block-store]]
    [hoard.repo.archive :as archive]
    [hoard.repo.version :as version]
    [hoard.store.core :as store])
  (:import
    java.time.Instant))


#_
(defrecord MemoryRepository
  [archives]

  store/ArchiveStore

  (list-archives
    [this query]
    (into []
          (map (fn [[archive-name versions]]
                 {::archive/name archive-name
                  ::archive/versions versions}))
          @archives))


  (get-archive
    [this archive-name]
    (when-let [versions (get @archives archive-name)]
      {::archive/name archive-name
       ::archive/versions (into []
                                (map #(dissoc % ::version/index))
                                versions)}))


  (read-version
    [this archive-name version-id]
    (->>
      (get @archives archive-name)
      (filter #(= version-id (::version/id %)))
      (first)))


  (create-version!
    [this archive-name index-data]
    (let [version {::version/id (version/gen-id)
                   ::version/created-at (Instant/now)
                   ::version/count (count index-data)
                   ::version/size (apply + (keep :size index-data))
                   ::version/index index-data}]
      (dosync
        (alter archives update archive-name (fnil conj []) version))
      version))


  (remove-version!
    [this archive-name version-id]
    (dosync
      (if-let [versions (get @archives archive-name)]
        (let [versions' (into []
                              (remove #(= version-id (::version/id %)))
                              versions)]
          (alter archives assoc archive-name versions')
          (not= versions versions'))
        false))))


#_#_
(alter-meta! #'->MemoryRepository assoc :private true)
(alter-meta! #'map->MemoryRepository assoc :private true)


(defn memory-repository
  "Construct a new in-memory data repository."
  [opts]
  #_
  (map->MemoryRepository
    (assoc opts
           :archives (ref (sorted-map))
           :blocks (memory-block-store))))
