(ns main
  (:require [cljs-node-io.core :refer [slurp]]
            [com.rpl.specter :refer [AFTER-ELEM ALL ATOM MAP-VALS NONE nthpath pred= setval setval* transform]]
            [groq-sdk :refer [Groq]]
            [os :refer [homedir]]
            [path :refer [join]]
            [promesa.core :as promesa :refer [all]]))

(defonce state
  (atom {}))

(defn get-selection-bounds
  []
  (promesa/let [positions (all (map #(.callFunction (:nvim @state) "getpos" %) ["." "v"]))]
    (sort (map (comp vec
                     (partial map dec)
                     drop-last
                     rest
                     js->clj)
               positions))))

(defn get-sentences
  []
  (promesa/let [[start-pos end-pos] (get-selection-bounds)
                start-sentence (.callFunction (:nvim @state) "Get" (clj->js {:pos start-pos}))]
    (if (js->clj start-sentence)
      (promesa/let [end-sentence* (.callFunction (:nvim @state) "Get" (clj->js {:pos end-pos}))
                    end-sentence (if (js->clj end-sentence*)
                                   (js->clj end-sentence*)
                                   (.callFunction (:nvim @state) "Get" (clj->js {:offset -1
                                                                                 :pos end-pos})))]
        (if (= (js->clj start-sentence) (js->clj end-sentence))
          [(js->clj start-sentence)]
          (promesa/loop [sentences [(js->clj end-sentence)]]
            (promesa/let [previous-sentence (.callFunction (:nvim @state) "Get" (clj->js {:offset -1
                                                                                          :pos (drop-last (first sentences))}))]
              (if (= (js->clj start-sentence) (js->clj previous-sentence))
                (cons (js->clj start-sentence) sentences)
                (promesa/recur (cons (js->clj previous-sentence) sentences)))))))
      [])))

(defn style
  [index])

(defn prepend
  [sentences]
  (promesa/let [previous-sentence (.callFunction (:nvim @state) "Get" (clj->js {:offset -1
                                                                                :pos (drop-last (first sentences))}))]
    (cons (or (js->clj previous-sentence) [0 0 0]) sentences)))

(defn request
  [function & args]
  (.then (.request (:nvim @state) function (clj->js args))
         #(js->clj % :keywordize-keys true)))

(defn refresh-range
  [[start end]]
  (promesa/let [extmarks (request "nvim_buf_get_extmarks"
                                  0
                                  (:resolved-range (:namespace @state))
                                  start
                                  end
                                  {:overlap true})]
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
  (promesa/let [next-sentence (.callFunction (:nvim @state)
                                             "Get"
                                             (clj->js {:offset 1
                                                       :pos [(first (last sentences)) (llast sentences)]}))]
    (setval AFTER-ELEM
            (or (js->clj next-sentence) [(first (last sentences)) (llast sentences) (llast sentences)])
            sentences)))

(def get-contexts*
  (comp (partial map (comp str
                           (partial setval* [MAP-VALS (pred= "")] NONE)
                           (partial zipmap [:previous-sentence :target-sentence :next-sentence])))
        (partial partition 3 1)))

(defn get-contexts
  [sentences]
  (promesa/let [sentences* (prepend sentences)
                sentences* (append sentences*)
                lines (.buffer.getLines (:nvim @state) (clj->js {:start (ffirst sentences*)
                                                                 :end (inc (first (last sentences*)))}))]
    (get-contexts* (map (fn [[row start-col end-col]]
                          (subs (nth (js->clj lines) (- row (ffirst sentences*))) start-col end-col))
                        sentences*))))

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
   :maxLength 100
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
  (when-let [window (:window @state)]
    (setval [ATOM :window] NONE state)
    (.close (:hud window))))

(defn render-hud
  []
  (promesa/let [source-window (.-window (:nvim @state))
                cursor (.-cursor source-window)
                cursor* (transform (nthpath 0) dec (js->clj cursor))
                extmarks (request "nvim_buf_get_extmarks"
                                  0
                                  (:resolved-range (:namespace @state))
                                  cursor*
                                  cursor*
                                  {:overlap true})
                hud-buffer (:buffer @state)
                source-buffer (.-buffer (:nvim @state))]
    (if (empty? extmarks)
      (close-hud)
      (do (.setLines hud-buffer
                     (-> @state
                         :cache
                         ((-> source-buffer
                              .-id
                              str
                              keyword))
                         ((-> extmarks
                              ffirst
                              str
                              keyword))
                         format-lines
                         clj->js)
                     (clj->js {:start 0
                               :end -1}))
          (when-not (and (:window @state)
                         (->> @state
                              :window
                              :source
                              .-id
                              (= (.-id source-window))))
            (promesa/let [hud-window (.openWindow (:nvim @state) (:buffer @state) false (clj->js {:split "below"
                                                                                                  :style "minimal"}))]
              (setval [ATOM :window]
                      {:source source-window
                       :hud hud-window}
                      state)
              nil))))))

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
                    pending-range-extmarks (request "nvim_buf_get_extmarks"
                                                    (:buffer payload)
                                                    (:pending-range (:namespace @state))
                                                    (take 2 pending-range-extmark)
                                                    ((juxt :end_row :end_col) (last pending-range-extmark))
                                                    {:overlap true})
                    resolved-sentence-extmark (request "nvim_buf_set_extmark"
                                                       (:buffer payload)
                                                       (:resolved-sentence (:namespace @state))
                                                       (first pending-sentence-extmark)
                                                       (second pending-sentence-extmark)
                                                       (setval :hl_group
                                                               (if (:pass payload)
                                                                 "DiagnosticUnderlineOk"
                                                                 "DiagnosticUnderlineError")
                                                               (select-keys (last pending-sentence-extmark) #{:end_row :end_col})))]
        (setval [ATOM
                 :cache
                 (-> payload
                     :buffer
                     str
                     keyword)
                 (keyword (str resolved-sentence-extmark))]
                (select-keys payload #{:explanation :suggestions})
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

(defn suggest
  []
  (promesa/let [sentences (get-sentences)]
    (when-not (empty? sentences)
      (promesa/let [extmarks (set-sentence-extmarks sentences)
                    prompt (get-prompt)
                    contexts (get-contexts sentences)
                    buffer (.-buffer (:nvim @state))]
        (refresh-ranges sentences)
        (dorun (map (fn [context extmark]
                      (promesa/let [response (.chat.completions.create groq
                                                                       (clj->js {:messages [{:role "system"
                                                                                             :content prompt}
                                                                                            {:role "user"
                                                                                             :content context}]
                                                                                 :model model
                                                                                 :response_format response-format}))]
                        (->> response
                             parse-response
                             (merge {:extmark extmark
                                     :buffer (.-id buffer)})
                             clj->js
                             (.callFunction (:nvim @state) "HandleResult"))))
                    contexts
                    extmarks))))))

(defn handle-closing
  [id]
  (when-let [window (:window @state)]
    (setval [ATOM :window] NONE state)
    (when (->> window
               :source
               .-id
               (= (parse-long id)))
      (.catch (.close (:hud window))
              (fn [_]
                (.quit (:nvim @state)))))))

(defn main
  [plugin]
  (promesa/let [pending-range-namespace (.createNamespace (.-nvim plugin) "pending-range")
                pending-sentence-namespace (.createNamespace (.-nvim plugin) "pending-sentence")
                resolved-range-namespace (.createNamespace (.-nvim plugin) "resolved-range")
                resolved-sentence-namespace (.createNamespace (.-nvim plugin) "resolved-sentence")
                buffer (.createBuffer (.-nvim plugin) false true)]
    (reset! state {:buffer buffer
                   :cache {}
                   :index 0
                   :namespace {:pending-range pending-range-namespace
                               :pending-sentence pending-sentence-namespace
                               :resolved-range  resolved-range-namespace
                               :resolved-sentence resolved-sentence-namespace}
                   :nvim (.-nvim plugin)}))
  (.registerAutocmd plugin "WinClosed" handle-closing (clj->js {:eval "expand('<amatch>')"
                                                                :pattern "*"
                                                                :sync true}))
  (.registerFunction plugin "HandleResult" handle-result (clj->js {:sync true}))
  (.registerFunction plugin "Style" style (clj->js {:sync true}))
  (.registerFunction plugin "Suggest" suggest (clj->js {:sync true})))