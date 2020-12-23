(ns hoard.repl
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [hoard.data.archive :as archive]
    [hoard.data.version :as version]
    [hoard.repo.config :as cfg]
    [hoard.stuff :as stuff]
    [manifold.deferred :as d]))


(defn get-archive
  []
  (archive/find-root (io/file "example")))
