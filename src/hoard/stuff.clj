(ns hoard.stuff
  "Work on the function instead of the form."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    java.io.File
    (java.nio.file
      FileVisitOption
      Files
      LinkOption
      Path)
    (java.nio.file.attribute
      FileTime
      PosixFilePermission)))


;; ## File Permissions

(def ^:private permission-order
  "File permission enum values, in ascending bit position order."
  [PosixFilePermission/OTHERS_EXECUTE
   PosixFilePermission/OTHERS_WRITE
   PosixFilePermission/OTHERS_READ
   PosixFilePermission/GROUP_EXECUTE
   PosixFilePermission/GROUP_WRITE
   PosixFilePermission/GROUP_READ
   PosixFilePermission/OWNER_EXECUTE
   PosixFilePermission/OWNER_WRITE
   PosixFilePermission/OWNER_READ])


(defn- permissions->bits
  "Convert a set of file permission enums to an integer bitmask."
  [permissions]
  (loop [bits 0
         idx 0]
    (if (< idx 9)
      (recur (if (contains? permissions (nth permission-order idx))
               (bit-or bits (bit-shift-left 1 idx))
               bits)
             (inc idx))
      bits)))


(defn- bits->permissions
  "Convert an integer bitmask to a set of file permission enums."
  [bits]
  (into #{}
        (keep (fn test-bit
                [idx]
                (when-not (zero? (bit-and bits (bit-shift-left 1 idx)))
                  (nth permission-order idx))))
        (range 9)))


(defn permission-string
  "Convert a permission bitmask into a human-friendly string."
  [bits]
  (->> (range 9)
       (map (fn test-bit
              [i]
              (if (zero? (bit-and bits (bit-shift-left 1 i)))
                "-"
                (case (mod i 3)
                  0 "x"
                  1 "w"
                  2 "r"))))
       (reverse)
       (apply str)))



;; ## File Walking

(defn- path-stats
  "Return a map of stats about a file at the given path."
  [^Path path]
  (let [size (Files/size path)
        no-follow-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        permissions (permissions->bits (Files/getPosixFilePermissions path no-follow-links))
        modified-at (.toInstant (Files/getLastModifiedTime path no-follow-links))]
    (cond
      (Files/isSymbolicLink path)
      {:path (str path)
       :type :symlink
       :target (str (Files/readSymbolicLink path))
       :permissions permissions
       :modified-at modified-at}

      (Files/isRegularFile path no-follow-links)
      {:path (str path)
       :type :file
       :size size
       :permissions permissions
       :modified-at modified-at}

      (Files/isDirectory path no-follow-links)
      {:path (str path)
       :type :directory
       :permissions permissions
       :modified-at modified-at}

      :else
      {:path (str path)
       :type :unknown})))


(defn walk-files
  "Walk a filesystem depth-first, returning a sequence of file metadata. This
  includes the (relative) path, file type, size, permissions, and modified
  time."
  [^File root]
  (->>
    (Files/walk (.toPath root) (into-array java.nio.file.FileVisitOption []))
    (.iterator)
    (iterator-seq)
    (map path-stats)))
