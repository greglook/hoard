(ns hoard.stuff
  "Work on the function instead of the form."
  (:require
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [hoard.data.version :as version]
    [hoard.file.tsv :as tsv]
    [manifold.deferred :as d]
    [multiformats.hash :as multihash])
  (:import
    (java.io
      File
      InputStream
      OutputStream
      PipedInputStream
      PipedOutputStream)
    java.util.concurrent.TimeUnit
    (java.util.zip
      GZIPInputStream
      GZIPOutputStream)
    org.apache.commons.io.input.CountingInputStream
    org.apache.commons.io.output.CountingOutputStream))


(defn stopwatch
  "Create a delay which yields the number of milliseconds between the time this
  function was called and when it was realized."
  []
  (let [start (System/nanoTime)]
    (delay (/ (- (System/nanoTime) start) 1e6))))



;; ## Process Piping

(defn- piped-streams
  "Construct a pair of connected output and input streams, forming a pipe.
  Returns a tuple of the output stream sink and the input stream source."
  [buffer-size]
  (let [pipe-src (PipedInputStream. (int buffer-size))
        pipe-sink (PipedOutputStream. pipe-src)]
    [pipe-sink pipe-src]))


(defn- future-copy
  "Copy all of the data from `in` to `out` on a new thread, closing `in` after.
  Does *not* close `out`. Returns a future which yields true when the copy is
  complete."
  [^InputStream in ^OutputStream out]
  (future
    (try
      (io/copy in out)
      true
      (finally
        (.close in)))))


(defn pipe-process
  "Pipe the provided stream of input data through a process invoked with the
  given arguments. Writes output data to the given output stream. Returns a
  deferred which yields information about the transfer on success."
  [command ^InputStream in ^OutputStream out]
  (d/future
    ;; TODO: what to do if the process needs human input?
    ;; Graphical pinentry programs work
    (let [elapsed (stopwatch)
          process (.start (ProcessBuilder. ^java.util.List command))
          stdin (CountingOutputStream. (.getOutputStream process))
          stdout (CountingInputStream. (.getInputStream process))
          input-copier (future-copy in stdin)
          output-copier (future-copy stdout out)]
      (if (.waitFor process 60 TimeUnit/SECONDS)
        (do
          @input-copier
          @output-copier)
        (do
          (future-cancel input-copier)
          (future-cancel output-copier)
          (.destroy process)))
      (let [exit (.exitValue process)]
        (merge
          {:success? (zero? exit)
           :elapsed @elapsed
           :input-bytes (.getByteCount stdin)
           :output-bytes (.getByteCount stdout)}
          (when-not (zero? exit)
            {:exit exit})
          (let [stderr (slurp (.getErrorStream process))]
            (when-not (str/blank? stderr)
              {:error stderr})))))))


(defn write-version
  "Writes a sequence of version index data to the given output stream, after
  compressing it and running it through the given command. Returns a deferred
  which yields the pipe output when finished."
  [^OutputStream out command version]
  (d/future
    (let [[pipe-sink pipe-src] (piped-streams 4096)
          gzip-out (GZIPOutputStream. pipe-sink)
          count-out (CountingOutputStream. gzip-out)
          encrypt (pipe-process command pipe-src out)]
      (version/write-data! count-out version)
      (.close count-out)
      (->
        @encrypt
        (assoc :raw-size (.getByteCount count-out))
        (set/rename-keys
          {:input-bytes :compressed-size
           :output-bytes :encrypted-size})))))


(defn read-version
  "Read version index data from the given input stream. Returns a deferred
  which yields the process result with the version data under `:version` on
  success."
  [^InputStream in command]
  ;; WARNING: the GZIP input stream seems to read some bytes to determine the
  ;; encoding of the stream *during construction*, so if it is created before
  ;; the process which fills the pipe, the thread will deadlock.
  (let [[pipe-sink pipe-src] (piped-streams 4096)]
    (d/chain
      (d/zip
        (pipe-process command in pipe-sink)
        (d/future
          (let [gzip-in (GZIPInputStream. pipe-src)
                count-in (CountingInputStream. gzip-in)
                version (version/read-data count-in)]
            (.close count-in)
            [version (.getByteCount count-in)])))
      (fn combine
        [[proc [version raw-size]]]
        (-> proc
            (assoc :raw-size raw-size
                   :version version)
            (set/rename-keys
              {:input-bytes :encrypted-size
               :output-bytes :compressed-size}))))))
