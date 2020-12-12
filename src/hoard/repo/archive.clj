(ns hoard.repo.archive
  (:require
    [clojure.spec.alpha :as s]
    [hoard.repo.version :as version]))


;; ## Specs

;; Unique identifying name for the archive.
(s/def ::name
  string?)


;; Sequence of versions of the archive.
(s/def ::versions
  (s/coll-of ::version/meta :kind vector?))
