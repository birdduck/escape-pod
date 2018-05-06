(defproject escape-pod "0.0.1"
  :dependencies [[cljsjs/nodejs-externs "1.0.4-1"]
                 [cljsjs/twemoji "2.4.0-0"]
                 [funcool/cuerdas "2.0.5"]
                 [garden "1.3.5"]
                 [hiccups "0.3.0"]
                 [markdown-clj "1.0.2"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.238"]
                 [org.clojure/tools.cli "0.3.7"]]

  :plugins [[lein-cljsbuild "1.1.7"]]

  :cljsbuild {:builds [{:id "prod"
                        :source-paths ["src"]
                        :compiler {:externs ["externs.js"]
                                   :main escape-pod.core
                                   :output-dir "target"
                                   :output-to "index.js"
                                   :optimizations :advanced
                                   :parallel-build true
                                   :target :nodejs}}]})
