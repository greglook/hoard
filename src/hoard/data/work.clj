(ns hoard.data.work
  "Lightweight observability code for work reporting."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [manifold.time :as mt])
  (:import
    (java.io
      File
      InputStream
      OutputStream)
    java.time.Instant
    java.util.UUID
    org.apache.commons.io.input.CountingInputStream
    org.apache.commons.io.output.CountingOutputStream))


(defn stopwatch
  "Create a delay which yields the number of milliseconds between the time this
  function was called and when it was realized."
  []
  (let [start (System/nanoTime)]
    (delay (/ (- (System/nanoTime) start) 1e6))))


;; Event types:
;; - general log output
;; - report a task starting with known or unknown total work to do
;; - report progress on a task, either adding to or setting the current progress
;; - report a task completing successfully
;; - report a task failing
;;
;; Ideas:
;; - generically show progress bars and estimated time
;; - infer task "tree" from dynamic context
;; - async reporting over a channel (stream?)
;; - consumed to create the "current" view of work
;; - wrapper for an input stream with regular periodic measurement
;; - wrapper for doseq that reports each iteration


(defn report
  "Report a new event to the stream."
  [event]
  ;; TODO: something fancier
  (let [event (assoc event :time (Instant/now))]
    (binding [*out* *err*]
      (prn ::report! event))))


(defn log
  "Report a message log event."
  [msg & args]
  (report {:type :log
           :message (if (seq args)
                      (apply format msg args)
                      msg)}))


(defn ^:no-doc watch*
  "Internal helper for watch macro."
  [data body-fn]
  (let [id (UUID/randomUUID)
        elapsed (stopwatch)]
    (report (assoc data
                   :id id
                   :type :start))
    (try
      (let [result (body-fn)]
        (report (assoc data
                       :id id
                       :type :ok
                       :elapsed @elapsed))
        result)
      (catch Exception ex
        (report {:id id
                 :type :fail
                 :elapsed @elapsed
                 :error ex})
        (throw ex)))))


(defmacro watch
  "Watch the given forms."
  [data & body]
  `(watch* ~data (fn [] ~@body)))


(defn ^:no-doc for-progress*
  "Helper function for the progress iterator macro."
  [data f coll]
  (let [id (UUID/randomUUID)
        elapsed (stopwatch)
        f' (fn monitor
             [x]
             (let [result (f x)]
               (report {:id id
                        :type :progress
                        :progress :increment
                        :work 1})
               result))]
    (report (assoc data
                   :id id
                   :type :start
                   :total (count coll)))
    (try
      ;; TODO: make this deferred-aware?
      (let [result (mapv f' coll)]
        (report {:id id
                 :type :ok
                 :elapsed @elapsed})
        result)
      (catch Exception ex
        (report {:id id
                 :type :fail
                 :elapsed @elapsed
                 :error ex})
        (throw ex)))))


(defmacro for-progress
  "Execute the body of code once for each item in the collection, binding it to
  the given symbol. The data map following the bindings should provide the work
  item name. Automatically emits start, progress, and end events. Returns a
  vector of the results of evaluating `body` on each item."
  [[sym coll :as bindings] data & body]
  {:pre [(vector? bindings)
         (= 2 (count bindings))
         (map? data)]}
  `(for-progress*
     ~data
     (fn [~sym] ~@body)
     ~coll))


(defn ^:no-doc with-input-progress*
  "Helper function for the input progress macro."
  [data stream-fn body-fn]
  (let [id (UUID/randomUUID)
        elapsed (stopwatch)]
    (report (assoc data :id id, :type :start))
    (try
      (with-open [input (stream-fn)]
        (let [counter (CountingInputStream. input)
              monitor (fn monitor
                        []
                        (report {:id id
                                 :type :progress
                                 :progress :absolute
                                 :work (.getByteCount counter)}))
              cancel (mt/every 1000 1000 monitor)]
          (try
            (let [result (body-fn counter)]
              (report {:id id
                       :type :ok
                       :elapsed @elapsed})
              result)
            (finally
              (cancel)))))
      (catch Exception ex
        (report {:id id
                 :type :fail
                 :elapsed @elapsed
                 :error ex})
        (throw ex)))))


(defmacro with-input
  "Open an input stream wrapped with a counter that will periodically report
  the number of bytes transferred through it."
  [[sym stream-expr :as bindings] data & body]
  {:pre [(vector? bindings)
         (= 2 (count bindings))
         (map? data)]}
  `(with-input-progress*
     ~data
     (fn [] ~stream-expr)
     (fn [~sym] ~@body)))


(defmacro with-file-input
  "Like `with-input`, but takes a file directly and uses its size as the total
  amount of work to do."
  [[sym file-expr :as bindings] data & body]
  {:pre [(vector? bindings)
         (= 2 (count bindings))
         (map? data)]}
  `(let [^java.io.File file# ~file-expr]
     (with-input-progress*
       (assoc ~data :total (.length file#))
       (fn [] (io/input-stream file#))
       (fn [~sym] ~@body))))
