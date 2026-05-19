(ns build
  (:require [clojure.java.shell :refer [sh]]))

(defn build
  {:shadow.build/stage :flush}
  [state]
  (println "Updating Neovim remote plugins.")
  (sh "nvim" "--headless" "+Lazy load word" "+UpdateRemotePlugins" "+qa!")
  state)