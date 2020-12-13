(ns hoard.task.list
  (:require
    [clojure.string :as str]
    [hoard.repo.archive :as archive]
    [hoard.store.core :as store]
    [hoard.task.util :as u]))


(defn print-usage
  "Print help for the list command."
  []
  (println "Usage: hoard [options] list <repo> [repo...]")
  (newline)
  (println "List the archives present in the named repositories."))


(defn list-archives
  "Implementation of the `list` command."
  [config args]
  (when-not (seq args)
    (u/printerr "list command requires at least one repository name")
    (u/exit! 1))
  (run!
    (fn list-repo
      [repo-name]
      (let [repo (u/init-repo config repo-name)]
        (->>
          (store/list-archives repo {})
          (map ::archive/name)
          (sort)
          (run! println))
      (flush)))
    args))
