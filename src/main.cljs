(ns main
  (:require [cljs-node-io.core :refer [slurp]]
            [com.rpl.specter :refer [AFTER-ELEM MAP-VALS NONE pred= setval setval*]]
            [os :refer [homedir]]
            [path :refer [join]]
            [promesa.core :as promesa :refer [all]]))

(def api-key
  (-> (homedir)
      (join ".config/word/cerebras")
      slurp))

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
  [start-pos end-pos]
  (promesa/let [start-sentence (.callFunction (:nvim @state) "Get" (clj->js {:pos start-pos}))]
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
                                                            (:namespace @state)
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

(defn set-range-extmark
  [[previous-sentence target-sentence]]
  (.request (:nvim @state) "nvim_buf_set_extmark" (clj->js [0
                                                            (:namespace @state)
                                                            (first previous-sentence)
                                                            (last previous-sentence)
                                                            {:end_col (last target-sentence)
                                                             :end_row (first target-sentence)}])))

(defn set-range-extmarks
  [sentences]
  (promesa/let [sentences* (prepend sentences)]
    (all (map set-range-extmark (partition 2 1 sentences*)))))

(def llast
  (comp last last))

(defn append
  [sentences]
  (promesa/let [next-sentence (.callFunction (:nvim @state) "Get" (clj->js {:offset 1
                                                                            :pos [(first (last sentences)) (llast sentences)]}))]
    (setval AFTER-ELEM (or (js->clj next-sentence) [(first (last sentences)) (llast sentences) (llast sentences)]) sentences)))

(def create-context*
  (comp (partial map (comp (partial setval* [MAP-VALS (pred= "")] NONE)
                           (partial zipmap [:previous :target :next])))
        (partial partition 3 1)))

(defn create-context
  [sentences]
  (promesa/let [sentences* (prepend sentences)
                sentences* (append sentences*)
                lines (.buffer.getLines (:nvim @state) (clj->js {:start (ffirst sentences*)
                                                                 :end (inc (first (last sentences*)))}))]
    (create-context* (map (fn [[row start-col end-col]]
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

(defn suggest
  []
  (promesa/let [bounds (get-selection-bounds)
                sentences (apply get-sentences bounds)]
    (when-not (empty? sentences)
      (promesa/let [range-marks (set-range-extmarks sentences)
                    sentence-marks (set-sentence-extmarks sentences)]))))

(defn main
  [plugin]
  (promesa/let [namespace (.createNamespace (.-nvim plugin) "word")]
    (reset! state {:nvim (.-nvim plugin)
                   :namespace namespace
                   :index 0}))
  (.registerFunction plugin "Style" style)
  (.registerFunction plugin "Suggest" suggest))