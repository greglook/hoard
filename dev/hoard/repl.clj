(ns hoard.repl
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [hoard.config :as cfg]
    [hoard.repo.archive :as archive]
    [hoard.repo.index :as index]
    [hoard.repo.version :as version]
    [hoard.store.core :as store]
    [hoard.store.memory :refer [memory-repository]]))


(def repo
  (memory-repository {}))
