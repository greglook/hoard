(ns hoard.task.list
  (:require
    [clojure.string :as str]
    [hoard.repo.archive :as archive]
    [hoard.store.core :as store]
    [hoard.task.util :as u]))


(defn print-usage
  "Print help for the list command."
  []
  (println "Usage: hoard [options] list")
  (newline)
  (println "List the archives present in the repository."))


(defn list-archives
  "Implementation of the `list` command."
  [repo args]
  (when (seq args)
    (u/printerr "hoard list command takes no arguments")
    (u/exit! 1))
  (->>
    (store/list-archives repo {})
    (map ::archive/name)
    (sort)
    (run! println))
  (flush))
