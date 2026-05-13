(ns main
  (:require [cljs-node-io.core :refer [slurp]]
            [com.rpl.specter :refer [AFTER-ELEM ALL MAP-VALS NONE pred= setval setval* transform]]
            [cljs.core.async :refer [chan]]
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

(defn set-sentence-extmark
  [[row start-col end-col]]
  (.request (:nvim @state) "nvim_buf_set_extmark" (clj->js [0
                                                            (:sentence-namespace @state)
                                                            row
                                                            start-col
                                                            {:end_col end-col
                                                             :end_row row
                                                             :hl_group "DiagnosticUnderlineWarn"}])))

(defn set-sentence-extmarks
  [sentences]
  (all (map set-sentence-extmark sentences)))

(defn prepend
  [sentences]
  (promesa/let [previous-sentence (.callFunction (:nvim @state) "Get" (clj->js {:offset -1
                                                                                :pos (drop-last (first sentences))}))]
    (cons (or (js->clj previous-sentence) [0 0 0]) sentences)))

(defn get-range-bounds
  [sentences]
  (promesa/let [sentences* (prepend sentences)]
    (all (transform [ALL ALL] (juxt first last) (partition 2 1 sentences*)))))

(defn set-range-extmark
  [[start end]]
  (.request (:nvim @state) "nvim_buf_set_extmark" (clj->js [0
                                                            (:range-namespace @state)
                                                            (first start)
                                                            (last start)
                                                            {:end_col (last end)
                                                             :end_row (first end)}])))

(def set-range-extmarks
  (comp all
        (partial map set-range-extmark)))

(def llast
  (comp last last))

(defn append
  [sentences]
  (promesa/let [next-sentence (.callFunction (:nvim @state) "Get" (clj->js {:offset 1
                                                                            :pos [(first (last sentences)) (llast sentences)]}))]
    (setval AFTER-ELEM (or (js->clj next-sentence) [(first (last sentences)) (llast sentences) (llast sentences)]) sentences)))

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

(defn suggest
  []
  (promesa/let [sentences (get-sentences)]
    (when-not (empty? sentences)
      (promesa/let [range-bounds (get-range-bounds sentences)
                    range-marks (set-range-extmarks range-bounds)
                    sentence-marks (set-sentence-extmarks sentences)
                    prompt (get-prompt)
                    contexts (get-contexts sentences)]
        (run! #(promesa/let [response (.chat.completions.create groq (clj->js {:messages [{:role "system"
                                                                                           :content prompt}
                                                                                          {:role "user"
                                                                                           :content %}]
                                                                               :model model
                                                                               :response_format response-format}))]
                 (parse-response response))
              contexts)))))

(defn main
  [plugin]
  (promesa/let [range-namespace (.createNamespace (.-nvim plugin) "range")
                sentence-namespace (.createNamespace (.-nvim plugin) "corge")]
    (reset! state {:nvim (.-nvim plugin)
                   :range-namespace range-namespace
                   :sentence-namespace sentence-namespace
                   :index 0}))
  (.registerFunction plugin "Style" style (clj->js {:sync true}))
  (.registerFunction plugin "Suggest" suggest (clj->js {:sync true})))