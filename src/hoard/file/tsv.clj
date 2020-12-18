(ns hoard.file.tsv
  "Utility functions for working with tab-separated-value files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    java.time.Instant))


(defn parse-long
  "Parse a long value from a string."
  [s]
  (Long/parseLong s))


(defn parse-inst
  "Parse an instant value from a string."
  [s]
  (Instant/parse s))


(defn format-header
  "Format a row of column headers."
  [columns]
  (->>
    columns
    (map (comp name :name) columns)
    (str/join "\t")))


(defn format-row
  "Format a row of TSV using the given column definitions. Returns the row
  string encoding the record data."
  [columns record]
  (->>
    columns
    (map (fn encode-cell
           [column]
           (if-some [value (get record (:name column))]
             (let [encode (:encode column str)]
               (encode value))
             "")))
    (str/join "\t")))


(defn parse-row
  "Parse a row of TSV using the given column definitions. Returns the record
  data decoded from the row."
  [columns row]
  (->>
    (str/split row #"\t")
    (map vector columns)
    (keep (fn decode-cell
            [[column cell]]
            (when-not (str/blank? cell)
              [(:name column)
               (let [decode (:decode column identity)]
                 (decode cell))])))
    (into {})))


(defn read-lines
  "Read the lines of a file. Returns a lazy sequence of the record data."
  [columns lines]
  (let [expected (format-header columns)
        [header & rows] lines]
    (when (not= expected header)
      (throw (ex-info (str "Read unexpected header: " (pr-str header))
                      {:header header
                       :expected expected})))
    (map (partial parse-row columns) rows)))


(defn read-data
  "Read the contents of a file. Returns a lazy sequence of the record data, or
  throws an exception if the header does not match."
  [in columns]
  (read-lines columns (line-seq (io/reader in))))


(defn write-data!
  "Write the header and records to the given output stream."
  [out columns records]
  (binding [*out* (io/writer out)]
    (println (format-header columns))
    (->>
      records
      (map (partial format-row columns))
      (run! println))
    (flush)))
