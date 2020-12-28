(ns hoard.file.core
  "Common file utility functions."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    java.io.File
    (java.nio.file
      Files
      LinkOption)
    (java.nio.file.attribute
      PosixFilePermission)))


;; ## Predicates

(defn exists?
  "True if the given file exists."
  [^File file]
  (boolean (and file (.exists file))))


(defn readable?
  "True if the process can read the given `File`."
  [^File file]
  (boolean (and file (.canRead file))))


(defn file?
  "True if the given `File` represents a regular file."
  [^File file]
  (boolean (and file (.isFile file))))


(defn directory?
  "True if the given `File` represents a directory."
  [^File file]
  (boolean (and file (.isDirectory file))))


(defn symlink?
  "true if the given `File` represents a symbolic link."
  [^File file]
  (boolean (and file (Files/isSymbolicLink (.toPath file)))))



;; ## Accessors

(defn file-name
  "Return the name of the given file. Returns nil if file is nil."
  [^File file]
  (and file (.getName file)))


(defn canonical
  "Return the canonical version of the file. Returns nil if file is nil."
  [^File file]
  (and file (.getCanonicalFile file)))


(defn parent
  "Return the parent file of the file. Returns nil if file is nil."
  [^File file]
  (and file (.getParentFile file)))


(defn size
  "Return the size of the file in bytes."
  [^File file]
  (and file (.length file)))


(defn last-modified
  "Return the instant the file was last modified."
  [^File file]
  (-> (.toPath file)
      (Files/getLastModifiedTime
        (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (.toInstant)))


(defn link-target
  "Return the string path the symbolic link is pointing to."
  [^File file]
  (when (symlink? file)
    (str (Files/readSymbolicLink (.toPath file)))))


(defn list-files
  "Return a sequence of the files which are direct children of the given
  directory. Returns nil if the file is nil or is not a directory."
  [^File dir]
  (when (directory? dir)
    (.listFiles dir)))


(defn walk-files
  "Walk a filesystem tree, starting at the root. Returns a lazy depth-first
  sequence of the files in the tree."
  [ignore? ^File file]
  (when-not (ignore? file)
    (cons
      file
      (when (.isDirectory file)
        (mapcat (partial walk-files ignore?)
                (.listFiles file))))))



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
                (case (int (mod i 3))
                  0 "x"
                  1 "w"
                  2 "r"))))
       (reverse)
       (apply str)))


(defn permissions
  "Return the integer permission bitmask for the given file."
  [^File file]
  (when file
    (-> (.toPath file)
        (Files/getPosixFilePermissions
          (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
        (permissions->bits))))



;; ## Actions

(defn delete!
  "Remove the file. Does not check for existence first."
  [^File file]
  (when file
    (.delete file)))


(defn safely-delete!
  "Safely remove the file. Will not throw exceptions."
  [^File file]
  (when file
    (try
      (.delete file)
      nil
      (catch Exception _
        nil))))



;; ## Miscellaneous

(defn chomp-separator
  "If the string of the given value ends with the file path separator, remove it."
  [x]
  (let [s (str x)]
    (if (str/ends-with? s File/pathSeparator)
      (subs s 0 (dec (count s)))
      s)))
