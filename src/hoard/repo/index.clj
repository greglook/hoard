(ns hoard.repo.index
  (:require
    [clojure.spec.alpha :as s]
    [multiformats.hash])
  (:import
    multiformats.hash.Multihash))


;; ## Specs

;; Relative path of the file in the tree being archived.
(s/def :hoard.repo.index.entry/path
  string?)


;; Type of file entry being archived.
(s/def :hoard.repo.index.entry/type
  #{:directory :file :symlink})


;; Original size in bytes of the file being archived.
(s/def :hoard.repo.index.entry/size
  nat-int?)


;; Octal bitmask representing the entry's permissions.
(s/def :hoard.repo.index.entry/permissions
  (s/int-in 0 512))


;; Last modified time of the file being archived.
(s/def :hoard.repo.index.entry/modified-at
  inst?)


;; Multihash digest of the original file content.
(s/def :hoard.repo.index.entry/content-id
  #(instance? Multihash %))


;; Multihash digest of the encrypted file content.
(s/def :hoard.repo.index.entry/crypt-id
  #(instance? Multihash %))


;; Map of data for an index entry.
(s/def ::entry
  (s/keys :req-un [:hoard.repo.index.entry/path
                   :hoard.repo.index.entry/type
                   :hoard.repo.index.entry/size
                   :hoard.repo.index.entry/permissions
                   :hoard.repo.index.entry/modified-at
                   :hoard.repo.index.entry/content-id
                   :hoard.repo.index.entry/crypt-id]))
