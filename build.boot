(set-env! :dependencies   '[[adzerk/bootlaces "0.1.13" :scope "test"]
                            [clj-jgit "0.8.9" :exclusions [org.clojure/clojure]]
                            [com.fzakaria/slf4j-timbre "0.3.4"
                             :exclusions [org.clojure/clojure
                                          org.slf4j/slf4j-api]
                             :scope      "test"]
                            [degree9/boot-semver "1.4.4"
                             :exclusions [org.clojure/clojure]]
                            [org.clojure/clojure "1.9.0-alpha15"
                             :scope "provided"]
                            [robert/hooke "1.3.0" :scope "test"]]
          :resource-paths #{"src"})

(require '[adzerk.bootlaces :refer :all]
         '[penny-profit.boot-flow :as flow]
         '[robert.hooke :refer [add-hook]])

(task-options!
 pom  {:description "git-flow tasks for boot"
       :project     'penny-profit/boot-flow
       :scm         {:url "https://github.com/PennyProfit/boot-flow"}}
 push {:repo "deploy-clojars"})

(defn production-deploy [handler branch]
  (comp (build-jar) (push-release) (handler branch)))
(add-hook #'flow/production-deploy #'production-deploy)

(defn snapshot-deploy [handler branch]
  (comp (build-jar) (push-snapshot) (handler branch)))
(add-hook #'flow/snapshot-deploy #'snapshot-deploy)
