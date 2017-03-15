(ns penny-profit.boot-flow
  (:require [boot.core :as boot, :refer [deftask]]
            [clj-jgit.internal :as giti]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as gitq]
            [clojure.set :as set])
  (:import (java.io FileNotFoundException)
           (org.eclipse.jgit.api MergeCommand MergeCommand$FastForwardMode)
           (org.eclipse.jgit.lib ObjectId Ref)))

(set! *warn-on-reflection* true)

(defn feature-finish [_] identity)
(defn feature-start [_] identity)
(defn feature-switch [_] identity)

(defn clean? [repo]
  (empty? (reduce set/union (vals (git/git-status repo)))))

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

(deftask feature [n name NAME str "feature to switch to"]
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (if (clean? repo)
          (let [branches (list-branches repo)
                features (into #{}
                               (filter #(re-matches #"feature/.*" %))
                               branches)
                branch   (cond
                           name                   (str "feature/" name)
                           (= (count features) 1) (first features))]
            (cond
              (nil? branch)
              (throw (Exception. "Please specify feature name"))

              (contains? branches branch)
              (do (git/git-checkout repo branch)
                  (((feature-start branch) handler) fileset))

              :else
              (do (git/git-checkout repo branch true false "develop")
                  (((feature-switch branch) handler) fileset))))
          (throw (Exception. "Please commit or stash your changes")))))))

(deftask finish []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (if (clean? repo)
          (let [branch (git/git-branch-current repo)]
            (case (nth (re-matches #"(feature)/.*" branch) 1)
              "feature"
              (do
                (git/git-checkout repo "develop")
                (.. repo
                    merge
                    (include ^ObjectId (giti/resolve-object branch repo))
                    (setFastForward MergeCommand$FastForwardMode/NO_FF)
                    (setMessage (str "Merge branch '" branch "' into develop"))
                    call)
                (git/git-branch-delete repo [branch])
                (((feature-finish branch) handler) fileset))))
          (throw (Exception. "Please commit or stash your changes")))))))
