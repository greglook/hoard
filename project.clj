(defproject mvxcvi/hoard "0.1.0-SNAPSHOT"
  :description "Securely storing data archives."
  :url "https://github.com/greglook/hoard"
  :license {:name "Public Domain"
            :url "http://unlicense.org/"}

  :dependencies
  [[org.clojure/clojure "1.10.2-alpha1"]
   [org.clojure/tools.cli "1.0.194"]
   [mvxcvi/blocks "2.0.4"]]

  :main hoard.main

  :profiles
  {:repl
   {:source-paths ["dev"]
    :repl-options {:init-ns hoard.repl}
    :dependencies [[org.clojure/tools.namespace "1.1.0"]]}

   :uberjar
   {:target-path "target/uberjar"
    :uberjar-name "hoard.jar"
    :global-vars {*assert* false}
    :jvm-opts ["-Dclojure.compiler.direct-linking=true"
               "-Dclojure.spec.skip-macros=true"]
    :aot :all}})
