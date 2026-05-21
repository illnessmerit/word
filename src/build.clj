(ns build
  (:require [babashka.fs :refer [create-dirs expand-home file]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]))

(def plugins-directory
  (expand-home "~/.config/nvim/lua/plugins/"))

(def plugin-file
  (file plugins-directory "devenv.lua"))

(defn configure-plugin
  []
  (create-dirs plugins-directory)
  (->> "DEVENV_ROOT"
       System/getenv
       (string/replace (slurp "template.lua") "{{dir}}")
       (spit plugin-file)))

(defn build
  {:shadow.build/stage :flush}
  [state]
  (println "Configuring plugin.")
  (configure-plugin)
  (println "Updating Neovim remote plugins.")
  (sh "nvim" "--headless" "+Lazy load word" "+UpdateRemotePlugins" "+qa!")
  state)