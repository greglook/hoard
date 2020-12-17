(ns hoard.stuff
  "Work on the function instead of the form."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    (java.io
      File
      InputStream
      OutputStream)
    (java.nio.file
      FileVisitOption
      Files
      LinkOption
      Path)
    (java.nio.file.attribute
      FileTime
      PosixFilePermission)
    java.util.concurrent.TimeUnit
    org.apache.commons.io.input.CountingInputStream
    org.apache.commons.io.output.CountingOutputStream))


(defn stopwatch
  "Create a delay which yields the number of milliseconds between the time this
  function was called and when it was realized."
  []
  (let [start (System/nanoTime)]
    (delay (/ (- (System/nanoTime) start) 1e6))))



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
      {:type :symlink
       :path (str path)
       :target (str (Files/readSymbolicLink path))
       :permissions permissions
       :modified-at modified-at}

      (Files/isRegularFile path no-follow-links)
      {:type :file
       :path (str path)
       :size size
       :permissions permissions
       :modified-at modified-at}

      (Files/isDirectory path no-follow-links)
      {:type :directory
       :path (str path)
       :permissions permissions
       :modified-at modified-at}

      :else
      {:type :unknown
       :path (str path)})))


(defn walk-files
  "Walk a filesystem depth-first, returning a sequence of file metadata. This
  includes the (relative) path, file type, size, permissions, and modified
  time."
  [^File root]
  ;; TODO: this is neat, but how will it handle ignored directories?
  (->>
    (Files/walk (.toPath root) (into-array java.nio.file.FileVisitOption []))
    (.iterator)
    (iterator-seq)
    (map path-stats)))



;; ## Root Finding

(defn find-archive-root
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
        {:root (.getCanonicalPath dir)
         :config {}
         :ignore #{}}
        (recur (.getParentFile dir) (dec limit))))))



;; ## Process Piping

(defn pipe-process
  "Pipe the provided stream of input data through a process invoked with the
  given arguments. Writes output data to the given output stream. Returns a
  deferred which yields information about the transfer on success."
  [command ^InputStream in ^OutputStream out]
  ;; TODO: what to do if the process needs human input?
  (let [elapsed (stopwatch)
        process (.start (ProcessBuilder. ^java.util.List command))
        stdin (CountingOutputStream. (.getOutputStream process))
        stdout (CountingInputStream. (.getInputStream process))
        input-copier (future
                       (io/copy in stdin)
                       (.close stdin)
                       (.close in))
        output-copier (future
                        (io/copy stdout out)
                        (.close stdout)
                        (.close out))]
    (if (.waitFor process 60 TimeUnit/SECONDS)
      (do
        @input-copier
        @output-copier)
      (do
        (future-cancel input-copier)
        (future-cancel output-copier)
        (.destroy process)))
    (let [exit (.exitValue process)]
      {:exit exit
       :success? (zero? exit)
       :stderr (slurp (.getErrorStream process))
       :elapsed @elapsed
       :input-bytes (.getByteCount stdin)
       :output-bytes (.getByteCount stdout)})))
