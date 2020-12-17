(ns hoard.store.core
  "Core storage protocols for hoard repositories."
  (:require
    [hoard.repo.version :as version]))


(defprotocol ArchiveStore
  "Storage protocol for at-rest archive version data."

  (list-archives
    [store opts]
    "List metadata about the archives present in the store. Returns a sequence
    (possibly lazy) which contains the metadata maps in no particular order.")

  (get-archive
    [store archive-name]
    "Retrieve information about a specific archive. Returns nil if no such
    archive is present. Versions are returned in time-ascending order.")

  (stat-version
    [store archive-name version-id]
    "Retrieve rough statistics on a specific version of an archive. Returns nil if
    no such version or archive is present.")

  (read-version
    [store archive-name version-id]
    "Open an input stream over the byte contents of a stored version.")

  (store-version!
    [store archive-name version-id content]
    "Store data from the provided content stream as a new version. Returns a
    map of metadata about the written version.")

  (remove-version!
    [store archive-name version-id]
    "Removes a version of an archive from the repository. Returns true if the
    version was present and removed, false otherwise."))


#_
(defprotocol Repository
  "Storage protocol for data repositories."

  ;; TODO: this is clearly a code smell
  (block-store
    [repo]
    "Retrieve a block store for interacting with the repository data.")

  (list-archives
    [repo query]
    "List archives present in the repository.")

  (get-archive
    [repo archive-name]
    "Retrieve information about a specific archive. Returns nil if no such
    archive is present. Versions are returned in time-ascending order.")

  (read-version
    [repo archive-name version-id]
    "Read and decrypt the index file for a specific version of an archive.")

  (create-version!
    [repo archive-name index-data]
    "Create a new version of the archive, storing and encrypting the provided
    index-data. Returns the version record.")

  (remove-version!
    [repo archive-name version-id]
    "Removes a version of an archive from the repository. Returns true if the
    version was present and removed, false otherwise."))


;; TODO: how does this ns generically manage storage?
;; Four primary operations:
;; - Write version index
;;   index -> index/write-data! -> gzip -> encrypt -> store-version!
;; - Read version index
;;   read-version -> decrypt -> gunzip -> index/read-data -> index
;; - Write data block
;;   content -> gzip? -> encrypt -> block/store!
;; - Read data block
;;   block/open -> decrypt -> gunzip -> content



#_
(defn create-version!
  "Store index data"
  [ index-data]
  (let [version-id (version/gen-id)
        created-at (Instant/now)
        version {::version/id version-id
                 ::version/created-at created-at
                 ::version/count (count index-data)
                 ::version/size (apply + (keep :size index-data))
                 ::version/index index-data}]
    ;; TODO: implement
    version))
