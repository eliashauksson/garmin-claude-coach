(ns coach.views.athlete
  "Athlete card on the Plan page: a summary of athlete.md, creating or
  editing it as a chat with Claude, in a plain text editor or by uploading
  a file, and delete / restore with backups."
  (:require [clojure.string :as str]
            [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

(def url "/api/athlete")

(defn- when-text [iso]
  (let [d (js/Date. iso)]
    (str (ui/day-month d) " "
         (.padStart (str (.getHours d)) 2 "0") ":" (.padStart (str (.getMinutes d)) 2 "0"))))

(def action-label {"edit" (tk "before an edit") "upload" (tk "before an upload") "delete" (tk "deleted")
                   "restore" (tk "before a restore")})

(defn- days-until [iso]
  (when (re-matches #"\d{4}-\d{2}-\d{2}" (str iso))
    (js/Math.round (/ (- (ui/parse-date iso) (ui/parse-date (ui/iso-today))) 86400000))))

(defn- overview
  "Race countdown as the hero figure, the key numbers as stat tiles, the focus line.
  The summary's own texts are written by Claude (in the language set when it was made)."
  [{:keys [race race_date race_detail athlete stats focus]}]
  (let [days (days-until race_date)]
    [:div.athlete-overview
     [:div.ao-top
      [:div.ao-race
       [:div.ao-eyebrow (str/join " · " (remove str/blank? [race (ui/date-long race_date)]))]
       (cond
         (and days (pos? days)) [:div.big.md days [:small (if (= 1 days) (t "day to go") (t "days to go"))]]
         (= 0 days)             [:div.big.md (t "Race day")])
       (when-not (str/blank? race_detail) [:div.sub race_detail])]
      (when (seq stats)
        [:div.ao-stats
         (for [{:keys [label value]} stats]
           [:div.ao-stat {:replicant/key label}
            [:div.stat-label label]
            [:div.stat-value value]])])]
     (when (or (not (str/blank? focus)) (not (str/blank? athlete)))
       [:div.ao-foot
        (when-not (str/blank? focus) [:span.ao-focus [:b (t "Focus")] focus])
        (when-not (str/blank? athlete) [:span.sub athlete])])]))

(defn- summary-block [{:keys [exists summary summarising summary_error]} busy?]
  (cond
    (not exists) [:div.empty (t "No athlete information yet; the coach can't plan without it. Add it in a chat with Claude, or upload a file you already have (Markdown or plain text).")]
    (map? summary) (overview summary)
    (or summarising busy?) [:div.athlete-chat [:div.typing [:span.spinner] (t "Claude is writing a summary…")]]
    :else [:div.plan-footer
           [:span.sub (if summary_error (t "The summary failed: {0}" summary_error) (t "No summary yet."))]
           [:button.pill {:on {:click [[:athlete-summary]]}} (t "Try again")]]))

(defn- chat-block [{:keys [messages input busy? proposal error cost]}]
  [:div.athlete-chat
   (for [[i {:keys [role text]}] (map-indexed vector messages)]
     [:div.msg {:replicant/key i :class role} text])
   (when busy? [:div.msg.assistant.typing [:span.spinner] (t "Claude is thinking…")])
   (when error [:div.error error])
   (when proposal
     [:div.athlete-proposal
      [:b (t "Proposed changes")]
      (for [[i {:keys [old_text new_text]}] (map-indexed vector (:edits proposal))]
        [:div.change {:replicant/key i}
         (when (seq old_text) [:del old_text])
         [:ins (if (and (empty? old_text) (> (count new_text) 600))
                 (str (subs new_text 0 600) "…")
                 new_text)]])
      [:div.plan-footer
       [:button.btn-ink {:on {:click [[:api "PUT" url {:content (:content proposal) :summary (:summary proposal)}]
                                      [:set-ui :athlete nil]]}}
        (t "Apply changes")]
       [:span.sub (t "Nothing changes until you apply. The current version is backed up.")]]])
   [:div.plan-footer
    [:input.note {:type "text" :placeholder (t "e.g. My LTHR is now 176 bpm")
                  :value (or input "") :disabled busy?
                  :on {:input [[:set-in-ui :athlete :chat :input :event/target-value]]
                       :keydown [[:athlete-chat-key]]}}]
    [:button.btn-ink {:disabled (or busy? (str/blank? input)) :on {:click [[:athlete-chat-send]]}} (t "Send")]
    [:button.pill {:on {:click [[:set-ui :athlete nil]]}} (t "Close")]]
   (when cost [:div.sub (t "This chat so far: about CHF {0}" (.toFixed cost 3))])])

(defn- backups-block [{:keys [backups]}]
  (when (seq backups)
    [:details.athlete-backups
     [:summary (t "Backups ({0})" (count backups))]
     (for [{:keys [id at action]} backups]
       [:div.plan-footer {:replicant/key id}
        [:span (str (when-text at) " · " (t (action-label action action)))]
        [:button.pill {:on {:click [[:api "POST" (str url "/restore") {:id id}]]}} (t "Restore")]])
     [:div.sub (t "Restoring backs up the current version first. The newest 3 backups are kept.")]]))

(defn- upload-button []
  ;; a label around a hidden file input: clicking it opens the file picker
  [:label.pill.upload
   (t "Upload file")
   [:input.visually-hidden {:type "file" :accept ".md,.markdown,.txt,text/markdown,text/plain"
                            :on {:change [[:athlete-upload]]}}]])

(defn- upload-block
  "Preview of the picked file, then Use / Cancel. Nothing is saved before that."
  [{:keys [name content error]} exists?]
  (if error
    [:div.plan-footer [:span.error error] [:button.pill {:on {:click [[:set-in-ui :athlete :upload nil]]}} (t "OK")]]
    [:div.athlete-proposal
     [:b (t "Upload {0}" name)]
     [:div.change [:ins (if (> (count content) 600) (str (subs content 0 600) "…") content)]]
     [:div.sub (str (t "{0} lines · {1} characters." (ui/thousands (count (str/split-lines content)))
                       (ui/thousands (count content)))
                    " "
                    (when exists?
                      (str (t "This replaces the current athlete information; the current version is kept as a backup.")
                           " "))
                    (t "Claude then writes the summary. To reshape the file, use Edit with Claude afterwards."))]
     [:div.plan-footer
      [:button.btn-ink {:on {:click [[:api "PUT" url {:content content :source "upload"}] [:set-ui :athlete nil]]}}
       (if exists? (t "Replace with this file") (t "Use this file"))]
      [:button.pill {:on {:click [[:set-in-ui :athlete :upload nil]]}} (t "Cancel")]]]))

(defn- editor-block
  "The whole of athlete.md in a text area. Nothing is saved until Save."
  [{:keys [text original]} exists?]
  (let [changed? (not= (str/trim (or text "")) (str/trim (or original "")))]
    [:div.athlete-editor
     [:textarea.athlete-text {:value (or text "") :spellcheck false
                              :rows (min 40 (max 12 (inc (count (str/split-lines (or text ""))))))
                              :placeholder (t "Write about yourself as an athlete: your goal race and date, training history, heart rate zones, injuries, how many days a week you can train…")
                              :on {:input [[:set-in-ui :athlete :editor :text :event/target-value]]}}]
     [:div.plan-footer
      [:button.btn-ink {:disabled (or (not changed?) (str/blank? text))
                        :on {:click [[:api "PUT" url {:content text}] [:set-ui :athlete nil]]}}
       (t "Save")]
      [:button.pill {:on {:click [[:set-ui :athlete nil]]}} (t "Cancel")]
      [:span.sub (str (t "{0} lines · {1} characters." (ui/thousands (count (str/split-lines (or text ""))))
                         (ui/thousands (count (or text ""))))
                      " "
                      (when exists? (str (t "The current version is backed up.") " "))
                      (t "Claude then writes the summary."))]]]))

(defn card [athlete ui-state]
  (let [{:keys [chat editor confirm-delete? summarising? upload]} ui-state
        open? (or (some? chat) (some? editor))]
    (ui/card {}
             (ui/label-row {:icon :heart :accent "--blue" :tint "--tint-blue" :soft? true :label (t "Athlete")
                            :right (when-not open?
                                     [:span.plan-actions
                                      [:button.btn-ink {:on {:click [[:set-in-ui :athlete :chat {:messages []}]]}}
                                       (if (:exists athlete) (t "Edit with Claude") (t "Add with Claude"))]
                                      [:button.pill {:on {:click [[:set-in-ui :athlete :editor
                                                                   {:text (:content athlete)
                                                                    :original (:content athlete)}]]}}
                                       (if (:exists athlete) (t "Edit file") (t "Write file"))]
                                      (upload-button)
                                      (when (:exists athlete)
                                        [:button.pill {:on {:click [[:set-in-ui :athlete :confirm-delete? true]]}}
                                         (t "Delete")])])})
             (summary-block athlete summarising?)
             (when upload (upload-block upload (:exists athlete)))
             (when confirm-delete?
               [:div.banner.critical
                [:span.icon "!"]
                [:div
                 [:div [:b (t "Delete the athlete information?")]]
                 [:div.sub (t "The coach can't plan until you add it again. A backup is kept, so you can restore it.")]
                 [:div.plan-footer
                  [:button.btn-ink {:on {:click [[:api "DELETE" url nil] [:set-ui :athlete nil]]}} (t "Yes, delete")]
                  [:button.pill {:on {:click [[:set-in-ui :athlete :confirm-delete? false]]}} (t "Cancel")]]]])
             (when chat (chat-block chat))
             (when editor (editor-block editor (:exists athlete)))
             (backups-block athlete))))
