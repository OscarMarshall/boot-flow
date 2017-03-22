(set-env! :dependencies   '[[adzerk/bootlaces "0.1.13"]
                            [clj-jgit "0.8.9"]
                            [degree9/boot-semver "1.4.4"
                             :exclusions [org.clojure/clojure]]
                            [org.clojure/clojure "1.9.0-alpha14"
                             :scope "provided"]
                            [robert/hooke "1.3.0"]]
          :resource-paths #{"src"})

(require '[adzerk.bootlaces :refer :all]
         '[penny-profit.boot-flow :as flow]
         '[robert.hooke :refer [add-hook]])

(task-options! pom  {:project 'penny-profit/boot-flow}
               push {:repo "deploy-clojars"})

(add-hook #'flow/master-deploy (fn [handler _]
                                 (comp (build-jar) (push-release) handler)))

(add-hook #'flow/snapshot-deploy (fn [handler _]
                                   (comp (build-jar) (push-snapshot) handler)))
