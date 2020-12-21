(ns hoard.repl
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [hoard.repo.archive :as archive]
    [hoard.repo.config :as cfg]
    [hoard.repo.index :as index]
    [hoard.repo.version :as version]
    [hoard.stuff :as stuff]
    [hoard.store.core :as store]
    [hoard.store.memory :refer [memory-repository]]
    [manifold.deferred :as d]))


(def repo
  (memory-repository {}))
