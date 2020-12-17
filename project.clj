(defproject mvxcvi/hoard "0.1.0-SNAPSHOT"
  :description "Securely storing data archives."
  :url "https://github.com/greglook/hoard"
  :license {:name "Public Domain"
            :url "http://unlicense.org/"}

  :dependencies
  [[org.clojure/clojure "1.10.2-alpha1"]
   [org.clojure/tools.cli "1.0.194"]
   [mvxcvi/blocks "2.1.0-SNAPSHOT"]]

  :main hoard.main

  :hiera
  {:cluster-depth 2
   :vertical true
   :show-external false
   :ignore-ns #{"clojure"}}

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
