(defproject escape-pod "0.0.1"
  :dependencies [[cljsjs/nodejs-externs "1.0.4-1"]
                 [cljsjs/twemoji "11.2.0-0"]
                 [funcool/cuerdas "2.2.0"]
                 [funcool/promesa "2.0.1"]
                 [garden "1.3.6"]
                 [hiccups "0.3.0"]
                 [markdown-clj "1.0.7"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.10.0"]
                 [org.clojure/clojurescript "1.10.520"]
                 [org.clojure/tools.cli "0.4.1"]]

  :plugins [[lein-cljsbuild "1.1.7"]]

  :cljsbuild {:builds [{:id "prod"
                        :source-paths ["src"]
                        :compiler {:externs ["externs.js"]
                                   :main escape-pod.core
                                   :output-dir "target"
                                   :output-to "index.js"
                                   :optimizations :advanced
                                   :parallel-build true
                                   :source-map "index.js.map"
                                   :target :nodejs}}]})
