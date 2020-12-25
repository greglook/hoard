(ns hoard.repo.config
  "Configuration and options for the tool.

  Hoard supports a configuration file at `$XDG_CONFIG_HOME/hoard/config`. The
  syntax is loosely based on TOML or INI files."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))


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


(defn- update-some
  "Update an associative data structure by applying `f` to the current value
  and `args`, if the current value at `k`. Returns the structure unchanged if
  `k` is not present."
  [m k f & args]
  (if (find m k)
    (apply update m k f args)
    m))


(defn repo-config
  "Parse the configuration for a repository from the config data. Returns the
  config merged with defaults, or nil if no such repository is configured."
  [config repo-name]
  (when-let [repo-config (get-in config [:repository (keyword repo-name)])]
    (-> (:defaults config)
        (merge repo-config)
        (update-some :type keyword)
        (update-some :trim.keep-days #(Integer/parseInt (str %)))
        (update-some :trim.keep-versions #(Integer/parseInt (str %))))))
