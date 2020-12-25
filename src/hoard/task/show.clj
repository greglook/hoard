(ns hoard.task.show
  (:require
    [clojure.string :as str]
    [hoard.data.archive :as archive]
    [hoard.data.repository :as repo]
    [hoard.data.version :as version]
    [hoard.task.util :as u]))


(defn print-usage
  "Print help for the list command."
  []
  (println "Usage: hoard [options] show <repo> [archive] [version]")
  (newline)
  (println "Show information about a repository, archive, or version."))


(defn show-info
  "Implementation of the `show` command."
  [config args]
  ;; - if no args, error
  ;; - if repo, show ...?
  ;; - if repo and archive, show ...?
  ;; - if repo/archive/version, show ...?
  ;; - if more args, error
  ,,,)
