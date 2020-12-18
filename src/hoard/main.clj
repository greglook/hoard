(ns hoard.main
  "Main entry for hoard tool."
  (:gen-class)
  (:require
    [clojure.stacktrace :as cst]
    [clojure.tools.cli :as cli]
    [hoard.file.ini :as ini]
    [hoard.repo.config :as cfg]
    [hoard.task.list :as list]
    [hoard.task.repo :as repo]
    [hoard.task.show :as show]
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
  (println "    create    Create a new horde repository")
  (println "    list      List the archives present in a repository")
  (println "    show      Print information about a repository, archive, or version")
  ;(println "    status    Show the current status of a working directory")
  ;(println "    archive   Store a new snapshot version of an archive")
  ;(println "    restore   Restore a saved version of an archive")
  ;(println "    verify    Check the integrity of the repository")
  ;(println "    trim      Trim the version history and remove unused data")
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
        ;"create"  (repo/print-create-usage)
        "list"    (list/print-usage)
        "show"    (show/print-usage)
        ;"init"
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
      (let [config (ini/read (cfg/config-file))]
        (cfg/with-options options
          (case command
            ;"create"  (repo/create-repo config args)
            "list"    (list/list-archives config args)
            "show"    (show/show-info config args)
            ;"init"    (archive/initialize-local config args)
            ;"status"  (archive/print-status config args)
            ;"keep"    (archive/keep-data config args)  (also, "save", "preserve")
            ;"restore" (archive/restore-data config args)
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
