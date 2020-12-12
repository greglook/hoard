(ns hoard.repo.archive
  (:require
    [clojure.spec.alpha :as s]
    [hoard.repo.index :as index]))


;; ## Specs

;; Unique identifying name for the archive.
(s/def ::name
  string?)


;; Sequence of versions of the archive.
(s/def ::versions
  (s/coll-of ::index/meta :kind vector?))
