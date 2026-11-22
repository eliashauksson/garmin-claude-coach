(ns coach.views.more
  "The More tab: a list of the pages that aren't daily ones, each with a line
  saying what it holds."
  (:require [coach.i18n :refer [t]]
            [coach.ui :as ui]
            [coach.views.settings :as settings]))

(defn- row [{:keys [href icon accent tint title sub]}]
  [:a.more-row {:replicant/key href :href href}
   (ui/icon-chip icon accent tint {:soft? true})
   [:span.more-text [:b title] [:span.sub sub]]
   [:span.more-chevron {:aria-hidden "true"} "›"]])

(defn view [{:keys [sync instance]}]
  [:div {:style {:display "contents"}}
   [:div.page-head [:div.title [:h1 (t "More")]]]
   (when instance
     (ui/card {} [:div (t "This is {0}'s dashboard." instance)]))
   [:div.more-list
    (row {:href "#/athlete" :icon :heart :accent "--blue" :tint "--tint-blue" :title (t "Athlete")
          :sub (t "Your goal race, availability and history for the coach")})
    (row {:href "#/peaks" :icon :mountain :accent "--green" :tint "--tint-green" :title (t "Peaks")
          :sub (t "The summits you have reached")})
    (row {:href "#/metrics" :icon :nav-metrics :accent "--purple" :tint "--tint-purple"
          :title (t "Metrics explained") :sub (t "What each number means and where it comes from")})
    (row {:href "#/settings" :icon :nav-settings :accent "--text-2" :tint "--surface-2" :title (t "Settings")
          :sub (t "Language, appearance, Garmin, Coros and backups")})
    (when (settings/admin? sync)
      (row {:href "#/settings?tab=claude" :icon :bolt :accent "--amber" :tint "--tint-amber"
            :title (t "Claude & API costs") :sub (t "Models and delivery per task, and what the requests cost")}))]])
