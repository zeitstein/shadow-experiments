(ns shadow.experiments.grove.history
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.arborist.attributes :as attr]))

(defn init!
  "Use to intercept clicks on 'internal' links and HTML `History` modifications to:
  1. update the state of `History` (e.g. update the URL)
  2. trigger the `:ui/route!` event (for which the handler is defined by the user).

  `config` map:
  * `:start-token` – replaces the \"/\" URL on init. Defaults to `\"/\"`. Should
                     start – but not end – with `/`.
  * `:path-prefix` – a common URL base to use. Defaults to `\"\"`. Should start
                     – but not end – with `/`.
  * `:use-fragment` – pass `true` if you want to use hash-based URLs. Defaults
                      to `false`.
  * `:root-el` - optional DOM node to set the click listener on. Defaults to
                 `document.body`.

  ### Usage
  - This function should be called before component env is initialised (e.g.
    before [[shadow.experiments.grove/render]]).
  - Set the `:ui/href` attribute on anchors for internal links. (Unlike regular 
    `href` will automatically take care of `path-prefix` and `use-fragment`.)
  - Register handler for the event `{:e :ui/route! :token token :tokens tokens}`.
  - Only pure clicks with main mouse button (no keyboard modifiers) will be intercepted.
  - The `:ui/redirect!` *fx* handler will be registered and can be 'called' with:
    * `:token` – URL to redirect to (a string starting with `/`)
    * `:title` – optional. `title` arg for `history.pushState`"
  [rt-ref
   {:keys [start-token path-prefix use-fragment root-el]
    :or {start-token "/"
         path-prefix ""
         use-fragment false}
    :as config}]

  {:pre [(or (= "" path-prefix)
             (and (string? path-prefix)
                  (str/starts-with? path-prefix "/")
                  (not (str/ends-with? path-prefix "/"))))

         (or (= "/" start-token)
             (and (str/starts-with? start-token "/")
                  (not (str/ends-with? start-token "/"))))
         ]}

  (let [get-token
        (fn []
          (if-not use-fragment
            (let [path js/window.location.pathname]
              (cond
                (= path path-prefix)
                "/"

                (str/starts-with? path path-prefix)
                (subs path (count path-prefix))

                :else
                (throw (ex-info "path did not match path prefix" {:path path :path-prefix path-prefix}))
                ))
            (let [hash js/window.location.hash]
              ;; always start everything with a / even when using hash
              ;; is "" when url doesn't have a hash, otherwise #foo
              (if (= hash "")
                "/"
                (subs js/window.location.hash (+ 1 (count path-prefix)))))))]

    (attr/add-attr :ui/href
      (fn [env node oval nval]
        (when nval
          (when-not (str/starts-with? nval "/")
            (throw (ex-info (str ":ui/href must start with / got " nval)
                     {:val nval})))

          (set! node -href
            (if use-fragment
              (str "#" path-prefix nval)
              (str path-prefix
                   (if-not (str/ends-with? path-prefix "/")
                     nval
                     (subs nval 1))))))))

    (ev/reg-fx rt-ref :ui/redirect!
      (fn [{:keys [transact!] :as env} {:keys [token title]}]
        {:pre [(str/starts-with? token "/")]}

        (js/window.history.pushState
          nil
          (or title js/document.title)
          (str path-prefix token))

        (let [tokens (str/split (subs token 1) #"/")]
          ;; FIXME: there needs to be cleaner way to start another tx from fx
          ;; currently forcing them to be async so the initial tx can conclude
          (js/setTimeout #(transact! {:e :ui/route! :token token :tokens tokens}) 0)
          )))

    (swap! rt-ref
      (fn [rt]
        (-> rt
            (assoc ::config config)
            (update ::rt/env-init conj
              (fn [env]
                (let [trigger-route!
                      (fn []
                        ;; token must start with /, strip it to get tokens vector
                        (let [token (get-token)
                              tokens (str/split (subs token 1) #"/")]

                          (sg/run-now! env #(sg/run-tx env {:e :ui/route! :token token :tokens tokens}))))

                      first-token
                      (get-token)]

                  ;; fragment uses hashchange event so we can skip checking clicks
                  (when-not use-fragment
                    (.addEventListener (or root-el js/document.body) "click"
                      (fn [^js e]
                        (when (and (zero? (.-button e))
                                   (not (or (.-shiftKey e) (.-metaKey e) (.-ctrlKey e) (.-altKey e))))
                          (when-let [a (some-> e .-target (.closest "a"))]

                            (let [href (.getAttribute a "href")
                                  a-target (.getAttribute a "target")]

                              (when (and href (seq href) (str/starts-with? href path-prefix) (nil? a-target))
                                (.preventDefault e)

                                (js/window.history.pushState nil js/document.title href)

                                (trigger-route!)
                                )))))))

                  (when (and (= "/" first-token) (seq start-token))
                    (js/window.history.replaceState
                      nil
                      js/document.title
                      (str (when use-fragment "#") path-prefix start-token)))

                  (trigger-route!)

                  (js/window.addEventListener "popstate"
                    (fn [e]
                      (trigger-route!)))

                  (when use-fragment
                    (js/window.addEventListener "hashchange"
                      (fn [e]
                        (trigger-route!)))))

                env)))))))
