(ns escape-pod.templates
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require
    [cljs.nodejs :as nodejs]
    [cljsjs.twemoji]
    [cuerdas.core :as str]
    [hiccups.runtime :as hiccupsrt]
    [markdown.core :as md]
    [sci.core :as sci]))

(def path (nodejs/require "path"))
(def fs (nodejs/require "fs-extra"))
(def humanize-url (nodejs/require "humanize-url"))
(def linkify (nodejs/require "linkifyjs"))
(def linkify-html (nodejs/require "linkifyjs/html"))
(def smartypants (.-smartypants (nodejs/require "smartypants")))
(def truncate-url (nodejs/require "truncate-url"))

(defn get-helpers []
  {:emojify #(js/twemoji.parse %)
   :json-stringify #(js/JSON.stringify (clj->js %))
   :linkify #(linkify-html % #js {:format (fn [value type]
                                            (if (= type "url")
                                              (truncate-url (humanize-url value) 30)
                                              value))})
   :md->html md/md->html
   :smartypants smartypants
   :uslug str/uslug})

(defn resolve-template
  ([template]
   (resolve-template template ".cljs"))
  ([template extension]
   (first (filter #(.existsSync fs %)
                  (map #(path.join % "templates" (str template extension))
                       [(js/process.cwd) js/__dirname]))) ))

(defn render
  ([template]
   (render template {}))
  ([template options]
   (html (sci/eval-string
           (.readFileSync fs (resolve-template template) "utf8")
           {:bindings (reduce-kv
                        (fn [m k v]
                          (assoc m (if (keyword? k)
                                     (symbol (name k))
                                     k)
                                 v))
                        {}
                        (merge (get-helpers) options))}))))
