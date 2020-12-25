(ns hoard.task.util
  "Common task utilities."
  (:require
    [clojure.string :as str]
    [hoard.repo.config :as cfg]
    [hoard.store.file :refer [file-repository]]
    [hoard.store.memory :refer [memory-repository]]))


;; ## Options

(def ^:dynamic *options*
  "Runtime options."
  {})


(defmacro with-options
  "Evaluate the expressions in `body` with the print options bound to `opts`."
  [opts & body]
  `(binding [*options* ~opts]
     ~@body))


(defn option
  "Return the value set for the given option, if any."
  [k]
  (get *options* k))



;; ## Coloring

(def ^:private ansi-codes
  {:reset "[0m"
   :red   "[031m"
   :green "[032m"
   :cyan  "[036m"})


(defn colorize
  "Wrap the string in ANSI escape sequences to render the named color."
  [s color]
  {:pre [(ansi-codes color)]}
  (str \u001b (ansi-codes color) s \u001b (ansi-codes :reset)))



;; ## Message Output

(defn printerr
  "Print a message to standard error."
  [& messages]
  (binding [*out* *err*]
    (print (str (str/join " " messages) "\n"))
    (flush))
  nil)


(defn printerrf
  "Print a message to standard error with formatting."
  [message & fmt-args]
  (binding [*out* *err*]
    (apply printf (str message "\n") fmt-args)
    (flush))
  nil)


(defn log
  "Log a message which will only be printed when verbose output is enabled."
  [& messages]
  (when (option :verbose)
    (apply printerr messages))
  nil)


(defn logf
  "Log a formatted message which will only be printed when verbose output is
  enabled."
  [message & fmt-args]
  (when (option :verbose)
    (apply printerrf message fmt-args))
  nil)



;; ## Exit Behavior

(def ^:dynamic *suppress-exit*
  "Bind this to prevent tasks from exiting the system process."
  false)


(defn exit!
  "Exit a task with a status code."
  [code]
  (if *suppress-exit*
    (throw (ex-info (str "Task exited with code " code)
                    {:code code}))
    (System/exit code)))



;; ## Repo Setup

(defn init-repo
  "Initialize a repository from the configuration."
  [config repo-name]
  (let [repo-config (cfg/repo-config config repo-name)]
    (when-not repo-config
      (printerr "could not find configuration for repository" repo-name)
      (exit! 2))
    (case (:type repo-config)
      :memory
      (memory-repository repo-config)

      :file
      (file-repository repo-config)

      ;; else
      (do
        (printerr "unknown repository type" (name (:type repo-config)))
        (exit! 3)))))
