(ns hoard.config.local
  "Functions for managing the configuration and state in a local working tree."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    java.io.File))


(defn find-local-root
  "Find up from the given directory to locate the hoard archive root. Returns a
  map of information about the archive, or nil if no archive root is found.

  The search will will terminate after `limit` recursions or once it hits the
  filesystem root or a directory the user can't read."
  [^File dir limit]
  (when (and dir
             (.isDirectory dir)
             (.canRead dir)
             (pos? limit))
    (let [archive-dir (io/file dir ".hoard")]
      (if (.isDirectory archive-dir)
        ;; TODO: load config and ignore here
        {:root (.getCanonicalFile dir)
         :config {}
         :ignore #{}}
        (recur (.getParentFile (.getCanonicalFile dir)) (dec limit))))))
