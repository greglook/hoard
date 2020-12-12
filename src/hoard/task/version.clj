(ns hoard.task.version
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [hoard.task.util :as u]))


(def version
  "Project version string."
  (if-let [props-file (io/resource "META-INF/maven/mvxcvi/hoard/pom.properties")]
    (with-open [props-reader (io/reader props-file)]
      (let [props (doto (java.util.Properties.)
                    (.load props-reader))
            {:strs [groupId artifactId version revision]} props]
        (format "%s/%s %s (%s)"
                groupId artifactId version
                (str/trim-newline revision))))
    "HEAD"))


(defn print-usage
  "Print help for the version command."
  []
  (println "Usage: hoard [options] version")
  (newline)
  (println "Print build version information about the tool."))


(defn print-version
  "Implementation of the `version` command."
  [args]
  (when (seq args)
    (u/printerr "hoard version command takes no arguments")
    (u/exit! 1))
  (println version)
  (flush))
