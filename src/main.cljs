(ns main
  (:require [cljs-node-io.core :refer [slurp]]
            [com.rpl.specter :refer [AFTER-ELEM MAP-VALS NONE pred= setval setval*]]
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

(defn set-range-extmark
  [[previous-sentence target-sentence]]
  (request "nvim_buf_set_extmark"
           0
           (:range-namespace @state)
           (first previous-sentence)
           (last previous-sentence)
           {:end_col (last target-sentence)
            :end_row (first target-sentence)}))

(defn set-range-extmarks
  [sentences]
  (promesa/let [sentences* (prepend sentences)]
    (all (map set-range-extmark (partition 2 1 sentences*)))))

(defn set-sentence-extmark
  [[row start-col end-col]]
  (request "nvim_buf_set_extmark"
           0
           (:sentence-namespace @state)
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

(defn handle*
  [payload]
  (promesa/let [range-extmark (request "nvim_buf_get_extmark_by_id"
                                       (:buffer payload)
                                       (:range-namespace @state)
                                       (:extmark payload)
                                       {:details true})]
    (when-not (empty? range-extmark)
      (promesa/let [sentence-extmark (request "nvim_buf_get_extmark_by_id"
                                              (:buffer payload)
                                              (:sentence-namespace @state)
                                              (:extmark payload)
                                              {:details true})
                    overlapping-extmarks (request "nvim_buf_get_extmarks"
                                                  (:buffer payload)
                                                  (:range-namespace @state)
                                                  (take 2 range-extmark)
                                                  ((juxt :end_row :end_col) (last range-extmark))
                                                  {:overlap true})]
        (all (mapcat (comp (apply juxt (map #(partial request "nvim_buf_del_extmark" (:buffer payload) %)
                                            ((juxt :range-namespace :sentence-namespace) @state)))
                           first)
                     overlapping-extmarks))))))

(def handle
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
        (set-range-extmarks sentences)
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
                             (.callFunction (:nvim @state) "Handle"))))
                    contexts
                    extmarks))))))

(defn main
  [plugin]
  (promesa/let [range-namespace (.createNamespace (.-nvim plugin) "range")
                sentence-namespace (.createNamespace (.-nvim plugin) "sentence")]
    (reset! state {:index 0
                   :nvim (.-nvim plugin)
                   :range-namespace range-namespace
                   :sentence-namespace sentence-namespace}))
  (.registerFunction plugin "Style" style (clj->js {:sync true}))
  (.registerFunction plugin "Suggest" suggest (clj->js {:sync true}))
  (.registerFunction plugin "Handle" handle (clj->js {:sync true})))