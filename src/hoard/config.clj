(ns hoard.config
  "Configuration and options for the tool.

  Hoard supports a configuration file at `$XDG_CONFIG_HOME/hoard/config`. The
  syntax is loosely based on TOML or INI files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [clojure-ini.core :as ini]))


;; ## Options

(def ^:dynamic *options*
  "Runtime options."
  {})


(defmacro with-options
  "Evaluate the expressions in `body` with the print options bound to `opts`."
  [opts & body]
  `(binding [*options* ~opts]
     ~@body))


(defn option
  "Return the value set for the given option, if any."
  [k]
  (get *options* k))



;; ## Configuration File

(defn config-file
  "Resolve the location of the configuration file by checking the
  `XDG_CONFIG_HOME`, and `HOME` environment variables. Returns the config file,
  without checking whether it exists."
  []
  (let [cfg-home (System/getenv "XDG_CONFIG_HOME")
        home (System/getenv "HOME")]
    (if-not (str/blank? cfg-home)
      (io/file cfg-home "hoard" "config")
      (io/file home ".config" "hoard" "config"))))


(defn read-config
  "Read a configuration file, returning the structured config data."
  [file]
  (reduce-kv
    (fn split-sections
      [cfg section-key data]
      (let [path (mapv keyword (str/split section-key #"\."))]
        (assoc-in cfg path (walk/keywordize-keys data))))
    {}
    (ini/read-ini
      file
      :keywordize? false
      :comment-char \#)))
