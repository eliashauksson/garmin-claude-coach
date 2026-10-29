(ns user
  (:require [coach.garmin-mcp :as gm]
            [coach.mcp :as mcp]
            [coach.source :as source]))

(defonce src (atom nil))

(defn start []
  (reset! src (gm/start!))
  :started)

(defn stop []
  (when-let [s @src] (gm/stop! s) (reset! src nil))
  :stopped)

(comment
  (start)
  (map #(get % "name") (mcp/list-tools (:client @src)))   ; only the read-only allowlist
  (gm/call (:client @src) "count_activities")
  (def acts (source/fetch-activities @src "2026-10-01" "2026-10-29"))
  (source/fetch-activity @src (:id (first acts)))
  (source/fetch-wellness @src "2026-10-28" "2026-10-29")
  (stop))
