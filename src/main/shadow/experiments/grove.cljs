(ns shadow.experiments.grove
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require-macros [shadow.experiments.grove])
  (:require
    [goog.async.nextTick]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.fragments] ;; util macro references this
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.collections :as sc]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.ui.util :as util]
    [shadow.experiments.grove.ui.suspense :as suspense]
    [shadow.experiments.grove.ui.atoms :as atoms]
    [shadow.experiments.grove.ui.portal :as portal]
    [shadow.experiments.arborist.attributes :as a]))

(set! *warn-on-infer* false)

;; these are private - should not be accessed from the outside
(defonce active-apps-ref (atom {}))

(defn run-now! [env callback]
  (gp/run-now! (::comp/scheduler env) callback))

(defn dispatch-up! [{::comp/keys [^not-native parent] :as env} ev-map]
  {:pre [(map? env)
         (map? ev-map)
         (qualified-keyword? (:e ev-map))]}
  ;; FIXME: should schedule properly when it isn't in event handler already
  (gp/handle-event! parent ev-map nil env))

(deftype QueryInit [ident query config]
  gp/IBuildHook
  (hook-build [this component idx]
    ;; support multiple query engines by allowing queries to supply which key to use
    (let [{::rt/keys [runtime-ref] :as env} (comp/get-env component)
          engine-key (:engine config ::gp/query-engine)
          query-engine (get @runtime-ref engine-key)]

      (assert query-engine (str "no query engine in runtime for key " engine-key))
      (gp/query-hook-build query-engine env component idx ident query config))))

(defn query-ident
  "Queries starting from `ident`. Defaults to ident lookup if no `query` provided.
   * `query` - Optional. EQL query.
   * `config` - Optional. Any kind of config that may come up.
   The db keys accessed by the query (including inside `eql/attr`) are 'observed'
   – any changes to them during transactions will cause the query to re-un.
   "
  ;; shortcut for ident lookups that can skip EQL queries
  ([ident]
   {:pre [(vector? ident)]}
   (QueryInit. ident nil {}))

  ;; EQL queries
  ([ident query]
   (query-ident ident query {}))
  ([ident query config]
   {:pre [;; (db/ident? ident) FIXME: can't access db namespace in main, move to protocols?
          (vector? ident)
          (keyword? (first ident))
          (vector? query)
          (map? config)]}
   (QueryInit. ident query config)))

(defn query-root
  "Queries from the root of the db.
   * `query` - EQL query.
   * `config` - Optional. Any kind of config that may come up.
   The db keys accessed by the query (including inside `eql/attr`) are 'observed'
   – any changes to them during transactions will cause the query to re-un."
  ([query]
   (query-root query {}))
  ([query config]
   {:pre [(vector? query)
          (map? config)]}
   (QueryInit. nil query config)))

(defn tx*
  [runtime-ref tx origin]
  (assert (rt/ref? runtime-ref) "expected runtime ref?")

  (let [{::gp/keys [query-engine]} @runtime-ref]
    (assert query-engine "missing query-engine in env")
    (assert (map? tx) "expected transaction to be a map")

    (gp/transact! query-engine tx origin)))

(defn tx [{::rt/keys [runtime-ref] :as env} ev-map e origin]
  (tx* runtime-ref ev-map origin))

(defn run-tx
  "Used inside a component event handler. Runs transaction `tx`, e.g.
   `{:e ::some-event :data ...}`. `env` is the component environment map
   available in event handlers."
  [{::rt/keys [runtime-ref] :as env} tx]
  (tx* runtime-ref tx env))

(defn run-tx-with-return
  [{::rt/keys [runtime-ref] :as env} tx]
  (tx* runtime-ref tx env))

(defn run-tx!
  "Used outside of a component event handler. Run transaction `tx`, e.g.
   `{:e ::some-event :data ...}`."
  [runtime-ref tx]
  (tx* runtime-ref tx nil))

(deftype RootScheduler [^:mutable update-pending? work-set]
  gp/IScheduleUpdates
  (schedule-update! [this work-task]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (util/next-tick #(.process-work! this))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-now! [this action]
    (set! update-pending? true)
    (action)
    ;; work must happen immediately since (action) may need the DOM event that triggered it
    ;; any delaying the work here may result in additional paint calls (making things slower overall)
    ;; if things could have been async the work should have been queued as such and not ended up here
    (.process-work! this))

  Object
  (process-work! [this]
    (let [iter (.values work-set)]
      (loop []
        (let [current (.next iter)]
          (when (not ^boolean (.-done current))
            (gp/work! ^not-native (.-value current))

            ;; should time slice later and only continue work
            ;; until a given time budget is consumed
            (recur)))))

    (set! update-pending? false)))

(defn default-error-handler [component ex]
  ;; FIXME: this would be the only place there component-name is accessed
  ;; without this access closure removes it completely in :advanced which is nice
  ;; ok to access in debug builds though
  (if ^boolean js/goog.DEBUG
    (js/console.error (str "An Error occurred in " (.. component -config -component-name) ", it will not be rendered.") component)
    (js/console.error "An Error occurred in Component, it will not be rendered." component))
  (js/console.error ex))

(deftype RootEventTarget [rt-ref]
  gp/IHandleEvents
  (handle-event! [this ev-map e origin]
    (tx* rt-ref ev-map origin)))

(defn- make-root-env
  [rt-ref root-el]

  ;; FIXME: have a shared root scheduler rt-ref
  ;; multiple roots should schedule in some way not indepdendently
  (let [scheduler
        (RootScheduler. false (js/Set.))

        event-target
        (RootEventTarget. rt-ref)

        env-init
        (::rt/env-init @rt-ref)]

    (reduce
      (fn [env init-fn]
        (init-fn env))

      ;; base env, using init-fn to customize
      {::comp/scheduler scheduler
       ::comp/event-target event-target
       ::suspense-keys (atom {})
       ::rt/root-el root-el
       ::rt/runtime-ref rt-ref
       ;; FIXME: get this from rt-ref?
       ::comp/error-handler default-error-handler}

      env-init)))

(defn render
  "Renders the UI root. Call on init and `^:dev/after-load`.
   * `rt-ref` – runtime atom
   * `root-el` – something like `(js/document.getElementById \"app\")`.
   * `root-node` – root element/component (e.g. `defc`)."
  [rt-ref ^js root-el root-node]
  {:pre [(rt/ref? rt-ref)]}
  (if-let [active-root (.-sg$root root-el)]
    (do (when ^boolean js/goog.DEBUG
          (comp/mark-all-dirty!))

        ;; FIXME: somehow verify that env hasn't changed
        ;; env is supposed to be immutable once mounted, but someone may still modify rt-ref
        ;; but since env is constructed on first mount we don't know what might have changed
        ;; this is really only a concern for hot-reload, apps only call this once and never update

        (sa/update! active-root root-node)
        ::updated)

    (let [new-env (make-root-env rt-ref root-el)
          new-root (sa/dom-root root-el new-env)]
      (sa/update! new-root root-node)
      (set! (.-sg$root root-el) new-root)
      (set! (.-sg$env root-el) new-env)
      ::started)))

(defn unmount-root [^js root-el]
  (when-let [root (.-sg$root root-el)]
    (ap/destroy! root true)
    (js-delete root-el "sg$root")
    (js-delete root-el "sg$env")))

(defn watch
  "Hook that watches `the-atom` and updates when the atom's value changes.
   Accepts an optional `path-or-fn` arg that can be used to 'watch' a portion of
   `the-atom`, enabling quick diffs.

   (watch the-atom [:foo])
   (watch the-atom (fn [old new] ...))

   'path' – as in `(get-in @the-atom path)`
   'fn' - similar to above, defines how to access the relevant parts of `the-atom`.
   Takes [old-state new-state] of `the-atom` and returns the actual value stored
   in the hook. Example: `(fn [_ new] (get-in new [:id :name]))`.
   
   **Use strongly discouraged** in favor of the normalized central db."
  ([the-atom]
   (watch the-atom (fn [old new] new)))
  ([the-atom path-or-fn]
   (if (vector? path-or-fn)
     (atoms/AtomWatch. the-atom (fn [old new] (get-in new path-or-fn)) nil nil nil)
     (atoms/AtomWatch. the-atom path-or-fn nil nil nil))))

(defn env-watch
  "Similar to [[watch]], but for atoms inside component env."
  ([key-to-atom]
   (env-watch key-to-atom [] nil))
  ([key-to-atom path]
   (env-watch key-to-atom path nil))
  ([key-to-atom path default]
   {:pre [(keyword? key-to-atom)
          (vector? path)]}
   (atoms/EnvWatch. key-to-atom path default nil nil nil nil)))

(defn suspense
  "See [docs](https://github.com/thheller/shadow-experiments/blob/master/doc/async.md)."
  [opts vnode]
  (suspense/SuspenseInit. opts vnode))

(defn simple-seq 
  "Creates a collection of DOM elements by applying `render-fn` to each item 
   in `coll`. `render-fn` can be a function or component. Best used with colls
   that don't re-order or change much. Otherwise, use [[keyed-seq]].

   ---
   Example

   ```clojure
   (sg/simple-seq
    (range 5)
    (fn [num]
      (<< [:div \"inline-item: \" num])))
   ```                      
   "
  [coll render-fn]
  (sc/simple-seq coll render-fn))

(defn keyed-seq
  "Creates a keyed collection of DOM elements by applying `render-fn` to each
   item in `coll`. Preferred over [[simple-seq]] when collection changes often.
   * `key-fn` is used to extract a unique key from items in `coll`.
   * `render-fn` can be a function or component.
   
   ---
   Examples:

   ```clojure
   ;; ident used as key
   (keyed-seq [[::ident 1] ...] identity component)

   (keyed-seq [{:id 1 :data ...} ...] :id
     (fn [item] (<< [:div.id (:data item) ...])))
   ```"
  [coll key-fn render-fn]
  (sc/keyed-seq coll key-fn render-fn))

(deftype TrackChange [^:mutable val ^:mutable trigger-fn ^:mutable result env component idx]
  gp/IBuildHook
  (hook-build [this c i]
    (TrackChange. val trigger-fn nil (comp/get-env c) c i))

  gp/IHook
  (hook-init! [this]
    (set! result (trigger-fn env nil val)))

  (hook-ready? [this] true)
  (hook-value [this] result)
  (hook-update! [this] false)

  (hook-deps-update! [this ^TrackChange new-track]
    (assert (instance? TrackChange new-track))

    (let [next-val (.-val new-track)
          prev-result result]

      (set! trigger-fn (.-trigger-fn new-track))
      (set! result (trigger-fn env val next-val))
      (set! val next-val)

      (not= result prev-result)))

  (hook-destroy! [this]
    ))

(defn track-change [val trigger-fn]
  (TrackChange. val trigger-fn nil nil nil nil))

;; using volatile so nobody gets any ideas about add-watch
;; pretty sure that would cause havoc on the entire rendering
;; if sometimes does work immediately on set before render can even complete
(defn ref []
  (volatile! nil))

(defn effect
  "Calls `(callback env)` after render when provided `deps` changes.
   (*Note*: this implies it will be called on mount too.)
   `callback` may return a function which will be called if cleanup is required.
   Cleanup is performed on component unmount *and* after each render 
   before callback. (Similar to [React](https://reactjs.org/docs/hooks-effect.html#effects-with-cleanup))"
  [deps callback]
  (comp/EffectHook. deps callback nil true nil nil))

(defn render-effect
  "Calls (callback env) after every render.
   `callback` may return a function which will be called if cleanup is required.
   Cleanup is performed on component unmount *and* after each render 
   before callback. (Similar to [React](https://reactjs.org/docs/hooks-effect.html#effects-with-cleanup))"
  [callback]
  (comp/EffectHook. :render callback nil true nil nil))

(defn mount-effect
  "Calls (callback env) on mount once.
   `callback` may return a function which will be called if cleanup is required.
   Cleanup is performed on component unmount *and* after each render 
   before callback. (Similar to [React](https://reactjs.org/docs/hooks-effect.html#effects-with-cleanup))"
  [callback]
  (comp/EffectHook. :mount callback nil true nil nil))

;; FIXME: does this ever need to take other options?
(defn portal
  ([body]
   (portal/portal js/document.body body))
  ([ref-node body]
   (portal/portal ref-node body)))