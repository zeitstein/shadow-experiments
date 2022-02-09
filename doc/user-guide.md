# Introduction to `shadow-grove`

`shadow-grove` is a web framework written in Clojure/Script from the ground up. It is fair to say that performance is a major goal, but `shadow-grove` retains the developer experience we have come to love: UI as a pure function of normalized data queried by EQL, clear separation between UI and data concerns, etc.

Loosely speaking, there are two major parts of `shadow-grove`: [UI components](#ui-components) and [normalized db with EQL](#db-and-query). You can use the former without the latter, but they combine together into an optimal experience featuring fast surgical updates of the DOM.

This document is meant to provide an overview and surface the most important functionality. For more details, please consult the docstrings. You can read more about the project's goals and design considerations [here](https://github.com/thheller/shadow-experiments/tree/master/doc).

Since `shadow-grove` is mostly built on top of protocols, one can extend/replace some of its defaults (e.g. using Fulcro/DataScript/etc. for data management). That will not be covered here, but we feel is well worth mentioning.

If you'd like to take a look at full app examples:
- Most up-to-date and in-use is the [shadow-cjs-ui](https://github.com/thheller/shadow-cljs/tree/master/src/main/shadow/cljs/ui)
- todomvc examples: [simple](https://github.com/thheller/shadow-experiments/blob/master/src/dev/todomvc/simple.cljs) (just the component framework) and [split](https://github.com/thheller/shadow-experiments/tree/master/src/dev/todomvc/split) (+ db and eql) (**Note**: these are not a 100% up-to-date with the API, but they work.)
<!-- todo perhaps not? -->
- Proof-of-concept [integration with Fulcro](https://github.com/zeitstein/shadow-grove-fulcro-integration)


You can find the simple example we'll be using through this doc in the following repo.
<!-- todo link to example repo -->
You can try it out and follow along.

The basic setup, including namespaces we'll refer to throughout, is as follows:
```clojure
(ns example
  (:require
   [shadow.experiments.grove :as sg :refer (<< defc)]
   [shadow.experiments.grove.runtime :as rt]
   [shadow.experiments.grove.db :as db]
   [shadow.experiments.grove.eql-query :as eql]
   [shadow.experiments.grove.events :as ev]
   [shadow.experiments.grove.local :as local]))

(def schema
  {::dir    {:type        :entity
             :primary-key :dir/id
             :attr        {}
             :joins       {:dir/dirs  [:many ::dir]
                           :dir/files [:many ::file]}}
   ::file   {:type        :entity
             :primary-key :file/id
             :attr        {}}})

(def denormalized-data
  {:dir/id 0 :dir/name "project" :dir/open? true
   :dir/files [{:file/id 0 :file/name "deps.edn"}
               {:file/id 1 :file/name ".nrepl-port" ::hidden? true}]
   :dir/dirs [{:dir/id 1 :dir/name "src/example" :dir/open? true
               :dir/files [{:file/id 2 :file/name "ui.cljs"}
                           {:file/id 3 :file/name "db.cljs"}]}]})

(defonce data-ref ;; db atom
  (-> {} ;; empty db, we'll do a load! on init
      (db/configure schema)
      (atom)))

(defonce rt-ref ;; runtime atom
  (rt/prepare {} data-ref ::runtime-id))

(def root-el
(js/document.getElementById "root"))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

(defn init []
  ;; default query engine
  (local/init! rt-ref)
  ;; inserts denormalized-data into db
  (sg/run-tx! rt-ref {:e ::load!})
  (start))
```


## UI Components

### The Fragment Macro `<<`

Produces DOM nodes which will 'reactively update' when 'variables' inside it change. The macro detects things that can change (`Symbol`s) at compile time and emits optimized functions for creating and updating the DOM, which skip over the static parts of the fragment.

[Examples of templating with `<<`](https://code.thheller.com/demos/shadow-shadow-grove/?id=e0e91cac520338e6889f9224d7bcf7b8)

Note: the macro only analyses `hiccup` and will stop when it encounters anything else. For example, note the second `<<` in example below:

```clojure
(defn fragment [v]
  (<< [:div.card
       [:div.card-title "title"]
       [:div.card-body v]
       (when v
         (<< [:div.card-footer
              [:div.card-actions
               [:button "ok" v]
               [:button "cancel"]]]))]))
```

The macro itself does not create DOM nodes, so passing its results around is cheap.

### Lists of DOM elements

Check out `sg/keyed-seq` and `sg/simple-seq` docstrings.

```clojure
(<< [:div.contains
     (sg/keyed-seq contains identity
       #(if (= (first %) ::dir)
          (ui-dir %)
          (ui-file %)))])
```

### The `defc` component macro

We'll be looking at the `ui-dir` component from our example, step by step.

```clojure
(defc ui-dir [ident]
  (bind {:dir/keys [name open? contains] ::keys [hidden?] :as props}
    (sg/query-ident ident [:dir/name :dir/open? :dir/contains ::hidden?]))

  (bind num-visible
    (expensive-computation contains))

  (render
    (<< [:div.dir
         [:span.name {:on-click {:e ::dir-click! :ident ident}
                      :class (when hidden? "hidden")}
          name]

         [:span.loc (str "(visible loc: " (* num-visible 300) ")")]

         (when (and open? (seq contains))
           (<< [:div.contains
                (sg/keyed-seq contains identity
                  #(if (= (first %) ::dir)
                     (ui-dir %)
                     (ui-file %)))]))]))

  (event ::dir-click! [env {:keys [ident]} e]
    (if (.-ctrlKey e)
      (sg/run-tx env {:e ::toggle-hide! :ident ident})
      (sg/run-tx env {:e ::toggle-open! :ident ident}))))
```

#### Hooks

Used in places where one would use hooks in React, reactive statements in Svelte, etc. They are hooked up to the component lifecycle. (We don't much like the term "hooks", so if you have a better idea - let us know!)

The component macro `defc` takes four types of hooks:
- `(bind <name> <&body>)` sets up a named binding that can be used in later hooks. If the value changes all other hooks using that binding will also be triggered.
- `(hook <&body>)` is the same as `bind` but does not have an output other hooks can use.
- `(render <&body>)` produces the component output, can only occur once.
- `(event <event-kw> <arg-vector> <&body>)` creates an event handler fn.

Note that all of these execute in order so `bind` and `hook` cannot be used after `render`. They may also only use bindings declared before themselves. They may shadow the binding name if desired. `event` can be used after or before `render` as it will trigger when the event fires and not during component rendering.

Unlike React hooks all of these are wired up at compile time and will only trigger if their "inputs" change (e.g. previous `bind` name or component arguments). In the above example:
- a hook named `num-visible` is initialized to `(expensive-computation contains)` on component mount,
- `expensive-computation` will only re-trigger if `contains` changes – and not the rest of `props`,
- and, in this case, only the bits depending on `num-visible` and `contains` will be updated in the DOM.

Just like in React, there are some rules for hooks:
- The `defc` macro already enforces that things are called in the correct order.
- Technically, `sg/query-ident` and `expensive-computation` produce [different types of hooks](https://github.com/thheller/shadow-experiments/blob/master/doc/components.md). A rule is that the type of a hook, once created, must remain consistent. Basically the only thing that should be avoided is conditionals. So no `(bind foo (when bar (sg/query-ident baz)))` which might either be `nil` (a simple value) or a query hook – different types – depending on the value of `bar` (which, remember, can change without the component re-mounting).
<!-- todo difficult to explain without going into details...  -->

<!-- !todo remove? -->
- `env-watch`
- `watch` – bind and watch atoms, can model local state but it is strongly discouraged in favor of using a normalized db as the place for state management. (We believe this approach leads to less code and less complicated component logic easier to reason about)

#### Events

Events (both DOM and transactions) are declared as data:

```clojure
;; event declaration
[:span.name {:on-click {:e ::dir-click! :ident ident}} name]

;; event handler
(event ::dir-click! [env {:keys [ident] :as event-map} event]
  (if (.-ctrlKey e)
    (sg/run-tx env {:e ::toggle-hide! :ident ident})
    (sg/run-tx env {:e ::toggle-open! :ident ident})))
```

If no `(event ...)` handler is registered with the component, events will bubble up the component tree (`::toggle-hide!` in `ui-file`). If no component processes the event, they will by handled by a "root event target". If using [shadow-grove's db and query](#db-and-query), the event map will passed along as a [transaction](#transactions) (`toggle-show-hidden!` in `ui-root`). Otherwise, you can [plug in your own](https://github.com/thheller/shadow-experiments/blob/f875c29bbda34d19ebdedf335b5a3ea40c7a4030/src/main/shadow/experiments/shadow-grove.cljs#L167).

The component environment (`env` above) is a place to store data that should be accessible from components, without using global state. It is constructed at the root and then passed down to every node. You can add things to it on init with:

```clojure
(swap! rt-ref update ::rt/env-init conj #(assoc % ...))
```

<!-- todo -->
- fx – side effects in regular events are controlled via :fx. it is expected to do side effects triggered by events.
- `dispatch-up!`

#### Effects

Effect are mainly meant to be used to make manual modifications to the DOM based on data changes (e.g. help out with transitions/animations). (For data modifications, use [transactions](#transactions)). Each effect takes a callback function, which may return an optional clean-up function. There are: `effect`, `mount-effect`, `render-effect`. Current API is:

```clojure
(defc ...
  (hook (sg/effect ...)))
```


## DB and Query

The normalized db is a flat map, with entity idents as keys. At points of 'contact' with the db (e.g. in event handlers) the db is available as a map one can `update`, `assoc-in`, etc.

A basic EQL engine is available. Instead of composing a big query at UI root, we feel that each component should just query for the data it needs. (Certainly a trade off, e.g. with respect to remote interactions.) Components can get their data in a declarative way without being coupled to where that data is actually coming from.

Nevertheless, it is still very much about your UI being a function of your data.

### DB

A schema is defined to enable normalization helpers.
- `:primary-key` used as ident generating function. You can supply your own in `:ident-gen`.
- `:joins` - specify on which attribute and whether to-`:one` or to-`:many`.

```clojure
(def schema
  {::dir    {:type        :entity
             :primary-key :dir/id
             :attr        {}
             :joins       {:dir/dirs  [:many ::dir]
                           :dir/files [:many ::file]}}
   ::file   {:type        :entity
             :primary-key :file/id
             :attr        {}}})
```

To get a feel for `shadow-grove`'s db architecture, evaluate or `tap`:

```clojure
(-> {}
    (db/configure schema) ;; associates schema to the db as metadata
    (db/transacted)
    (db/add ::dir
      ;; data which is not normalized
      {:dir/id 0 :dir/name "project" :dir/open? true
       :dir/files [{:file/id 0 :file/name "deps.edn"}
                   {:file/id 1 :file/name ".nrepl-port" ::hidden? true}]
       :dir/dirs  [{:dir/id 1 :dir/name "src/example" :dir/open? true
                    :dir/files [{:file/id 2 :file/name "ui.cljs"}
                                {:file/id 3 :file/name "db.cljs"}]}]}
      [::project-root])
    (assoc ::show-hidden? true)
    (db/commit!)
    :data)
```

This is basically what `::load!` does in our example. (We'll get to the `db/...` stuff in a soon.)

### Queries

Components query for data through `sg/query-ident` and `sg/query-root`. Both do either a simple `(get db ident-or-root)` or run an optional EQL query. (Using EQL is, of course, somewhat less performant than just returning the map under ident from db.)

`Computed attributes` are available as `eql/attr` methods. These methods' dispatch functions are EQL attributes and their return will be included in query results for this attribute.

```clojure
(defmethod eql/attr :dir/contains
  [env db {:dir/keys [files dirs] :as current} query-part params]
  (cond->> (concat dirs files)
    (not (::show-hidden? db))
    (filterv (fn [ident] (not (::hidden? (get db ident)))))))

;; in a component, query results return
;; {:dir/contains computed-above, :dir/name ...}
(bind {:dir/keys [name open? contains] :as query-results}
  (sg/query-ident ident [:dir/name :dir/open? :dir/contains]))
```

**Note**: `eql/attr` must not return lazy seqs.

Besides computed attributes, EQL is useful when you need to 'join' multiple normalized pieces into a single denormalized data tree.

Under the hood, `shadow-grove` keeps track of attributes/keys that were queried for. So `name`, `open?` and `contains` for this particular ident. This information enables `shadow-grove`, in conjuction with observing what's changed during transactions (more on this in a bit), to do efficient refreshes of the UI. For more details, consult the [doc](https://github.com/thheller/shadow-experiments/blob/master/doc/what-the-heck-just-happened.md).

Note, however, that all `ident`s that were accessed in `eql/attr :dir/contains` above will also be 'observed'. So, in addition to `name` and `open?` for a dir with `ident`, all the dirs and files it contains will also be 'tracked'. Thus, modifying something (during a transaction) in these idents will cause the above query to re-run. This is mostly fine and sometimes necessary, but it is something to keep in mind when it comes to performance.

### Transactions

The intent is to keep UI-related concerns separate from data/backend concerns. Transactions are simply event maps we've seen earlier.

- `run-tx` – runs a transaction from within a DOM event handler.
- `run-tx!`- runs a transaction outside of the component context (e.g. `load!` in our example).

Example of running a transaction and registering event handlers:
```clojure
;; outside of the component context
(sg/run-tx! rt-ref {:e ::load!})

;; in event handlers
(event ::toggle-hide! [env event-map event]
    (when (.-ctrlKey event)
      (sg/run-tx env event-map)))

;; register event handlers
(ev/reg-event rt-ref ::toggle-hide!
  (fn [tx-env {:keys [ident] :as event-map}]
    ;; note: handlers return the updated `tx-env`.
    (update-in tx-env [:db ident ::hidden?] not)))

;; alternative way of registering event handlers
(defn toggle-hide!
  {::ev/handle ::toggle-hide!}
  [tx-env {:keys [ident] :as event-map}]
  (update-in tx-env [:db ident ::hidden?] not))
```
<!-- todo don't include this alternative? -->

As mentioned above, events for which no `(event ...)` handler is defined (anywhere in the component tree) will automatically run the transaction with `event-map`. This is reduces boilerplate when your events don't need to handle DOM-related stuff.

`(:db tx-env)` is the 'proxy' db: a custom datatype, allowing you to handle it like you would a regular clj map (`assoc`, `update`, etc.), but which 'observes' what you are doing (this is done automatically 'behind the scenes'). Working in tandem with queries, this enables `shadow-grove` to efficiently refresh the UI.

The following helpers are available from `shadow.experiments.shadow-grove.db`:
- Modify the db: `add`, `merge-seq`, `update-entity`, `remove`, `remove-idents`.
- Get all data by entity type: `all-idents-of`, `all-of`.

These are meant to be used inside the transaction environment, but you can test them out (and look at the recorded modifications) in the REPL with a pipeline like:
```clojure
(-> {} (configure schema) (transacted) (add ...) (commit!))
```

## Dev tools

- `tap` (e.g. to `shadow-cljs` inspect) component data by clicking on your component while holding <kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>s</kbd>. Add this to your build in `shadow-cljs.edn`:

  ```clojure
  :modules {:main {:preloads [shadow.experiments.shadow-grove.dev-support]}}
  ```

- `tx-reporter` – called after every transaction with detailed tx report. You can add it like so:

  ```clojure
  (defonce rt-ref
    (-> {::ev/tx-reporter (fn [report] (tap> report))}
        (rt/prepare data-ref ::rt-id)))
  ```


<!-- todo -->
- Might be a good source of info about optimizing/best practices?
https://github.com/thheller/js-framework-shadow-shadow-grove
