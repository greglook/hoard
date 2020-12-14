(ns hoard.main
  "Main entry for hoard tool."
  (:gen-class)
  (:require
    [clojure.stacktrace :as cst]
    [clojure.tools.cli :as cli]
    [hoard.repo.config :as cfg]
    [hoard.task.list :as list]
    [hoard.task.version :as version]))


(def ^:private cli-options
  "Command-line tool options."
  [,,,
   ;[nil  "--no-color" "Don't output ANSI color codes"]
   ;["-v" "--verbose" "Print detailed debugging output"]
   ["-h" "--help" "Show help and usage information"]])


(defn- print-general-usage
  "Print general usage help for the tool."
  [summary]
  (println "Usage: hoard [options] <command> [args...]")
  (newline)
  (println "Commands:")
  (println "    list      List the archives present in the repository")
  (println "    show      Print information about an archive")
  (println "    store     Store a new snapshot version of an archive")
  (println "    restore   Restore a saved version of an archive")
  (println "    verify    Check the integrity of the repository")
  (println "    clean     Trim the version history and remove unused data")
  (println "    stats     Print information about the repository")
  (println "    version   Print program version information.")
  (newline)
  (println "Options:")
  (println summary))


(defn -main
  "Main entry point."
  [& raw-args]
  (let [parsed (cli/parse-opts raw-args cli-options)
        [command & args] (parsed :arguments)
        options (parsed :options)]
    ;; Print any option parse errors and abort.
    (when-let [errors (parsed :errors)]
      (binding [*out* *err*]
        (run! println errors)
        (flush)
        (System/exit 1)))
    ;; Show help for general usage or a command.
    (when (:help options)
      (case command
        "list"    (list/print-usage)
        ;"show"    (task/print-show-usage)
        ;"store"   (task/print-store-usage)
        ;"restore" (task/print-restore-usage)
        ;"verify"  (task/print-verify-usage)
        ;"clean"   (task/print-clean-usage)
        ;"stats"   (task/print-stats-usage)
        "version" (version/print-usage)
        (print-general-usage (parsed :summary)))
      (flush)
      (System/exit 0))
    ;; If no command provided, print help and exit with an error.
    (when-not command
      (print-general-usage (parsed :summary))
      (flush)
      (System/exit 1))
    ;; Execute requested command.
    (try
      ;; TODO: init repository
      (let [repo nil]
        (cfg/with-options options
          (case command
            "list"    (list/list-archives repo args)
            ;"show"    (task/show-archive args)
            ;"store"   (task/store-data args)
            ;"restore" (task/restore-data args)
            ;"verify"  (task/verify-repo args)
            ;"clean"   (task/clean-repo args)
            ;"stats"   (task/repo-stats args)
            "version" (version/print-version args)
            (binding [*out* *err*]
              (println "Unknown hoard command:" command)
              (flush)
              (System/exit 1)))))
      (catch Exception ex
        (binding [*out* *err*]
          (cst/print-cause-trace ex)
          (flush)
          (System/exit 4))))
    ;; Successful tool run if no other exit.
    (System/exit 0)))
