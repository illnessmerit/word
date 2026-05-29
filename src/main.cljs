(ns main
  (:require [cljs-node-io.core :refer [slurp]]
            [cljs.math :refer [ceil]]
            [com.rpl.specter :refer [AFTER-ELEM ALL ATOM FIRST LAST MAP-VALS NONE nthpath pred= setval setval* transform]]
            [groq-sdk :refer [Groq]]
            [os :refer [homedir]]
            [path :refer [join]]
            [promesa.core :as promesa :refer [all resolved]]))

(defonce state
  (atom {}))

(defn call-function
  [fname args]
  (.then (.callFunction (:nvim @state) fname (clj->js args))
         #(js->clj % :keywordize-keys true)))

(defn decode
  [s]
  ;; This ensures that string length matches the UTF-8 byte count because Neovim uses byte-indexed positions.
  (.toString (js/Buffer.from s) "latin1"))

(defn get-selection-bounds
  []
  ;; https://github.com/neovim/neovim/issues/14451
  (promesa/let [mode (call-function "mode" [])
                positions (all (map (partial call-function "getpos") ["." "v"]))
                bounds (sort (map (comp vec
                                        (partial map dec)
                                        drop-last
                                        rest)
                                  positions))
                lines (.buffer.getLines (:nvim @state) (clj->js {:start (first (last bounds))
                                                                 :end (-> bounds
                                                                          last
                                                                          first
                                                                          inc)}))]
    (if (= "V" mode)
      (setval [LAST LAST]
              (-> lines
                  first
                  decode
                  count)
              (setval [FIRST LAST] 0 bounds))
      bounds)))

(defn get-sentences
  []
  (promesa/let [[start-pos end-pos] (get-selection-bounds)
                start-sentence (call-function "Get" {:pos start-pos})]
    (if start-sentence
      (promesa/let [end-sentence (call-function "Get" {:pos end-pos})
                    end-sentence* (if end-sentence
                                    end-sentence
                                    (call-function "Get" {:offset -1
                                                          :pos end-pos}))]
        (if (= start-sentence end-sentence*)
          [start-sentence]
          (promesa/loop [sentences [end-sentence*]]
            (promesa/let [previous-sentence (call-function "Get" {:offset -1
                                                                  :pos (drop-last (first sentences))})]
              (if (= start-sentence previous-sentence)
                (cons start-sentence sentences)
                (promesa/recur (cons previous-sentence sentences)))))))
      [])))

(defn prepend
  [sentences]
  (promesa/let [previous-sentence (call-function "Get" {:offset -1
                                                        :pos (drop-last (first sentences))})]
    (cons (or previous-sentence [0 0 0]) sentences)))

(defn request
  [function & args]
  (.then (.request (:nvim @state) function (clj->js args))
         #(js->clj % :keywordize-keys true)))

(defn get-overlapping-extmarks
  [buf ns-id start end]
  (promesa/let [extmarks (request "nvim_buf_get_extmarks"
                                  buf
                                  ns-id
                                  start
                                  end
                                  {:details true
                                   :overlap true})]
    (remove (fn [[_ row col details]]
              (or (= start ((juxt :end_row :end_col) details))
                  (= end [row col])))
            extmarks)))

(defn refresh-range
  [[start end]]
  (promesa/let [extmarks (get-overlapping-extmarks 0
                                                   (:resolved-range (:namespace @state))
                                                   start
                                                   end)]
    (all (mapcat (comp (apply juxt (map #(partial request "nvim_buf_del_extmark" 0 %)
                                        ((juxt :resolved-range :resolved-sentence) (:namespace @state))))
                       first)
                 extmarks))
    (request "nvim_buf_set_extmark"
             0
             (:pending-range (:namespace @state))
             (first start)
             (last start)
             {:end_col (last end)
              :end_row (first end)})))

(defn refresh-ranges
  [sentences]
  (promesa/let [sentences* (prepend sentences)]
    (->> sentences*
         (setval [ALL (nthpath 1)] NONE)
         (partition 2 1)
         (map refresh-range)
         all)))

(defn set-sentence-extmark
  [[row start-col end-col]]
  (request "nvim_buf_set_extmark"
           0
           (:pending-sentence (:namespace @state))
           row
           start-col
           {:end_col end-col
            :end_row row
            :hl_group "DiagnosticUnderlineWarn"}))

(def set-sentence-extmarks
  (comp all
        (partial map set-sentence-extmark)))

(def llast
  (comp last last))

(defn append
  [sentences]
  (promesa/let [next-sentence (call-function
                               "Get"
                               {:offset 1
                                :pos [(first (last sentences)) (llast sentences)]})]
    (setval AFTER-ELEM
            (or next-sentence [(first (last sentences)) (llast sentences) (llast sentences)])
            sentences)))

(def get-contexts*
  (comp (partial map (comp (partial setval* [MAP-VALS (pred= "")] NONE)
                           (partial zipmap [:previous-sentence :target-sentence :next-sentence])))
        (partial partition 3 1)))

(defn encode
  [s]
  (.toString (js/Buffer.from s "latin1")))

(defn slice
  [s start end]
  (-> s
      decode
      (subs start end)
      encode))

(defn get-contexts
  [sentences]
  (promesa/let [sentences* (prepend sentences)
                sentences** (append sentences*)
                lines (.buffer.getLines (:nvim @state) (clj->js {:start (ffirst sentences**)
                                                                 :end (-> sentences**
                                                                          last
                                                                          first
                                                                          inc)}))]
    (get-contexts* (map (fn [[row start-col end-col]]
                          (slice (nth (js->clj lines) (- row (ffirst sentences**))) start-col end-col))
                        sentences**))))

(defn get-styles
  []
  (promesa/let [styles (.lua (:nvim @state) "return require('word').config.styles")]
    (js->clj styles :keywordize-keys true)))

(defn get-prompt
  []
  (promesa/let [styles (get-styles)]
    (:prompt (nth styles (:index @state)))))

(def api-key
  (-> (homedir)
      (join ".config/word/groq")
      slurp))

(def groq
  (Groq. (clj->js {:apiKey api-key})))

(def model
  "openai/gpt-oss-120b")

(def bounded-string
  {:type "string"
   ;; https://console.groq.com/docs/structured-outputs
   ;; maxLength and minLength are not explicitly documented in Groq's Structured Outputs guide.
   ;; But they appear to be supported.
   :maxLength 1000
   :minLength 1})

(def suggestions
  {:items bounded-string
   ;; https://console.groq.com/docs/structured-outputs
   ;; maxItems and minItems are not explicitly documented in Groq's Structured Outputs guide.
   ;; But they appear to be supported.
   :maxItems 2
   :minItems 2
   :type "array"})

(defn wrap-schema
  [properties]
  {:additionalProperties false
   :properties properties
   :required (keys properties)
   :type "object"})

(def response-format
  {:json_schema {:name "word"
                 :schema (wrap-schema {:word {:anyOf (map wrap-schema [{:pass {:enum [true]
                                                                               :type "boolean"}
                                                                        :suggestions suggestions}
                                                                       {:explanation bounded-string
                                                                        :pass {:enum [false]
                                                                               :type "boolean"}
                                                                        :suggestions suggestions}])}})
                 :strict true}
   :type "json_schema"})

(def parse-response
  (comp :word
        #(js->clj % :keywordize-keys true)
        js/JSON.parse
        :content
        :message
        first
        :choices
        #(js->clj % :keywordize-keys true)))

(defn format-lines
  [cache]
  ((if (:explanation cache)
     (partial setval* AFTER-ELEM (:explanation cache))
     identity)
   (:suggestions cache)))

(defn close-hud
  []
  (request "nvim_buf_clear_namespace" 0 (:active-sentence (:namespace @state)) 0 -1)
  (when-let [window (:window @state)]
    (setval [ATOM :window] NONE state)
    (request "nvim_win_close" (:hud window) true)))

(defn outside-hud?
  []
  (if-let [active-window (:window @state)]
    (promesa/let [window (.-window (:nvim @state))]
      (not= (:hud active-window)
            (.-id window)))
    (resolved true)))

(defn get-extmarks
  []
  (promesa/let [source-window (.-window (:nvim @state))
                cursor (.-cursor source-window)
                cursor* (transform (nthpath 0) dec (js->clj cursor))
                extmarks (request "nvim_buf_get_extmarks"
                                  0
                                  (:resolved-range (:namespace @state))
                                  cursor*
                                  cursor*
                                  {:overlap true})]
    (js->clj extmarks)))

(defn get-sentence-extmark
  [id]
  (request "nvim_buf_get_extmark_by_id"
           0
           (:resolved-sentence (:namespace @state))
           id
           {:details true}))

(defn calculate-height
  [lines width]
  (apply + (map (comp ceil
                      #(/ % width)
                      count)
                lines)))

(defn render-hud
  []
  ;; We guard against nil (:nvim @state) because Neovim may trigger autocommands during startup.
  ;; Without this check, accessing properties like (.-window ...) on a null object throws a TypeError.
  (promesa/let [outside-hud (outside-hud?)]
    (when (and (:nvim @state) outside-hud)
      (promesa/let [extmarks (get-extmarks)]
        (if (empty? extmarks)
          (close-hud)
          (promesa/let [extmark (get-sentence-extmark (ffirst extmarks))
                        hud-buffer (:buffer @state)
                        source-buffer (.-buffer (:nvim @state))
                        lines (-> @state
                                  :cache
                                  ((-> source-buffer
                                       .-id
                                       str
                                       keyword))
                                  ((-> extmarks
                                       ffirst
                                       str
                                       keyword))
                                  format-lines)
                        source-window (.-window (:nvim @state))
                        width (.-width source-window)]
            (request "nvim_buf_set_extmark"
                     0
                     (:active-sentence (:namespace @state))
                     (first extmark)
                     (second extmark)
                     (merge {:hl_group "LspReferenceText"
                             :id 1}
                            (select-keys (last extmark) #{:end_row :end_col})))
            (.setLines hud-buffer
                       (clj->js lines)
                       (clj->js {:start 0
                                 :end -1}))
            (if (and (:window @state)
                     (->> @state
                          :window
                          :source
                          (= (.-id source-window))))
              (request "nvim_win_set_height" (:hud (:window @state)) (calculate-height lines width))
              (promesa/do
                (close-hud)
                (promesa/let [hud-window (.openWindow (:nvim @state)
                                                      (:buffer @state)
                                                      false
                                                      (clj->js {:height (calculate-height lines width)
                                                                :split "below"
                                                                :style "minimal"}))]
                  (setval [ATOM :window]
                          {:source (.-id source-window)
                           :hud (.-id hud-window)}
                          state))))))))
    ;; We return nil to ensure the promise resolves to a value that can be safely serialized via RPC.
    ;; In synchronous autocommands, if the promise resolves to a structure containing non-serializable objects, the Neovim Node client throws "Error: Unrecognized object".
    nil))

(def pass-highlight
  "DiagnosticUnderlineOk")

(def fail-highlight
  "DiagnosticUnderlineError")

(def unverified-highlight
  "DiagnosticUnderlineHint")

(defn apply-suggestion*
  [index]
  (promesa/let [extmarks (get-extmarks)]
    (when-not (empty? extmarks)
      (promesa/let [extmark (get-sentence-extmark (ffirst extmarks))
                    buffer (.-buffer (:nvim @state))
                    suggestion (-> @state
                                   :cache
                                   ((-> buffer
                                        .-id
                                        str
                                        keyword))
                                   ((-> extmarks
                                        ffirst
                                        str
                                        keyword))
                                   :suggestions
                                   (nth (dec index)))
                    opts {:end_col (->> suggestion
                                        decode
                                        count
                                        (+ (second extmark)))
                          :end_row (first extmark)
                          :id (ffirst extmarks)}]
        (request "nvim_buf_set_text"
                 (.-id buffer)
                 (first extmark)
                 (second extmark)
                 (:end_row (last extmark))
                 (:end_col (last extmark))
                 [suggestion])
        ;; https://github.com/neovim/neovim/issues/30331
        (request "nvim_buf_set_extmark"
                 (.-id buffer)
                 (:resolved-sentence (:namespace @state))
                 (first extmark)
                 (second extmark)
                 (setval :hl_group pass-highlight opts))
        (request "nvim_buf_set_extmark"
                 (.-id buffer)
                 (:resolved-range (:namespace @state))
                 (second (first extmarks))
                 (last (first extmarks))
                 opts)))))

(def apply-suggestion
  (comp apply-suggestion* first))

(defn handle-closing*
  [id]
  (when-let [window (:window @state)]
    (condp = id
      (:source window)
      (do (setval [ATOM :window] NONE state)
          ;; If only two windows remain attempting to close the HUD window during the 'WinClosed' autocommand of the source window triggers:
          ;; E855: Autocommands caused command to abort
          (promesa/let [windows (.-windows (:nvim @state))
                        buffer (.-buffer (:nvim @state))
                        modified (.getOption buffer "modified")]
            (if (->> windows
                     js->clj
                     count
                     (= 2))
              (if modified
                (promesa/do
                  (.errWriteLine (:nvim @state) "E37: No write since last change (add ! and quit again to override)")
                  (.command (:nvim @state) (str "sb " (.-id buffer)))
                  (request "nvim_win_close" (:hud window) true))
                (.quit (:nvim @state)))
              (request "nvim_win_close" (:hud window) true))))
      (:hud window)
      (do (setval [ATOM :window] NONE state)
          nil)
      nil)))

(def handle-closing
  (comp handle-closing* parse-long))

(defn handle*
  [payload]
  (promesa/let [pending-range-extmark (request "nvim_buf_get_extmark_by_id"
                                               (:buffer payload)
                                               (:pending-range (:namespace @state))
                                               (:extmark payload)
                                               {:details true})]
    (when-not (empty? pending-range-extmark)
      (promesa/let [pending-sentence-extmark (request "nvim_buf_get_extmark_by_id"
                                                      (:buffer payload)
                                                      (:pending-sentence (:namespace @state))
                                                      (:extmark payload)
                                                      {:details true})
                    pending-range-extmarks (get-overlapping-extmarks (:buffer payload)
                                                                     (:pending-range (:namespace @state))
                                                                     (take 2 pending-range-extmark)
                                                                     ((juxt :end_row :end_col) (last pending-range-extmark)))
                    resolved-sentence-extmark (request "nvim_buf_set_extmark"
                                                       (:buffer payload)
                                                       (:resolved-sentence (:namespace @state))
                                                       (first pending-sentence-extmark)
                                                       (second pending-sentence-extmark)
                                                       (setval :hl_group
                                                               (if (:pass payload)
                                                                 pass-highlight
                                                                 fail-highlight)
                                                               (select-keys (last pending-sentence-extmark) #{:end_row :end_col})))]
        (setval [ATOM
                 :cache
                 (-> payload
                     :buffer
                     str
                     keyword)
                 (keyword (str resolved-sentence-extmark))]
                (select-keys payload #{:explanation :pass :suggestions :target-sentence})
                state)
        (request "nvim_buf_set_extmark"
                 (:buffer payload)
                 (:resolved-range (:namespace @state))
                 (first pending-range-extmark)
                 (second pending-range-extmark)
                 (select-keys (last pending-range-extmark) #{:end_row :end_col}))
        (all (mapcat (comp (apply juxt (map #(partial request "nvim_buf_del_extmark" (:buffer payload) %)
                                            ((juxt :pending-range :pending-sentence) (:namespace @state))))
                           first)
                     pending-range-extmarks))
        (render-hud)))))

(def handle-result
  (comp handle*
        #(js->clj % :keywordize-keys true)
        first))

(defn style*
  [index]
  (setval [ATOM :index] (dec index) state)
  nil)

(def style
  (comp style* first))

(defn suggest
  []
  (promesa/let [outside-hud (outside-hud?)]
    (when outside-hud
      (promesa/let [sentences (get-sentences)]
        (when-not (empty? sentences)
          (promesa/let [extmarks (set-sentence-extmarks sentences)
                        prompt (get-prompt)
                        contexts (get-contexts sentences)
                        buffer (.-buffer (:nvim @state))]
            (refresh-ranges sentences)
            (dorun (map (fn [context extmark]
                          (promesa/let [response (promesa/catch (.chat.completions.create groq
                                                                                          (clj->js {:messages [{:role "system"
                                                                                                                :content prompt}
                                                                                                               {:role "user"
                                                                                                                :content (str context)}]
                                                                                                    :model model
                                                                                                    :response_format response-format
                                                                                                    ;; We set reasoning_effort to "high" because links inside sentences tend to get stripped away otherwise.
                                                                                                    :reasoning_effort "high"}))
                                                                (fn [_]
                                                                  (all (map #(request "nvim_buf_del_extmark" 0 (% (:namespace @state)) extmark)
                                                                            [:pending-range :pending-sentence]))))]
                            (->> response
                                 parse-response
                                 (merge context
                                        {:buffer (.-id buffer)
                                         :extmark extmark})
                                 (call-function "HandleResult"))))
                        contexts
                        extmarks))))))))

(defn refresh-highlights
  []
  ;; We guard against nil (:nvim @state) because Neovim may trigger autocommands during startup.
  ;; Without this check, invoking methods like (.callFunction ...) on a null object throws a TypeError.
  (when (:nvim @state)
    (promesa/let [first-line (call-function "line" ["w0"])
                  last-line (call-function "line" ["w$"])
                  extmarks (request "nvim_buf_get_extmarks"
                                    0
                                    (:resolved-sentence (:namespace @state))
                                    [(dec first-line) 0]
                                    [(dec last-line) -1]
                                    {:details true
                                     :overlap true})
                  lines (.buffer.getLines (:nvim @state) (clj->js {:start (dec first-line)
                                                                   :end last-line}))
                  buffer (.-buffer (:nvim @state))]
      (dorun (map (fn [[id row col details]]
                    (request "nvim_buf_set_extmark"
                             0
                             (:resolved-sentence (:namespace @state))
                             row
                             col
                             (merge (select-keys details #{:end_col :end_row})
                                    {:hl_group (if (= row (:end_row details))
                                                 (let [current-text (slice (nth (js->clj lines) (- row (dec first-line))) col (:end_col details))
                                                       cache-entry (->> @state
                                                                        :cache
                                                                        ((-> buffer
                                                                             .-id
                                                                             str
                                                                             keyword))
                                                                        ((keyword (str id))))]
                                                   (cond (= current-text (:target-sentence cache-entry))
                                                         (if (:pass cache-entry)
                                                           pass-highlight
                                                           fail-highlight)
                                                         ((set (:suggestions cache-entry)) current-text)
                                                         pass-highlight
                                                         :else
                                                         unverified-highlight))
                                                 unverified-highlight)
                                     :id id})))
                  extmarks)))))

(defn main
  [plugin]
  (promesa/let [pending-range-namespace (.createNamespace (.-nvim plugin) "pending-range")
                pending-sentence-namespace (.createNamespace (.-nvim plugin) "pending-sentence")
                resolved-range-namespace (.createNamespace (.-nvim plugin) "resolved-range")
                resolved-sentence-namespace (.createNamespace (.-nvim plugin) "resolved-sentence")
                active-sentence-namespace (.createNamespace (.-nvim plugin) "active-sentence")
                buffer (.createBuffer (.-nvim plugin) false true)]
    (reset! state {:buffer buffer
                   :cache {}
                   :index 0
                   :namespace {:pending-range pending-range-namespace
                               :pending-sentence pending-sentence-namespace
                               :resolved-range  resolved-range-namespace
                               :resolved-sentence resolved-sentence-namespace
                               :active-sentence active-sentence-namespace}
                   :nvim (.-nvim plugin)}))
  (.registerAutocmd plugin "BufEnter" (fn []) (clj->js {:pattern "*"}))
  (.registerAutocmd plugin "CursorMoved" render-hud (clj->js {:pattern "*"
                                                              :sync true}))
  (.registerAutocmd plugin "TextChanged" refresh-highlights (clj->js {:pattern "*"
                                                                      :sync true}))
  (.registerAutocmd plugin "TextChangedI" refresh-highlights (clj->js {:pattern "*"
                                                                       :sync true}))
  (.registerAutocmd plugin "WinClosed" handle-closing (clj->js {:eval "expand('<amatch>')"
                                                                :pattern "*"
                                                                :sync true}))
  (.registerAutocmd plugin "WinResized" render-hud (clj->js {:pattern "*"
                                                             :sync true}))
  (.registerAutocmd plugin "WinScrolled" refresh-highlights (clj->js {:pattern "*"
                                                                      :sync true}))
  (.registerFunction plugin "Apply" apply-suggestion (clj->js {:sync true}))
  (.registerFunction plugin "HandleResult" handle-result (clj->js {:sync true}))
  (.registerFunction plugin "Style" style (clj->js {:sync true}))
  (.registerFunction plugin "Suggest" suggest (clj->js {:sync true})) ())