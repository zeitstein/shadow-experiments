(ns shadow.experiments.grove.protocols)


;; not using record since they shouldn't act as maps
;; also does a bunch of other stuff I don't want
(deftype Ident [entity-type id ^:mutable _hash]
  ILookup
  (-lookup [this key]
    (case key
      :entity-type entity-type
      :id id
      nil))

  IHash
  (-hash [this]
    (if (some? _hash)
      _hash
      (let [x (bit-or 123 (hash id) (hash id))]
          (set! _hash x)
          x)))
  IEquiv
  (-equiv [this ^Ident other]
    (and (instance? Ident other)
         (keyword-identical? entity-type (.-entity-type other))
         (= id (.-id other)))))

(defprotocol IWork
  (work! [this]))

(defprotocol IHandleEvents
  ;; e and origin can be considered optional and will be ignored by most actual handlers
  (handle-event! [this ev-map e origin]))

(defprotocol IScheduleUpdates
  (schedule-update! [this target])
  (unschedule! [this target])
  (run-now! [this action])

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-asap! [this action])
  (run-whenever! [this action]))

(defprotocol IHook
  ;; useful to keep in mind that it is the component calling these methods
  (hook-init! [this]
    "called on component mount")
  (hook-ready? [this]
    "called once on mount. used for suspense/async. when false the component will
     stop and wait until the hook signals ready. then it will continue mounting
     and call the remaining hooks and render.")
  (hook-value [this]
    "return the value of the hook")
  (hook-deps-update! [this val]
    "called when the deps of the hook change.
     example: `(bind foo (+ bar))`, this method is called when `bar` changes.

     if true-ish value returned, hooks depending on this hook will update.
     
     `val` corresponds to the result of evaluating the body of the hook
     (with updated deps). e.g. result of `(+ bar)` in example above.
      If bind body returns a hook, val will be that hook (a custom type).")
  (hook-update! [this]
    "called after the hook's value becomes invalidated.
     (e.g. with `comp/hook-invalidate!`)
     
     if true-ish value returned, hooks depending on this hook will update.

     hook-invalidate! marks the hook as dirty and will add the component to the work set.
     then the work set bubbles up to the root and starts to work there,
     working off all pending work and calling hook-update! for all 'dirty' hooks.
     if that returns true it'll make all hooks dirty that depend on the hook-value,
     eventually reaching render if anything in render was dirty, then proceeding
     down the tree.")
  (hook-destroy! [this]
    "called on component unmount"))

(defprotocol IHookDomEffect
  (hook-did-update! [this did-render?]
    "called after component render"))

(defprotocol IBuildHook
  (hook-build [this component idx]
    "used to create a hook managed by `component`.
     
     example:
     `(bind foo (+ bar 1))` will create a hook named `foo`, whose value
     is initialised to `(+ bar 1)` on component mount.

     specifically, `foo` will be (SimpleVal. (+ bar 1)).
     (this is the default implementation of this protocol,
     found in grove.components.)"))

;; just here so that working on components file doesn't cause hot-reload issues
;; with already constructed components
(deftype ComponentConfig
  [component-name
   hooks
   opts
   check-args-fn
   render-deps
   render-fn
   events])

(defprotocol IQueryEngine
  ;; each engine may have different requirements regarding interop with the components
  ;; websocket engine can only do async queries
  ;; local engine can do sync queries but might have async results
  ;; instead of trying to write a generic one they should be able to specialize
  (query-hook-build [this env component idx ident query config])

  ;; hooks may use these but they may also interface with the engine directly
  (query-init [this key query config callback])
  (query-destroy [this key])

  ;; FIXME: one shot query that can't be updated later?
  ;; can be done by helper method over init/destroy but engine
  ;; would still do a bunch of needless work
  ;; only had one case where this might have been useful, maybe it isn't worth adding?
  ;; (query-once [this query config callback])

  ;; returns a promise, tx might need to go async
  (transact! [this tx origin]))

