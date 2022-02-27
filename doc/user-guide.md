# Introduction to *shadow-grove*

*shadow-grove* is a web framework written in Clojure/Script from the ground up. It is fair to say that performance is a major goal, but *shadow-grove* retains the developer experience we have come to love: UI as a pure function of normalized data queried by [EQL](https://github.com/edn-query-language/eql), clear separation between UI and data concerns, etc.

Loosely speaking, there are two major parts of *shadow-grove*: [UI components](#ui-components) and [normalized db with EQL](#db-and-query). You can use the former without the latter, but they combine together into an optimal experience featuring fast surgical updates of the DOM. It takes the "granular reactivity" approach (no VDOM).

This document is meant to provide an overview and surface the most important functionality. For more details, please consult the docstrings. You can read more about the project's goals and design considerations [here](https://github.com/thheller/shadow-experiments/tree/master/doc).

Since *shadow-grove* is mostly built on top of protocols, one can extend/replace some of its defaults (e.g. using Fulcro/DataScript/etc. for data management). That will not be covered here, but we feel is well worth mentioning.

- [UI Components](#ui-components)
  - [The Fragment Macro `<<`](#the-fragment-macro-)
  - [Lists of DOM elements](#lists-of-dom-elements)
  - [The `defc` component macro](#the-defc-component-macro)
- [DB and Query](#db-and-query)
  - [DB schema and architecture](#db-schema-and-architecture)
  - [Querying for data](#querying-for-data)
  - [Transactions](#transactions)
  - [fx – doing side effects](#fx--doing-side-effects)
- [Dev tools](#dev-tools)
- [More TODO](#more-todo)
  - [HTML History](#html-history)
  - [DOM node ref](#dom-node-ref)
  - [Keyboard listener](#keyboard-listener)

### Full app examples

If you'd like to take a look at full app examples:
- Most up-to-date and in-use is the [shadow-cjs-ui](https://github.com/thheller/shadow-cljs/tree/master/src/main/shadow/cljs/ui)
- todomvc examples: [simple](https://github.com/thheller/shadow-experiments/blob/master/src/dev/todomvc/simple.cljs) (just the component framework) and [split](https://github.com/thheller/shadow-experiments/tree/master/src/dev/todomvc/split) (+ db and eql) (**Note**: these are not a 100% up-to-date with the API, but they work.)
<!-- todo perhaps not? -->
- Proof-of-concept [integration with Fulcro](https://github.com/zeitstein/shadow-grove-fulcro-integration)

### The example and namespaces used in this doc

<!-- todo link to example repo -->
You can find the simple example we'll be using throughout this doc in the following repo. You can try it out and follow along.

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
   :dir/dirs  [{:dir/id 1 :dir/name "src/example" :dir/open? true
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

The *fragment macro* `<<` produces DOM nodes which will 'reactively update' when 'variables' inside it change. The macro detects things that can change (`Symbol`s) at compile time and emits optimized functions for creating and updating the DOM, which skip over the static parts of the fragment and only update what actually changed.

[Examples of templating with `<<`](https://code.thheller.com/demos/shadow-shadow-grove/?id=e0e91cac520338e6889f9224d7bcf7b8)

Featured in the `ui-dir` component of our example:

```clojure
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
                 (ui-file %)))]))])
```

The above fragment tracks `ident`, `hidden?`, `name`, etc. If only the value of `name` changes, it will only update the string within the `span.name` element.

**Note**: the macro only analyses `hiccup` and will stop when it encounters anything else. (Note `<<` inside `when` above.)

The macro itself does not create DOM nodes, so passing its results around is cheap.

### Lists of DOM elements

Functions which allow *shadow-grove* to optimize managing sequences of DOM nodes are available. You can see `keyed-seq` used in the example above. There is also `simple-seq`. Check out their docstrings for details and advice on their usage.

### The `defc` component macro

The goal of the [component design](https://github.com/thheller/shadow-experiments/blob/master/doc/components.md#components) is to gain access to incremental computation based on application events (e.g. state changes).

Here's a simple example of "incremental computation":
```clojure
(defn baz-defn [{:keys [a b]}]
  (let [x (print-inc "x" a)
        y (print-inc "y" b)]
    (<< [:div x y])))

;; x is recomputed only if a changes
(defc baz-defc [{:keys [a b]}]
  (bind x (print-inc "x" a))
  (bind y (print-inc "y" b))

  (render (<< [:div x y])))
```

We'll be looking at the `ui-dir` component from our example, step by step.

```clojure
(defc ui-dir [ident]
  (bind {:dir/keys [name open? contains] ::keys [hidden?] :as query-result}
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

To a first approximation, *hooks* can be thought of as values that are "wired up together" at compile time. Think nodes in a directed graph, where a node's value will update only if the preceding value it is connected to changes. This works together with the fragment macro `<<` to provide granular DOM updates. It helps to look at an example:

```clojure
(bind {:dir/keys [name open? contains] ::keys [hidden?] :as query-result}
  (sg/query-ident ident [:dir/name :dir/open? :dir/contains ::hidden?]))

(bind num-visible
  (expensive-computation contains))
```

Above:
- A hook is created and its value is initialized to `(expensive-computation contains)` on component mount. `num-visible` is "bound" to this value. 
- `expensive-computation` will only re-trigger (and `num-visible` update) if `contains` changes – but not if anything else from `query-result` changes.
- In this case, only the bits depending on `num-visible` and `contains` will be updated in the DOM.
- The hook will be "destroyed" when component unmounts from the DOM.

Technically, hooks are a custom datatype managed by components. This is completely kept "under the hood" and you mostly don't need to think about the underlying hooks. However, one thing to be aware of is that there are [different types of hooks](https://github.com/thheller/shadow-experiments/blob/master/doc/components.md). The simplest type (`SimpleVal`) underlies `num-visible`, while `query-result` is bound to a type (`QueryHook`) which implements [query](#querying-for-data)-specific behaviour through `sg/query-ident`.

(We don't much like the term "hooks", so if you have a better idea - let us know!)

The *component macro* `defc` takes four types of hooks:
- `(bind <name> <&body>)` sets up a named binding that can be used in later hooks. If the value changes all other hooks using that binding will also be triggered.
- `(hook <&body>)` is the same as `bind` but does not have an output other hooks can use.
- `(render <&body>)` produces the component output, can only occur once.
- `(event <event-kw> <arg-vector> <&body>)` creates an event handler fn.

Note that all of these execute in order so `bind` and `hook` cannot be used after `render`. They may also only use bindings declared before themselves. They may shadow the binding name if desired. `event` can be used after or before `render` as it will trigger when the event fires and not during component rendering.

There are some **rules for hooks**:
- The `defc` macro already enforces that things are called in the correct order.
- Once created, a hook's type must remain consistent. Basically the only thing that should be avoided is conditionals like:
  ```clojure
  (bind foo
    (if bar (sg/query-ident baz) (expensive-computation baz)))
  ```
  This can lead to errors since the value of `bar` can change – and hence the underlying hook type – without the component re-mounting.

<!-- !todo remove? -->
- `env-watch`
- `watch` – bind and watch atoms, can model local state but it is strongly discouraged in favor of using a normalized db as the place for state management. (We believe this approach leads to less code and less complicated component logic easier to reason about)

#### Events

*Events* are declared as data:

```clojure
{:e ::event-name :data1 "data1" :data2 "data2" ,,,}
```

Events can trigger both DOM event handlers and db operations (as [transactions](#transactions)):

```clojure
;; DOM event declaration
[:span.name {:on-click {:e ::dir-click! :ident ident}} name]

;; DOM event handler
(event ::dir-click! [env {:keys [ident] :as event-map} event]
  (if (.-ctrlKey e)
    ;; will run event ::toggle-hide! as a db transaction
    (sg/run-tx env {:e ::toggle-hide! :ident ident})
    (sg/run-tx env {:e ::toggle-open! :ident ident})))
```

If no `(event ...)` handler is registered with the component, events will bubble up the component tree (`::toggle-hide!` in `ui-file`). If no component processes the event, it will by handled by a "root event target". If using *shadow-grove*'s db and query, the event map will be passed along as a [transaction](#transactions) (`toggle-show-hidden!` in `ui-root`). Otherwise, you can [plug in your own](https://github.com/thheller/shadow-experiments/blob/f875c29bbda34d19ebdedf335b5a3ea40c7a4030/src/main/shadow/experiments/shadow-grove.cljs#L167).

The *component environment* (`env` above) is a place to store data that should be accessible from components, without using global state. It is constructed at the root and then passed down to every node. You can add things to it on init with:

```clojure
(swap! rt-ref update ::rt/env-init conj #(assoc % ...))
```

<!-- todo -->
- `dispatch-up!`

#### Effects

*Effects* are mainly meant to be used to make manual modifications to the DOM based on data changes (e.g. help out with transitions/animations; for data modifications use [transactions](#transactions)). Each effect takes a callback function, which may return an optional clean-up function. There are: `effect`, `mount-effect` and `render-effect`. Current API is:

```clojure
(defc ...
  (hook (sg/effect ...)))
```


## DB and Query

The normalized db is organised as a flat map, with entity idents as keys. At points of 'contact' with the db (e.g. in event handlers) the db is available as a map one can `update`, `assoc-in`, etc.

A basic EQL engine is available. Instead of composing a big query at UI root, we feel that each component should just query for exactly the data it needs. (Certainly a trade off, e.g. with respect to remote interactions.) Components can get their data in a declarative way without being coupled to where that data is actually coming from.

Nevertheless, it is still very much about your UI being a function of your data.

### DB schema and architecture

A *schema* is defined to enable normalization helpers.
- *entity types* – used track different types of entities you might have in your data. (`::dir` and `::file` in example below.)
- `:primary-key` – used as ident generating function. You can supply your own in `:ident-gen`.
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

To get a feel for *shadow-grove*'s db architecture, evaluate or `tap`:

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

This is basically what `::load!` does in our example. (We'll get to the `db/...` stuff soon.)

### Querying for data

Components query for data through `sg/query-ident` and `sg/query-root`. The first can do a simple ident lookup, while both can run an optional EQL query. (Using EQL is, of course, somewhat less performant than just returning the map under ident from db.) It is encouraged to structure your component tree so as to use as many fine grained ident lookup queries as possible, as those are the most efficient.

*Computed attributes* are available as `eql/attr` methods. These methods' dispatch functions are EQL attributes and their return will be included in query results for this attribute.

```clojure
(defmethod eql/attr :dir/contains
  [env db {:dir/keys [files dirs] :as current} query-part params]
  (cond->> (concat dirs files)
    (not (::show-hidden? db))
    (filterv (fn [ident] (not (::hidden? (get db ident)))))))

;; in a component, query results return
;; {:dir/contains computed-above, :dir/name ...}
(bind {:dir/keys [name open? contains] :as query-result}
  (sg/query-ident ident [:dir/name :dir/open? :dir/contains]))
```

**Note**: `eql/attr` must not return lazy seqs.

Besides computed attributes, EQL is useful when you need to 'join' multiple normalized pieces into a single denormalized data tree.

Under the hood, *shadow-grove* keeps track of idents that were queried for. So, in the example above, `name`, `open?` and `contains` for this particular `ident`. This information, in conjuction with observing what's changed during transactions (more on this in a bit), enables *shadow-grove* to do efficient refreshes of the UI. For more details, consult the [doc](https://github.com/thheller/shadow-experiments/blob/master/doc/what-the-heck-just-happened.md).

Note, however, that all `ident`s that were accessed in `eql/attr :dir/contains` above will also be "observed". So, in addition to `name` and `open?` for a dir with `ident`, all the dirs and files it contains will also be tracked. Thus, modifying something (during a transaction) in these idents will cause the above query to re-run. This is mostly fine and sometimes necessary, but it is something to keep in mind when it comes to performance.

### Transactions

The db is modified through *transactions*. The intent is to keep UI-related concerns separate from data/backend concerns. Transactions are simply event maps we've seen earlier with corresponding *event handlers*.

- `run-tx` – runs a transaction from within a DOM event handler.
- `run-tx!` – runs a transaction outside of the component context.

Examples of running transactions and registering event handlers:
```clojure
;; outside of the component context, e.g. on UI init
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

;; alternative method of registering event handlers
(defn toggle-hide!
  {::ev/handle ::toggle-hide!}
  [tx-env {:keys [ident] :as event-map}]
  (update-in tx-env [:db ident ::hidden?] not))
```
<!-- todo don't include this alternative? -->

Note that handlers are provided with, modify and return the transaction environment `tx-env`. Although the event systems are not identical, reading about [*re-frame*'s event handlers](https://day8.github.io/re-frame/EffectfulHandlers/) may be helpful.

`(:db tx-env)` is the 'proxy' db: a custom datatype, allowing you to handle it like you would a regular clj map (`assoc`, `update`, etc.), but which 'observes' what you are doing: which idents were added, updated or removed (this is done automatically 'behind the scenes'). Working in tandem with queries, this enables *shadow-grove* to efficiently refresh the UI.

The following helpers are available from `shadow.experiments.grove.db`:
- Modify the db: `add`, `merge-seq`, `update-entity`, `remove`, `remove-idents`.
- Get all data by entity type: `all-idents-of`, `all-of`.

These are meant to be used inside the transaction environment, but you can try them out (and see the modifications recorded) in the REPL with a pipeline like:
```clojure
(-> {} (configure schema) (transacted) (add ...) (commit!))
```

As mentioned above, events for which no `(event ...)` handler is defined (anywhere in the component tree) will automatically run the transaction with `event-map`. This reduces boilerplate when your events don't need to handle DOM-related stuff. In the example above, if `(.ctrlKey event)` was not needed, the whole `(event ::toggle-hide!) ...` can be omitted.

### fx – doing side effects

*fx* is used for doing side effects during transactions (inside event handlers). fx are not supposed to modify the db. A snippet from our example:

```clojure
(ev/reg-event rt-ref ::toggle-show-hidden!
  (fn [tx-env {:keys [show?] :as event}]
    (-> tx-env
      (assoc-in [:db ::show-hidden?] show?) ;; modify the db
      (ev/queue-fx ::alert! event)))) ;; schedule an fx

(ev/reg-fx rt-ref ::alert!
  (fn [fx-env {:keys [show?] :as fx-data}]
    (js/alert (str "Will " (when-not show? "not") " show hidden files."))))
```

`(:transact! fx-env)` allows fx to schedule another transaction, but it should be an async call. Example from [HTML History](#html-history):

```clojure
(ev/reg-fx rt-ref :ui/redirect!
  (fn [{:keys [transact!] :as env} {:keys [token title]}]
    ;; parts removed for clarity

    (let [tokens (str/split (subs token 1) #"/")]
      ;; forcing the transaction to be async
      (js/setTimeout #(transact! {:e :ui/route! :token token :tokens tokens}) 0))))
```

#### `http-fx` – run HTTP requests as fx

See `shadow.experiments.grove.http-fx/make-handler` docstring for complete details. Here's an annotated example.

```clojure
(ev/reg-event rt-ref ::run-api
  (fn [env {:keys [name status]}]
    ;; 'schedules' the http request in ::api-request 
    (ev/queue-fx env ::api-request
      ;; http request definition
      {:request {:uri ["api-point" {:name name :status status}]
                 :method :POST
                 :body {:foo "will be turned into transit!"}}
       ;; defines the event (tx) to trigger if the request is successful
       :on-success {:e ::merge-api-result :foo :bar}})))

(ev/reg-fx rt-ref ::api-request
  ;; makes the http request handler taking [fx-env {:keys [request on-success]}]
  ;; i.e. returns the actual function which runs an http request (XHR)
  (http/make-handler
    ;; config shared by all requests initiated by this handler
    {:on-error {:e ::api-error} ;; tx to run on error
     :base-url "api/" ;; ::run-api url will be "/api/api-point?name=name&status=status
     :request-format :transit ;; will serialize request body with transit
     :response-formats ;; customise response based on content type
     {"application/json"
      (fn [env ^js xhr-req]
        (-> (http/parse-json env xhr-req) ;; the default behaviour, see http-fx/default-response-formats
            ;; turn each json response into clj data
            (js->clj :keywordize-keys true)))}}))

;; this tx runs once the ::api-request completes successfully
(ev/reg-event rt-ref ::merge-api-result
  ;; the event-map will be provided with the properly formatted response of the request in `result`
  (fn [env {:keys [result foo]}] ;; foo from ::run-api
    (assoc-in env [:db :result] result)))

(ev/reg-event rt-ref ::api-error
  (fn [env {:keys [result status sent-request] :as e}]
    (js/console.log "e" e)))
```

If using `transit`, add this to your `init` fn:
```clojure
(shadow.experiments.grove.transit/init! rt-ref)
```
There's also `shadow.experiments.grove.edn`.

## Dev tools

- `tap` component data by clicking on your component while holding <kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>s</kbd>. Add this to your build in `shadow-cljs.edn`:

  ```clojure
  :modules {:main {:preloads [shadow.experiments.grove.dev-support]}}
  ```

  You can look at the data in the inspect tab of shadow-cljs ui.

- `tx-reporter` – called after every transaction with detailed a tx report. You can add it like so:

  ```clojure
  (defonce rt-ref
    (-> {::ev/tx-reporter (fn [report] (tap> report))}
        (rt/prepare data-ref ::rt-id)))
  ```


## More TODO
<!-- todo how ready is all this? --> 

### HTML History

The `shadow.experiments.grove.history/init!` fn enables 'UI routing' by intercepting clicks on 'internal' links and HTML `History` modifications to:
1. update the state of `History` (e.g. update the URL)
2. trigger the `:ui/route!` event (for which the handler is defined by the user).

Usage:
- This function should be called before component env is initialised (e.g. before `shadow.experiments.grove/render`).
- Set the `:ui/href` attribute on anchors for internal links. (Unlike regular `href` will automatically take care of `path-prefix` and `use-fragment`.)
- Intercepts only pure clicks with main mouse button (no keyboard modifiers).
- Register handler for the event `{:e :ui/route! :token token :tokens tokens}`.
- The `:ui/redirect!` *fx* handler will be registered and can be 'called' with:
  * `:token` – URL to redirect to (a string starting with `/`)
  * `:title` – optional. `title` arg for `history.pushState`


```clojure
;; in a component
[:a {:ui/href (str "/" id)} "internal link"]

;; on click, this event will change the ::main-root ident based on URL
(ev/reg-event rt-ref :ui/route!
  (fn [env {:keys [token tokens]}]
    (let [ident (db/make-ident ::entity (first tokens))]
      (-> env
          (assoc-in [:db ::main-root] ident)
          ;; not provided
          (ev/queue-fx :ui/set-window-title! {:title (get-title ident)})))))

;; calling init
(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

(defn init []
  (history/init! rt-ref {})
  (start))
```

### DOM node ref

```clojure
(defc ref []
  (bind container-ref (sg/ref))

  (render (<< [:div {:dom/ref container-ref
                     :on-click {:e ::click! :ref container-ref}}
               "text"]))

  (event ::click! [_ {:keys [ref]}]
    (js/console.log @ref)))

```

### Keyboard listener

Uses magic attributes and goog `KeyHandler`. You set the attribute `::keyboard/listen true` on a DOM element that wants to listen to keyboard events and create event handlers for specific keys.

```clojure
(defc keyboard []
  (render
    (<< [:span {::ky/listen true
                :content-editable true}
         "some text"]))

  (event ::ky/ctrl+arrowleft [env ev e]
    (js/console.log "ctrl+leftarrow"))

  (event ::ky/ctrl+i [en ev e]
    (js/console.log "ctrl+i")))
```
