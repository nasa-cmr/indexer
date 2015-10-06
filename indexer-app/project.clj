(defproject nasa-cmr/cmr-indexer-app "0.1.0-SNAPSHOT"
  :description "This is the indexer application for the CMR. It is responsible for indexing modified data into Elasticsearch."
  :url "***REMOVED***projects/CMR/repos/cmr/browse/indexer-app"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [nasa-cmr/cmr-umm-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-transmit-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-acl-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-message-queue-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-common-app-lib "0.1.0-SNAPSHOT"]
                 [compojure "1.4.0"]
                 [ring/ring-core "1.4.0" :exclusions [clj-time]]
                 [ring/ring-json "0.4.0"]
                 [nasa-cmr/cmr-elastic-utils-lib "0.1.0-SNAPSHOT"]]
  :plugins [[lein-test-out "0.3.1"]]
  :repl-options {:init-ns user}

  :jvm-opts  []

  :profiles
  {:dev {:dependencies [[ring-mock "0.1.5"]
                        [org.clojure/tools.namespace "0.2.11"]
                        [org.clojars.gjahad/debug-repl "0.3.3"]]
         :source-paths ["src" "dev" "test"]}
   :uberjar {:main cmr.indexer.runner
             :aot :all}}
  :aliases {;; Prints out documentation on configuration environment variables.
            "env-config-docs" ["exec" "-ep" "(do (use 'cmr.common.config) (print-all-configs-docs))"]})


