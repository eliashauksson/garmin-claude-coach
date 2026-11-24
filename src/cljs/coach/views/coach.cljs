(ns coach.views.coach
  "Ask the coach (#/coach): a chat about your own training data. The coach answers from the same data
  it plans with and can't change anything. When you ask for a plan change it offers one: a button
  starts the usual adjustment (Plan page), which you review and accept. The conversation lives in
  [:ui :coach :chat] and is gone when you leave the page's chat with New chat or reload."
  (:require [coach.i18n :refer [t tk]]
            [coach.ui :as ui]))

(def starters
  "Questions to start from; each is a button that asks it."
  [(tk "How is my form?")
   (tk "Why is today's session this one?")
   (tk "Am I on track for race day?")
   (tk "Can I move tomorrow's session?")])

(defn- adjust-offer
  "The coach's suggested plan change, as the athlete's note for an adjustment."
  [{:keys [adjust adjusting?]}]
  (when adjust
    [:div.athlete-proposal {:replicant/key "adjust"}
     [:b (t "A change to your plan")]
     [:div.sub adjust]
     [:div.plan-footer
      [:button.btn-ink {:disabled adjusting? :on {:click [[:coach-adjust]]}}
       (if adjusting? (t "Asking…") (t "Propose this change"))]
      [:span.sub (t "You review the proposal on the Plan page. Nothing changes until you accept.")]]]))

(defn view [{ui-state :ui}]
  (let [{:keys [messages cost] :as chat} (get-in ui-state [:coach :chat])]
    [:div {:style {:display "contents"}}
     [:div.page-head [:div.title [:h1 (t "Ask the coach")]]]
     (ui/card {}
              [:div.athlete-chat
               (when (empty? messages)
                 [:div {:replicant/key "intro"}
                  [:p (t "Ask about your training, your form or this week's plan. The coach answers from your own data and the training principles.")]
                  [:div.pills
                   (for [q starters]
                     [:button.pill {:replicant/key q :on {:click [[:coach-ask (t q)]]}} (t q)])]])
               (ui/chat-thread chat)
               (adjust-offer chat)
               [:div.plan-footer
                (ui/chat-input chat {:ui-path [:coach :chat] :placeholder (t "Ask the coach…")
                                     :send [:coach-chat-send] :on-key [:coach-chat-key]})
                (when (seq messages)
                  [:button.pill {:on {:click [[:set-in-ui :coach :chat nil]]}} (t "New chat")])]
               (when cost [:div.sub (t "This chat so far: about CHF {0}" (.toFixed cost 3))])
               [:div.sub (t "The coach can't change anything itself. A plan change only comes from a proposal you ask for.")]])]))
