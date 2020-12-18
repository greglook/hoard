(ns hoard.file.ini
  "Simple INI file reader."
  (:refer-clojure :exclude [read])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))


(defn- strip-comment
  "Remove comments from the line."
  [line]
  (if-let [n (str/index-of line \#)]
    (subs line 0 n)
    line))


(defn- parse-line
  "Parse a line of configuration into either a section header or an entry."
  [line]
  (if (str/starts-with? line "[")
    (let [n (or (str/index-of line "]")
                (throw (ex-info (str "Missing closing section delimiter: " line)
                                {:line line})))]
      (str/trim (subs line 1 n)))
    (let [n (or (str/index-of line "=")
                (throw (ex-info (str "Could not parse line: " line)
                                {:line line})))]
      [(str/trim (subs line 0 n))
       (str/trim (subs line (inc n)))])))


(defn- parse-value
  "Parse a configuration value by recognizing booleans and numbers."
  [v]
  (cond
    (= "true" v)
    true

    (= "false" v)
    false

    (re-matches #"-?\d+" v)
    (Long/parseLong v)

    (re-matches #"-?\d+\.\d*" v)
    (Double/parseDouble v)

    :else
    v))


(defn- build-map
  "Construct a config map from the sequence of parsed lines."
  [entries]
  (loop [entries entries
         section nil
         m {}]
    (if (seq entries)
      ;; process the next entry
      (let [entry (first entries)]
        (if (string? entry)
          ;; section header
          (let [section (update (str/split entry #"\.") 0 keyword)]
            (recur (rest entries)
                   section
                   (assoc-in m section {})))
          ;; key entry
          (recur (rest entries)
                 section
                 (assoc-in m
                           (conj section (keyword (first entry)))
                           (parse-value (second entry))))))
      ;; no more entries
      m)))


(defn read
  "Read a source configuration into a map structure."
  [source]
  (with-open [reader (io/reader source)]
    (->> (line-seq reader)
         (map strip-comment)
         (remove str/blank?)
         (map parse-line)
         (build-map))))
