(ns escape-pod.core
  (:require-macros [hiccups.core :as hiccups :refer [html]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader :as reader]
            [cljs.tools.cli :refer [parse-opts]]
            [cljsjs.twemoji]
            [cuerdas.core :as str]
            [garden.core :refer [css]]
            [goog.object :as gobj]
            [hiccups.runtime :as hiccupsrt]
            [markdown.core :as md]))

(def fs (nodejs/require "fs"))
(def path (nodejs/require "path"))
(def express (nodejs/require "express"))
(def moment (nodejs/require "moment-timezone"))
(def rimraf (nodejs/require "rimraf"))
(def taglib (nodejs/require "taglib2"))
(def mime-types (nodejs/require "mime-types"))

(nodejs/enable-util-print!)

(defn env-var [v]
  (gobj/get js/process.env v))

(defn emojify [s]
  (.parse js/twemoji s))

(defn read-file! [path]
  (js/Promise.
    (fn [res rej]
      (.readFile fs path "utf8" (fn [err data]
                                  (if err
                                    (rej err)
                                    (res data)))))))

(defn write-file! [path contents]
  (js/Promise.
    (fn [res rej]
      (.writeFile fs path contents "utf8" (fn [err]
                                            (if err
                                              (rej err)
                                              (res)))))))

(defn read-edn! [path]
  (.then (read-file! path)
         #(reader/read-string %)))

(defn mkdir [path]
  (js/Promise.
    (fn [res rej]
      (.mkdir fs path (fn [err]
                        (if err
                          (rej err)
                          (res)))))))
(defn rmdir [path]
  (js/Promise.
    (fn [res rej]
      (rimraf path (fn [err]
                     (if err
                       (rej err)
                       (res)))))))

(defn copy-file [source destination]
  (js/Promise.
    (fn [res rej]
      (.copyFile fs source destination (fn [err]
                                         (if err
                                           (rej err)
                                           (res)))))))

(defn is-directory? [source]
  (.isDirectory (.lstatSync fs source)))

(defn get-directories [source]
  (js/Promise.
    (fn [res rej]
      (.readdir fs source (fn [err result]
                            (if (some? err)
                              (rej err)
                              (res (filter is-directory?
                                           (map #(.join path source %) result)))))))))

(defn episodes-tx [episodes]
  (let [offset (atom 0)]
    (reverse (map-indexed (fn [idx {:keys [number] :as episode}]
                            (merge episode
                                   {:number (if number
                                              (do
                                                (swap! offset inc)
                                                number)
                                              (- (inc idx) @offset))}))
                          (reverse episodes)))))

(defn episode->item [{:keys [base-url description explicit? filename mime-type published-at tags title url]
                      :or {url (str base-url "/episodes/" (str/uslug title) "/" filename)}
                      :as config}]
  [:item
    [:title title]
    [:description description]
    ["itunes:explicit" (if (true? explicit?) "Yes" "No")]
    [:pubDate published-at]
    [:enclosure {:url url
                 :length (get tags :length)
                 :type mime-type}]
    [:guid url]])

(defn rss-feed [{:keys [cover? categories description email episodes explicit? language title author url image]
                 :or {author title
                      image "cover.jpg"
                      language "en-us"}
                 :as config}]
  [:rss {:version "2.0"
         "xmlns:googleplay" "http://www.google.com/schemas/play-podcasts/1.0"
         "xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"
         "xmlns:webfeeds" "http://webfeeds.org/rss/1.0"}
   [:channel
    [:title title]
    [:link url]
    [:description description]
    [:language language]
    ["itunes:author" author]
    ["itunes:owner"
     ["itunes:email" email]]
    ["itunes:subtitle" description]
    ["itunes:explicit" (if (true? explicit?) "Yes" "No")]
    (when cover?
      ["itunes:image" {:href (str url "/" image)}])
    (when cover?
      ["webfeeds:icon" (str url "/" image)])
    (for [category categories]
      ["itunes:category" {:text category}])
    (map (fn [episode]
           (episode->item (merge episode {:base-url url})))
         episodes)]])

(defn episode->article [{:keys [base-url cover? description explicit? filename image published-at mime-type notes number tags title url]
                         :or {url (str base-url "/episodes/" (str/uslug title) "/" filename)}
                         :as config}]
  [:article.center-ns.mw6-ns.hidden.mv4.mh3.ba.b--near-white
   (when cover?
     [:img.db.mv0.w-100 {:src (str base-url "/episodes/" (str/uslug title) "/" image)}])
   [:section.ph2.pv3.ma0.bg-near-white
    [:h2.f5.ma0
     [:a.link.dim.black {:href (str base-url "/episodes/" (str/uslug title))} title]]
    [:h3.f6.ma0.pt2.mid-gray (str "Episode #" number " published " (.format (.tz (moment (js/Date. published-at)) (.guess (.-tz moment))) "LLL z"))]]
   [:section
    [:p.f6.f5-ns.lh-copy.ph2.pv3.ma0.bg-white (emojify description)]
    (when notes
      [:section.f8.f7-ns.lh-copy.ph2.pb2.ma0.bg-white
       [:h4.ttu.ma0.mid-gray "Notes"]
       [:div (-> notes md/md->html emojify)]])]
   [:div.mh2.mb3
    [:audio.w-100 {:controls "controls" :style "z-index: 0;"}
     [:source {:src url :type mime-type}]]]])

(defn twitter-card [{:keys [base-url cover? image site title description]}]
  [[:meta {:name "twitter:card" :content "summary"}]
   [:meta {:name "twitter:site" :content site}]
   [:meta {:name "twitter:creator" :content site}]
   [:meta {:property "og:url" :content (str base-url "/episodes/" (str/uslug title))}]
   [:meta {:property "og:title" :content title}]
   [:meta {:property "og:description" :content description}]
   (when cover?
     [:meta {:property "og:image"
             :content (str base-url "/episodes/" (str/uslug title) "/" image)}])])

(defn style []
  (css [:img.emoji {:height "1em"
                    :width "1em"
                    :margin "0 .05em 0 .1em"
                    :vertical-align "-0.1em"}]))

(defn markup [{:keys [description email episodes explicit? language title author url]
               :or {author title
                    language "en-us"}
               :as config}]
  [:html {:lang language
          :prefix "og: http://ogp.me/ns#"}
   (apply conj
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:http-equiv "x-ua-compatible" :content "ie=edge"}]
           [:title (str title " | " (if (> (count episodes) 1)
                                      description
                                      (get (first episodes) :title) ))]
           [:meta {:name "description" :content (if (> (count episodes) 1)
                                                  description
                                                  (get (first episodes) :description))}]
           [:meta {:name "viewport" :content"width=device-width, initial-scale=1, shrink-to-fit=no"}]
           [:meta {:name "theme-color" :content="#ffffff"}]
           [:link {:rel "manifest" :href (str url "/manifest.json")}]
           [:link {:rel "stylesheet"
                   :href "https://unpkg.com/tachyons@4.9.1/css/tachyons.min.css"}]]
          [:style (style)]
          (if (> (count episodes) 1)
            [[:link {:rel "alternate"
                    :type "application/rss+xml"
                    :title title
                    :href (str url "/rss/podcast.rss")}]]
            (when-some [site (get-in config [:social :twitter])]
              (twitter-card (merge (first episodes)
                                   {:base-url url :site site})))))
   [:body.system-sans-serif
    [:section
     [:header.bg-white.fixed.w-100.ph3.pv3 {:style "z-index: 1;"}
      [:h1.f1.f-4-ns.lh-solid.center.tc.mv0
       [:a.link.dim.mid-gray {:href url} title]]
      [:h2.f5.dark-gray.fw2.tc.tracked description]]]
    [:section.pt6
     (map (fn [episode]
            (episode->article (merge episode {:base-url url})))
          episodes)]]])

(defn manifest [{:keys [title] :as config}]
  (js/JSON.stringify
    #js {"name" title
         "short_name" title
         "start_url" "/"
         "display" "standalone"
         "background_color" "#FFFFFF"
         "theme-color" "#FFFFFF"}))

(defn load-episode! [dir source]
  (.then (read-edn! (str dir source))
         (fn [{:keys [image filename]
               :or {image "cover.jpg"
                    filename "episode.mp3"}
               :as conf}]
           (merge {:filename filename
                   :image image}
                  conf
                  {:cover? (.existsSync fs (str dir "/" image))
                   :dir dir
                   :mime-type (.lookup mime-types filename)
                   :notes (when (.existsSync fs (str dir "/notes.md"))
                            (.readFileSync fs (str dir "/notes.md") "utf8"))
                   :origin (str dir "/" filename)
                   :tags (js->clj (.readTagsSync taglib (str dir "/" filename))
                                  :keywordize-keys true)}))))

(defn load-episodes! [source]
  (.then (get-directories source)
         (fn [directories]
           (.then (js/Promise.all
                    (map #(load-episode! % "/config.edn") 
                         directories))
                  (fn [episodes]
                    (episodes-tx
                      (reverse (sort-by #(js/Date. (get % :published-at))
                                        episodes))))))))

(defn render-html [state]
  (str "<!DOCTYPE html>"
       (html (markup state))))

(defn load-site! [{:keys [config]}]
  (.then
    (js/Promise.all
      [(read-edn! config)
       (load-episodes! "site/episodes")])
    (fn [[config episodes]]
      (let [image (get config :image "cover.jpg")
            state (merge config {:cover? (and image (.existsSync fs (str "site/" image)))
                                 :episodes episodes})]
        {:state state
         :manifest (manifest config)
         :html (render-html state)
         :rss (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                   (html (rss-feed state)))}))))

(defn write-site! [{:keys [state html manifest rss]}
                   {:keys [output-dir] :or {output-dir "./www"} :as options}]
  (.then
    (.then
      (.then
        (.then (rmdir output-dir)
               #(mkdir output-dir))
        #(js/Promise.all
           [(mkdir (str output-dir "/episodes"))
            (mkdir (str output-dir "/rss"))]))
      (fn  []
        (let  [image (get state :image "cover.jpg")
               cover? (get state :cover?)
               promises [(write-file! (str output-dir "/index.html") html)
                         (write-file! (str output-dir "/manifest.json") manifest)
                         (write-file! (str output-dir "/rss/podcast.rss") rss)]]
          (js/Promise.all
            (if cover?
              (conj promises (copy-file (str "site/" image)
                                        (str output-dir "/" image)))
              promises))))
      #(js/Promise.all
         [(write-file! (str output-dir "/index.html") html)
          (write-file! (str output-dir "/manifest.json") manifest)
          (write-file! (str output-dir "/rss/podcast.rss") rss)])) 
    (fn []
      (js/Promise.all
        (map (fn [{:keys [cover? dir filename image origin title]
                   :as episode}]
               (let [episode-dir (str output-dir "/episodes/" (str/uslug title))]
                 (.then (mkdir episode-dir)
                        (fn []
                          (let [promises [(write-file! (str episode-dir "/index.html")
                                                       (render-html (merge state {:episodes [episode]})))
                                          (copy-file origin (str episode-dir "/" filename))]]
                            (js/Promise.all
                              (if cover?
                                (conj promises
                                      (copy-file (str dir "/" image)
                                                 (str episode-dir "/" image)))
                                promises)))))))
             (get state :episodes))))))

(defn build! [options]
  (.then (load-site! options)
         #(write-site! % options)))

(defn load-site-middleware! [options]
  (fn [req res nxt]
    (-> (load-site! options)
        (.then #(do
                  (gobj/set req "site" %)
                  (nxt)))
        (.catch #(nxt %)))))

(defn load-episode-middleware! [req res nxt]
  (let [title (.. req -params -title)
        {:keys [state]} (.. req -site)]
    (if-some [episode (first
                        (filter #(= title (str/uslug (get % :title)))
                                (get state :episodes)))]
      (do
        (gobj/set req "episode" episode)
        (nxt))
      (.sendStatus res 404))))

(defn serve! [{:keys [port] :as options}]
  (doto (express)
    (.use (load-site-middleware! options))
    (.get "/"
          (fn [req res]
            (.send res (get (.. req -site) :html))))
    (.get "/episodes/:title"
          load-episode-middleware!
          (fn [req res]
            (.send res (render-html (merge (get (.. req -site) :state)
                                           {:episodes [(.. req -episode)]})))))
    (.get "/episodes/:title/:filename"
          load-episode-middleware!
          (fn [req res]
            (let [{:keys [dir]} (.. req -episode)
                  filename (.. req -params -filename)]
              (.sendFile res (str dir "/" (.basename path filename))
                             #js {:root (.cwd js/process)}
                             #(when %
                                (.error js/console %))))))
    (.get "/manifest.json"
          (fn [req res]
            (.send res (get (.. req -site) :manifest))))
    (.get "/rss/podcast.rss"
          (fn [req res]
            (doto res
              (.setHeader "Content-Type" "application/rss+xml")
              (.send (get (.. req -site) :rss)))))
    (.listen port #(println "Open the pod bay doors at"
                            (str "http://localhost:" port)))))

(def cli-options
  [["-c" "--config PATH" "Configuration path"
    :default "./config.edn"]
   ["-p" "--port PORT" "Port number"
    :default (or (env-var "PORT") 5000)
    :parse-fn #(js/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-h" "--help"]])

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (and (= 1 (count arguments))
           (#{"build" "serve"} (first arguments)))
      {:action (first arguments) :options options})))

(defn exit [status msg]
  (println msg)
  (.exit js/process status))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "build" (build! options)
        "serve" (serve! options)))))

(set! *main-cli-fn* -main)
