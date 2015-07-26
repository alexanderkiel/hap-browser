(defproject org.clojars.akiel/hap-browser "0.1-SNAPSHOT"
  :description "Generic Hypermedia Application Protocol (HAP) UI."
  :url "ttps://github.com/alexanderkiel/hap-browser"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3211"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [http-kit "2.1.18"]
                 [ring/ring-core "1.3.2"]
                 [bidi "1.18.10" :exclusions [ring/ring-core]]
                 [compojure "1.3.3"]
                 [org.omcljs/om "0.8.8" :scope "provided"]
                 [prismatic/plumbing "0.4.3"]
                 [prismatic/schema "0.4.2"]
                 [prismatic/om-tools "0.3.11" :scope "provided"]
                 [org.clojars.akiel/hap-client-clj "0.1"
                  :exclusions [com.cognitect/transit-clj]]]

  :profiles {:dev
             {:source-paths ["dev"]
              :dependencies [[org.clojure/tools.namespace "0.2.4"]]
              :global-vars {*print-length* 20}

              :cljsbuild
              {:builds [{:id "dev"
                         :source-paths ["src/cljs"]
                         :figwheel true
                         :compiler
                         {:output-to "resources/public/js/hap-browser-dev.js"
                          :output-dir "resources/public/js/out-dev"
                          :optimizations :none
                          :source-map true}}]}}

             :production
             {:hooks [leiningen.cljsbuild]

              :cljsbuild
              {:builds [{:source-paths ["src/cljs"]
                         :compiler
                         {:output-to "resources/public/js/hap-browser.js"
                          :optimizations :advanced
                          :pretty-print false}}]}}}

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.3.3" :exclusions [org.codehaus.plexus/plexus-utils
                                                org.clojure/clojure]]]

  :source-paths ["src/clj" "src/cljs"]
  :resource-paths ["resources"]
  :clean-targets ^{:protect false} ["target" "out" "repl" "resources/public/js"]

  :repl-options {:welcome (do
                            (println "   Docs: (doc function-name-here)")
                            (println "         (find-doc \"part-of-name-here\")")
                            (println "   Exit: Control+D or (exit) or (quit)")
                            (println "  Start: (startup)")
                            (println "Restart: (reset)"))}

  :figwheel {:css-dirs ["resources/public/css"]

             :server-port 5100
             :ring-handler hap-browser.app/app-dev})
