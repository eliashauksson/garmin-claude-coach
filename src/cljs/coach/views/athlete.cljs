(ns coach.views.athlete
  "Athlete card on the Plan page: a summary of athlete.md, creating or
  editing it as a chat with Claude or by uploading a file, and delete /
  restore with backups."
  (:require [clojure.string :as str]
            [coach.ui :as ui]))

(def url "/api/athlete")

(defn- when-text [iso]
  (let [d (js/Date. iso)]
    (str (.getDate d) " " (ui/months (.getMonth d)) " "
         (.padStart (str (.getHours d)) 2 "0") ":" (.padStart (str (.getMinutes d)) 2 "0"))))

(def action-label {"edit" "before an edit" "upload" "before an upload" "delete" "deleted" "restore" "before a restore"})

(defn- days-until [iso]
  (when (re-matches #"\d{4}-\d{2}-\d{2}" (str iso))
    (js/Math.round (/ (- (ui/parse-date iso) (ui/parse-date (ui/iso-today))) 86400000))))

(defn- overview
  "Race countdown as the hero figure, the key numbers as stat tiles, the focus line."
  [{:keys [race race_date race_detail athlete stats focus]}]
  (let [days (days-until race_date)]
    [:div.athlete-overview
     [:div.ao-top
      [:div.ao-race
       [:div.ao-eyebrow (str/join " · " (remove str/blank? [race (ui/date-long race_date)]))]
       (cond
         (and days (pos? days)) [:div.big.md days [:small (if (= 1 days) "day to go" "days to go")]]
         (= 0 days)             [:div.big.md "Race day"])
       (when-not (str/blank? race_detail) [:div.sub race_detail])]
      (when (seq stats)
        [:div.ao-stats
         (for [{:keys [label value]} stats]
           [:div.ao-stat {:replicant/key label}
            [:div.stat-label label]
            [:div.stat-value value]])])]
     (when (or (not (str/blank? focus)) (not (str/blank? athlete)))
       [:div.ao-foot
        (when-not (str/blank? focus) [:span.ao-focus [:b "Focus"] focus])
        (when-not (str/blank? athlete) [:span.sub athlete])])]))

(defn- summary-block [{:keys [exists summary summarising summary_error]} busy?]
  (cond
    (not exists) [:div.empty "No athlete information yet; the coach can't plan without it. Add it in a chat with Claude, or upload a file you already have (Markdown or plain text)."]
    (map? summary) (overview summary)
    (or summarising busy?) [:div.athlete-chat [:div.typing [:span.spinner] "Claude is writing a summary…"]]
    :else [:div.plan-footer
           [:span.sub (if summary_error (str "The summary failed: " summary_error) "No summary yet.")]
           [:button.pill {:on {:click [[:athlete-summary]]}} "Try again"]]))

(defn- chat-block [{:keys [messages input busy? proposal error cost]}]
  [:div.athlete-chat
   (for [[i {:keys [role text]}] (map-indexed vector messages)]
     [:div.msg {:replicant/key i :class role} text])
   (when busy? [:div.msg.assistant.typing [:span.spinner] "Claude is thinking…"])
   (when error [:div.error error])
   (when proposal
     [:div.athlete-proposal
      [:b "Proposed changes"]
      (for [[i {:keys [old_text new_text]}] (map-indexed vector (:edits proposal))]
        [:div.change {:replicant/key i}
         (when (seq old_text) [:del old_text])
         [:ins (if (and (empty? old_text) (> (count new_text) 600))
                 (str (subs new_text 0 600) "…")
                 new_text)]])
      [:div.plan-footer
       [:button.btn-ink {:on {:click [[:api "PUT" url {:content (:content proposal) :summary (:summary proposal)}]
                                      [:set-ui :athlete nil]]}}
        "Apply changes"]
       [:span.sub "Nothing changes until you apply. The current version is backed up."]]])
   [:div.plan-footer
    [:input.note {:type "text" :placeholder "e.g. My LTHR is now 176 bpm"
                  :value (or input "") :disabled busy?
                  :on {:input [[:set-in-ui :athlete :chat :input :event/target-value]]
                       :keydown [[:athlete-chat-key]]}}]
    [:button.btn-ink {:disabled (or busy? (str/blank? input)) :on {:click [[:athlete-chat-send]]}} "Send"]
    [:button.pill {:on {:click [[:set-ui :athlete nil]]}} "Close"]]
   (when cost [:div.sub (str "This chat so far: about CHF " (.toFixed cost 3))])])

(defn- backups-block [{:keys [backups]}]
  (when (seq backups)
    [:details.athlete-backups
     [:summary (str "Backups (" (count backups) ")")]
     (for [{:keys [id at action]} backups]
       [:div.plan-footer {:replicant/key id}
        [:span (str (when-text at) " · " (action-label action action))]
        [:button.pill {:on {:click [[:api "POST" (str url "/restore") {:id id}]]}} "Restore"]])
     [:div.sub "Restoring backs up the current version first. The newest 3 backups are kept."]]))

(defn- upload-button []
  ;; a label around a hidden file input: clicking it opens the file picker
  [:label.pill.upload
   "Upload file"
   [:input.visually-hidden {:type "file" :accept ".md,.markdown,.txt,text/markdown,text/plain"
                            :on {:change [[:athlete-upload]]}}]])

(defn- upload-block
  "Preview of the picked file, then Use / Cancel. Nothing is saved before that."
  [{:keys [name content error]} exists?]
  (if error
    [:div.plan-footer [:span.error error] [:button.pill {:on {:click [[:set-in-ui :athlete :upload nil]]}} "OK"]]
    [:div.athlete-proposal
     [:b (str "Upload " name)]
     [:div.change [:ins (if (> (count content) 600) (str (subs content 0 600) "…") content)]]
     [:div.sub (str (ui/thousands (count (str/split-lines content))) " lines · "
                    (ui/thousands (count content)) " characters. "
                    (if exists?
                      "This replaces the current athlete information; the current version is kept as a backup. "
                      "")
                    "Claude then writes the summary. To reshape the file, use Edit with Claude afterwards.")]
     [:div.plan-footer
      [:button.btn-ink {:on {:click [[:api "PUT" url {:content content :source "upload"}] [:set-ui :athlete nil]]}}
       (if exists? "Replace with this file" "Use this file")]
      [:button.pill {:on {:click [[:set-in-ui :athlete :upload nil]]}} "Cancel"]]]))

(defn card [athlete ui-state]
  (let [{:keys [chat confirm-delete? summarising? upload]} ui-state
        open? (some? chat)]
    (ui/card {}
             (ui/label-row {:icon :heart :accent "--blue" :tint "--tint-blue" :soft? true :label "Athlete"
                            :right (when-not open?
                                     [:span.plan-actions
                                      [:button.btn-ink {:on {:click [[:set-in-ui :athlete :chat {:messages []}]]}}
                                       (if (:exists athlete) "Edit with Claude" "Add with Claude")]
                                      (upload-button)
                                      (when (:exists athlete)
                                        [:button.pill {:on {:click [[:set-in-ui :athlete :confirm-delete? true]]}}
                                         "Delete"])])})
             (summary-block athlete summarising?)
             (when upload (upload-block upload (:exists athlete)))
             (when confirm-delete?
               [:div.banner.critical
                [:span.icon "!"]
                [:div
                 [:div [:b "Delete the athlete information?"]]
                 [:div.sub "The coach can't plan until you add it again. A backup is kept, so you can restore it."]
                 [:div.plan-footer
                  [:button.btn-ink {:on {:click [[:api "DELETE" url nil] [:set-ui :athlete nil]]}} "Yes, delete"]
                  [:button.pill {:on {:click [[:set-in-ui :athlete :confirm-delete? false]]}} "Cancel"]]]])
             (when open? (chat-block chat))
             (backups-block athlete))))
