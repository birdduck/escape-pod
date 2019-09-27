(ns escape-pod.core
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader :as reader]
            [clojure.tools.cli :refer [parse-opts]]
            [cuerdas.core :as str]
            [escape-pod.templates :as templates]
            [garden.core :refer [css]]
            [goog.object :as gobj]
            [promesa.core :as p :refer-macros [alet]]))

(nodejs/enable-util-print!)

(def fs (nodejs/require "fs-extra"))
(def path (nodejs/require "path"))
(def favicons (nodejs/require "favicons"))
(def mime-types (nodejs/require "mime-types"))
(def moment (nodejs/require "moment-timezone"))
(def music-metadata (nodejs/require "music-metadata"))
(def imagemin (nodejs/require "imagemin"))
(def imagemin-mozjpeg (nodejs/require "imagemin-mozjpeg"))
(def imagemin-optipng (nodejs/require "imagemin-optipng"))

(defn seconds->interval [n]
  (let [hours (js/Math.floor (/ n (* 60 60)))
        minutes (js/Math.floor (/ (- n (* hours 3600)) 60))
        seconds (js/Math.round (- n (* minutes 60) (* hours 3600)))]
    (str/join ":"
              (reduce (fn [m t]
                        (if (and (zero? t) (empty? m))
                          m
                          (conj m (str (when (and (< t 10)
                                                  (not (empty? m)))
                                         0)
                                       t))))
                      []
                      [hours minutes seconds]))))

(defn html->hiccup [s]
  (let [tag (subs (first (str/split s #"\s+")) 1)
        attrs (re-seq #"(\w\w+)=\"([^\"]*)\"" s)]
    [(keyword tag) (reduce
                     (fn [o [_ k v]]
                       (merge o (hash-map (keyword k) v)))
                     {}
                     attrs)]))

(defn read-file! [path] (.readFile fs path "utf8"))
(defn write-file! [path contents] (.writeFile fs path contents))
(defn mkdir! [path] (.mkdir fs path))
(defn rmdir! [path] (.remove fs path))
(defn copy! [source destination] (.copy fs source destination))

(defn read-edn! [path]
  (p/then (read-file! path)
         #(reader/read-string %)))

(defn is-file? [source]
  (.isFile (.lstatSync fs source)))

(defn is-directory? [source]
  (.isDirectory (.lstatSync fs source)))

(defn get-fs-objects!
  ([source] (get-fs-objects! identity source))
  ([pred source]
   (p/then (.readdir fs source)
           (fn [files]
             (filter pred
                     (map #(.join path source %)
                          files))))))

(defn get-files! [source]
  (get-fs-objects!
    #(and
       (not= "site/episodes" %)
       (or (is-file? %)
           (is-directory? %)))
    source))

(defn get-directories [source]
  (get-fs-objects! is-directory? source))

(defn get-paths
  ([source]
   (get-paths source (gobj/get path "sep")))
  ([source sep]
   (loop [paths (str/split source sep)
          results #{}]
      (if (empty? paths)
        results
        (recur (rest paths)
               (conj results
                     (str/join sep
                               (remove nil?
                                       [(last results) (first paths)]))))))))

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

(defn rss-feed [{:keys [title] :as config}]
  (templates/render "rss"
    (merge {:author title
            :image "cover.jpg"
            :language "en-us"
            'str str}
           config)))

(defn style []
  (css [:img.emoji {:height "1em"
                    :width "1em"
                    :margin "0 .05em 0 .1em"
                    :vertical-align "-0.1em"}]))

(defn post [{:keys [episodes image title] :as config}]
  (templates/render "post"
    (merge {:author title
            :language "en-us"
            :image "cover.jpg"
            :episode (first episodes)
            :style style
            'format-date (fn [date fmt]
                          (.format (.tz (moment (js/Date. date)) (.guess (.-tz moment))) fmt))}
           config)))

(defn posts [{:keys [episodes image title] :as config}]
  (templates/render "posts"
    (merge {:author title
            :language "en-us"
            :image "cover.jpg"
            :style style
            'format-date (fn [date fmt]
                          (.format (.tz (moment (js/Date. date)) (.guess (.-tz moment))) fmt))}
           config)))

(defn generate-manifest [{:keys [url cover? image title description] :or {image "cover.jpg"}}]
  (p/promise
    (fn [resolve reject]
      (favicons (str "site/" image)
                #js {:appName title
                     :appDescription description
                     :display "standalone"
                     :start_url "/"
                     :url url}
                (fn [err response]
                  (if err
                    (reject err)
                    (resolve
                      {:resources (map (fn [resource]
                                         {:name (gobj/get resource "name")
                                          :contents (gobj/get resource "contents")})
                                       (concat (gobj/get response "images")
                                               (gobj/get response "files")))
                       :elements (map (fn [html]
                                        (let [[tag attributes] (html->hiccup html)]
                                          [tag (reduce (fn [o k]
                                                         (let [v (get attributes k)]
                                                           (merge o (hash-map k (if (str/starts-with? v "/")
                                                                                  (str url v)
                                                                                  v)))))
                                                       {}
                                                       (keys attributes))]))
                                      (gobj/get response "html"))})))))))

(defn load-episode! [dir source]
  (-> (read-edn! (str dir source))
      (p/then (fn [{:keys [image filename]
                    :or {image "cover.jpg" filename "episode.mp3"}
                    :as conf}]
                (merge {:filename filename
                        :image image}
                       conf
                       {:cover? (.existsSync fs (str dir "/" image))
                        :dir dir
                        :length (.-size (.statSync fs (str dir "/" filename)))
                        :mime-type (.lookup mime-types filename)
                        :notes (when (.existsSync fs (str dir "/notes.md"))
                                 (.readFileSync fs (str dir "/notes.md") "utf8"))
                        :origin (str dir "/" filename)})))
      (p/then (fn [{:keys [filename] :as conf}]
                (p/alet [metadata (p/await (music-metadata.parseFile (str dir "/" filename)
                                                                     #js {:duration true
                                                                          :skipCovers true}))]
                  (merge {:duration (seconds->interval (.. metadata -format -duration))}
                         conf))))))

(defn load-episodes! [source]
  (p/then (get-directories source)
         (fn [directories]
           (p/then (p/all (map #(load-episode! % "/config.edn") directories))
                  (fn [episodes]
                    (episodes-tx
                      (reverse (sort-by #(js/Date. (get % :published-at))
                                        episodes))))))))

(defn load-site! [{:keys [config]}]
  (p/then (p/all [(read-edn! config)
                  (get-files! "site")
                  (load-episodes! "site/episodes")])
          (fn [[config files episodes]]
            (p/alet [manifest (p/await (generate-manifest config))
                     image (get config :image "cover.jpg")
                     state (merge config {:cover? (and image (.existsSync fs (str "site/" image)))
                                          :manifest manifest
                                          :episodes episodes})]
              {:state state :files files :manifest manifest}))))

(def image-optimization-plugins (clj->js [(imagemin-mozjpeg #js {:quality 80})
                                          (imagemin-optipng)]) )

(defn optimize-image! [file, output-dir]
  (imagemin #js [file]
            (clj->js {:destination output-dir
                      :plugins image-optimization-plugins})))

(defn optimize-image-buffer! [buffer]
  (imagemin.buffer buffer image-optimization-plugins))

(defn optimizable? [file-path]
  (contains? #{".jpg" ".png"} (.extname path file-path)))

(defn write-files! [files]
  (p/all (map (fn [{:keys [content dest operation src]}]
                (condp = operation
                  :copy (if (optimizable? src)
                          (optimize-image! src (.dirname path dest))
                          (copy! src dest))
                  :write (if (optimizable? dest)
                           (p/then (optimize-image-buffer! content)
                                   #(write-file! dest %))
                           (write-file! dest content))))
              files)))

(defn promise-serial [fns]
  (reduce (fn [prm f]
            (p/then prm (fn [result]
                          (p/then (f) #(conj result %)))))
          (p/promise [])
          fns))

(defn write-site! [{:keys [state manifest files]}
                   {:keys [output-dir] :or {output-dir "./www"}}]
  (let [site-files (concat
                     (map (fn [f]
                            {:operation :copy
                             :src f
                             :dest (.join path output-dir (.basename path f))})
                          files)
                     (map (fn [resource]
                            {:operation :write
                             :content (get resource :contents)
                             :dest (.join path output-dir (get resource :name))})
                          (get manifest :resources))
                     [{:operation :write
                        :content (str "<!DOCTYPE html>" (posts state))
                        :dest (.join path output-dir "index.html")}
                      {:operation :write
                        :content (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                      (rss-feed state))
                        :dest (.join path output-dir "rss/podcast.rss")}]
                     (reduce
                       (fn [m {:keys [cover? dir filename image origin title] :as episode}]
                         (let [episode-output-dir (.join path output-dir "episodes" (str/uslug title))]
                           (concat m
                                   [{:operation :write
                                     :content (str "<!DOCTYPE html>" (post (merge state {:episodes [episode]})))
                                     :dest (.join path episode-output-dir "index.html")}
                                    {:operation :copy
                                     :src origin
                                     :dest (.join path episode-output-dir filename)}]
                                   (when cover?
                                     [{:operation :copy
                                      :src (.join path dir image)
                                      :dest (.join path episode-output-dir image)}]))))
                       []
                       (get state :episodes)))]
    (p/then
      (p/then (rmdir! output-dir)
             (fn []
               (promise-serial
                 (map (fn [dir]
                        #(mkdir! dir))
                      (apply sorted-set
                             (reduce
                               (fn [m {:keys [dest]}]
                                 (clojure.set/union m (get-paths (.dirname path dest))))
                               #{}
                               site-files))))))
      #(write-files! site-files))))

(defn build! [options]
  (p/then (load-site! options)
          #(write-site! % options)))

(def cli-options
  [["-c" "--config PATH" "Configuration path"
    :default "./config.edn"]
   ["-h" "--help"]])

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (and (= 1 (count arguments))
           (#{"build"} (first arguments)))
      {:action (first arguments) :options options})))

(defn exit [status msg]
  (println msg)
  (.exit js/process status))

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "build" (build! options)))))

(set! *main-cli-fn* -main)
