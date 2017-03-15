(ns penny-profit.boot-flow
  (:require [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as gitq]))

(defn list-branches [repo]
  (into #{}
        (comp (map (fn [^Ref ref]
                     (nth (re-matches #"refs/heads/(.*)"
                                      (.getName ref))
                          1)))
              (filter some?))
        (git/git-branch-list repo)))

(deftask init []
  (boot/with-pass-thru _
    (let [repo     (try (git/load-repo ".")
                        (catch FileNotFoundException _
                          (git/git-init)))
          _        (when (empty? (gitq/rev-list repo))
                     (git/git-commit repo "Initial commit"))
          branches (list-branches repo)]
      (when-not (contains? branches "master")
        (git/git-branch-create repo "master"))
      (when-not (contains? branches "develop")
        (git/git-branch-create repo "develop")))))
