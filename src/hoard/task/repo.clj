(ns hoard.task.repo
  "Repository tasks."
  (:require
    [clojure.string :as str]
    [hoard.core.archive :as archive]
    [hoard.core.version :as version]
    [hoard.task.util :as u]))


(defn print-create-usage
  []
  (println "Usage: hoard [options] create <repo>")
  (newline)
  (println "Create a new repository from configuration."))


(defn create-repository
  "Create a new repository from configuration."
  [config args]
  ;; 1. Load repo configuration, or prompt to create it
  ;; 2. Check if repository is already initialized, or initialize it
  ;; 3. Show basic information about the repo
  ,,,)
