(defn twitter-card [{:keys [base-url cover? filename image site title description mime-type]}]
  [[:meta {:name "twitter:card" :content "summary"}]
   [:meta {:name "twitter:site" :content site}]
   [:meta {:name "twitter:creator" :content site}]
   [:meta {:property "og:url" :content (str base-url "/episodes/" (uslug title))}]
   [:meta {:property "og:title" :content title}]
   [:meta {:property "og:description" :content description}]
   [:meta {:property "og:audio" :content (str base-url "/episodes/" (uslug title) "/" filename)}]
   [:meta {:property "og:audio:type" :content mime-type}]
   (when cover?
     [:meta {:property "og:image"
             :content (str base-url "/episodes/" (uslug title) "/" image)}])])

(defn episode->article [{:keys [base-url cover? description explicit? filename image published-at mime-type notes number tags title url] :as config}]
  (let [url (get config :url (str base-url "/episodes/" (uslug title) "/" filename))]
    [:article.center-ns.mw6-ns.hidden.mv4.mh3.ba.b--near-white
     (when cover?
       [:img.db.mv0.w-100 {:src (str base-url "/episodes/" (uslug title) "/" image) :alt (str "Episode #" number " cover")}])
     [:section.ph2.pv3.ma0.bg-near-white
      [:h2.f5.ma0
       [:a.link.dim.black {:href (str base-url "/episodes/" (uslug title))} title]]
      [:h3.f6.ma0.pt2.mid-gray (str "Episode #" number " published " (format-date published-at "LLL z"))]]
     [:section
      [:p.f6.f5-ns.lh-copy.ph2.pv3.ma0.bg-white
       (-> description linkify smartypants emojify)]
      (when notes
        [:section.f8.f7-ns.lh-copy.ph2.pb2.ma0.bg-white
         [:h4.ttu.ma0.mid-gray "Notes"]
         [:div (-> notes md->html emojify)]])]
     [:div.mh2.mb3
      [:audio.w-100 {:controls "controls" :preload "metadata" :style "z-index: 0;"}
       [:source {:src url :type mime-type}]]]]))

[:html {:lang language
          :prefix "og: http://ogp.me/ns#"}
   (apply conj
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:http-equiv "x-ua-compatible" :content "ie=edge"}]
           [:title (str title " | " (get episode :title))]
           [:meta {:name "description" :content (get episode :description)}]
           [:meta {:name "viewport" :content"width=device-width, initial-scale=1, shrink-to-fit=no"}]
           [:link {:rel "stylesheet"
                   :href "https://unpkg.com/tachyons@4.9.1/css/tachyons.min.css"}]]
          (map identity (get manifest :elements))
          [:style (style)]
          [:link {:rel "canonical"
                  :href (str url "/episodes/" (uslug (:title episode)))}]
          (when (some? (get social :twitter))
            (twitter-card (merge episode
                                 {:base-url url :site (str "@" (get social :twitter))}))))
   [:body.system-sans-serif
    [:section
     [:header.bg-white.fixed.w-100.ph3.pv3 {:style "z-index: 1;"}
      [:h1.f1.f-4-ns.lh-solid.center.tc.mv0
       [:a.link.dim.mid-gray {:href url} title]]
      [:h2.f5.dark-gray.fw2.tc.tracked description]]]
    [:section.pt6
     (map #(episode->article (merge % {:base-url url}))
          episodes)]]]
