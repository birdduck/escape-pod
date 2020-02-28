(defn cdata [s]
  (str "<![CDATA[" s "]]>"))

(defn episode->item [{:keys [base-url cover? description duration explicit? filename image length mime-type published-at title] :as config}]
  (let [url (get config :url (str base-url "/episodes/" (uslug title) "/" filename))]
    [:item
     [:title (cdata title)]
     [:description (cdata description)]
    (when cover?
      [:itunes:image {:href (str base-url "/episodes/" (uslug title) "/" image)}])
    ["itunes:explicit" (if (true? explicit?) "Yes" "No")]
    ["itunes:duration" duration]
    [:pubDate published-at]
    [:enclosure {:url url 
                 :length length
                 :type mime-type}]
    [:guid url]]))

; NOTE we support arbitrary depth for categories, but iTunes accepts only two
(defn category->el [category]
  (if (vector? category)
    ["itunes:category" {:text (first category)}
     (map category->el (rest category))]
    (category->el [category])))

[:rss {:version "2.0"
         "xmlns:googleplay" "http://www.google.com/schemas/play-podcasts/1.0"
         "xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"
         "xmlns:webfeeds" "http://webfeeds.org/rss/1.0"}
 [:channel
  [:title (cdata title)]
  [:link url]
  [:description (cdata description)]
  [:language language]
  ["itunes:author" author]
  ["itunes:owner"
   ["itunes:email" email]]
  ["itunes:subtitle" (cdata description)]
  ["itunes:explicit" (if (true? explicit?) "Yes" "No")]
  (when cover?
    ["itunes:image" {:href (str url "/" image)}])
  (when cover?
    ["webfeeds:icon" (str url "/" image)])
  (map category->el categories)
  (map #(episode->item (merge % {:base-url url})) 
       episodes)]]
