(set-env! :dependencies   '[[adzerk/bootlaces "0.1.13" :scope "test"]
                            [clj-jgit "0.8.9"]
                            [degree9/boot-semver "1.4.4"
                             :exclusions [org.clojure/clojure]]
                            [org.clojure/clojure "1.9.0-alpha14"
                             :scope "provided"]
                            [robert/hooke "1.3.0" :scope "test"]]
          :resource-paths #{"src"})

(require '[adzerk.bootlaces :refer :all]
         '[penny-profit.boot-flow :as flow]
         '[robert.hooke :refer [add-hook]])

(task-options! pom  {:project 'penny-profit/boot-flow}
               push {:repo "deploy-clojars"})

(defn production-deploy [handler branch]
  (comp (build-jar) (push-release) (handler branch)))
(add-hook #'flow/production-deploy #'production-deploy)

(defn snapshot-deploy [handler branch]
  (comp (build-jar) (push-snapshot) (handler branch)))
(add-hook #'flow/snapshot-deploy #'snapshot-deploy)
