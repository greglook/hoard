(ns hoard.store.core
  "Core storage protocols for hoard repositories.")


(defprotocol Repository
  "Storage protocol for data repositories."

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
