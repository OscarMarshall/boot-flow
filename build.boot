(set-env! :dependencies '[[clj-jgit "0.8.9"]
                          [degree9/boot-semver "1.4.4"
                           :exclusions [org.clojure/clojure]]
                          [org.clojure/clojure "1.9.0-alpha14"
                           :scope "provided"]]
          :source-paths #{"src"})

(require '[penny-profit.boot-flow :as flow])
