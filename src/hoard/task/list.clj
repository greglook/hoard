(ns hoard.task.list
  (:require
    [clojure.string :as str]
    [hoard.core.archive :as archive]
    [hoard.core.version :as version]
    [hoard.store.core :as store]
    [hoard.task.util :as u]))


(defn print-usage
  "Print help for the list command."
  []
  (println "Usage: hoard [options] list <repo> [repo...]")
  (newline)
  (println "List the archives present in the named repositories."))


(defn- print-archive
  "Print a line of information about an archive belonging to a repo."
  [repo-name archive]
  (->>
    [repo-name
     (::archive/name archive)
     (format "%d versions" (count (::archive/versions archive)))
     (::version/id (peek (::archive/versions archive)))]
    (str/join "\t")
    (println)))


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
          (store/list-archives (:archives repo) {})
          (sort-by ::archive/name)
          (run! (partial print-archive repo-name)))
        (flush)))
    args))
