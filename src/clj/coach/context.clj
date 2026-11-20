(ns coach.context
  "Which athlete the current thread works for. One app can serve several
  athletes on a server (docs/server.md); each request and background job runs
  with `*athlete*` bound to its athlete's name (nil: the first athlete).
  Unbound, it is the athlete named by $COACH_INSTANCE (one app per athlete).

  `per-athlete-atom` is the state that exists once per athlete (the sync
  state, the open database, the language…). It behaves like an atom
  (deref, swap!, reset!, swap-vals!, compare-and-set!) on the current
  athlete's value.")

(def ^:dynamic *athlete*
  "An athlete's name, nil for the first athlete, or ::env: the instance of
  this process ($COACH_INSTANCE)."
  ::env)

(defonce ^{:doc "The $COACH_INSTANCE athlete (set by coach.paths); nil for the usual install."}
  env-athlete (atom nil))

(defn athlete
  "The current athlete's name; nil for the first athlete."
  []
  (let [a *athlete*]
    (if (identical? a ::env) @env-athlete a)))

(defn with-athlete
  "Calls `f` for athlete `name` (nil: the first athlete)."
  [name f]
  (binding [*athlete* name] (f)))

(defn athlete-fn
  "`f` as a function that runs for the athlete that is current now, for
  executors and threads (a `future` carries the binding by itself)."
  [f]
  (let [a (athlete)]
    (fn [& args] (binding [*athlete* a] (apply f args)))))

(defn prefixing-writer
  "A Writer that starts every line of `w` with \"[name] \" while a named
  athlete is current (the first athlete's lines stay as they are), so one
  log can hold all athletes of a server. Lines are written by whoever
  prints them: this is for the log, where output is line by line anyway."
  ^java.io.Writer [^java.io.Writer w]
  (let [at-start (volatile! true)]
    (proxy [java.io.Writer] []
      (write
        ([x] (let [^String s (cond (string? x) x
                                   (instance? (Class/forName "[C") x) (String. ^chars x)
                                   :else (String/valueOf (char x)))]
               (.write ^java.io.Writer this s 0 (count s))))
        ([x off len]
         (locking w
           (let [^String s (if (string? x) (subs x off (+ off len)) (String. ^chars x (int off) (int len)))]
             (when (pos? (count s))
               (let [lines (re-seq #"[^\n]*\n|[^\n]+" s)
                     name  (athlete)]
                 (doseq [^String l lines]
                   (when (and @at-start name (not= "\n" l)) (.write w (str "[" name "] ")))
                   (.write w l)
                   (vreset! at-start (.endsWith l "\n")))))))))
      (flush [] (locking w (.flush w)))
      (close [] (locking w (.close w))))))

(defn per-athlete-atom
  "An atom with its own value per athlete, each starting as `init`."
  [init]
  (let [m   (atom {})
        cur (fn []
              (let [k (athlete)]
                (or (get @m k)
                    (get (swap! m (fn [mm] (if (contains? mm k) mm (assoc mm k (atom init))))) k))))]
    (reify
      clojure.lang.IDeref
      (deref [_] @(cur))
      clojure.lang.IAtom2
      (swap [_ f] (swap! (cur) f))
      (swap [_ f a] (swap! (cur) f a))
      (swap [_ f a b] (swap! (cur) f a b))
      (swap [_ f a b more] (apply swap! (cur) f a b more))
      (compareAndSet [_ old new] (compare-and-set! (cur) old new))
      (reset [_ v] (reset! (cur) v))
      (swapVals [_ f] (swap-vals! (cur) f))
      (swapVals [_ f a] (swap-vals! (cur) f a))
      (swapVals [_ f a b] (swap-vals! (cur) f a b))
      (swapVals [_ f a b more] (apply swap-vals! (cur) f a b more))
      (resetVals [_ v] (reset-vals! (cur) v)))))
