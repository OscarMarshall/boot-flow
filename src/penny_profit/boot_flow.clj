(ns penny-profit.boot-flow
  (:require [boot.core :as boot, :refer [deftask]]
            [boot.util :as util]
            [clj-jgit.internal :as giti]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as gitq]
            [clojure.set :as set]
            [clojure.string :as string]
            [degree9.boot-semver :refer [get-version version]])
  (:import (java.io FileNotFoundException)
           (org.eclipse.jgit.api Git MergeCommand$FastForwardMode)
           (org.eclipse.jgit.lib ObjectId Ref)))

(set! *warn-on-reflection* true)

(defn feature-finish [_] identity)
(defn feature-resume [_] identity)
(defn feature-start [_] identity)
(defn hotfix-finish [_] identity)
(defn hotfix-resume [_] identity)
(defn hotfix-start [_] identity)
(defn master-deploy [_] identity)
(defn release-finish [_] identity)
(defn release-resume [_] identity)
(defn release-start [_] identity)
(defn version-bump [_] identity)

(def current-version (atom nil))

(defn clean? [repo]
  (empty? (reduce set/union (vals (git/git-status repo)))))

(defn dirty? [repo]
  (not (clean? repo)))

(defn ensure-clean [repo]
  (when (dirty? repo)
    (throw (Exception. "Please commit or stash your changes"))))

(defn git-merge! [^Git repo branch]
  (let [current-branch (git/git-branch-current repo)]
    (.. repo
        merge
        (include ^ObjectId (giti/resolve-object branch repo))
        (setFastForward MergeCommand$FastForwardMode/NO_FF)
        (setMessage (str "Merge branch '" branch "' into " current-branch))
        call)))

(defn list-branches [repo]
  (into #{}
        (comp (map (fn [^Ref ref]
                     (nth (re-matches #"refs/heads/(.*)"
                                      (.getName ref))
                          1)))
              (filter some?))
        (git/git-branch-list repo)))

(defn read-version!
  ([] (read-version! (get-version)))
  ([version]
   (reset! current-version
           (into [] (map read-string) (string/split version #"\.")))))

(defn version-string [] (string/join "." @current-version))

(defn major ([] (nth @current-version 0)) ([_] (major)))

(defn minor ([] (nth @current-version 1)) ([_] (minor)))

(defn patch ([] (nth @current-version 2)) ([_] (patch)))

(defn bump-major! [] (swap! current-version (fn [[x _ _]] [(inc x) 0 0])))

(defn bump-minor! [] (swap! current-version (fn [[x y _]] [x (inc y) 0])))

(defn bump-patch! [] (swap! current-version (fn [[x y z]] [x y (inc z)])))

(deftask init []
  (boot/with-pass-thru _
    (let [repo     (try (git/load-repo ".")
                        (catch FileNotFoundException _
                          (util/info "Initializing Git repo...%n")
                          (git/git-init)))
          _        (when (empty? (gitq/rev-list repo))
                     (util/info "Creating initial commit...%n")
                     (git/git-commit repo "Initial commit"))
          branches (list-branches repo)]
      (when-not (contains? branches "master")
        (util/info "Creating master branch...")
        (git/git-branch-create repo "master"))
      (when-not (contains? branches "develop")
        (util/info "Creating develop branch...")
        (git/git-branch-create repo "develop")))))

(deftask feature [n name NAME str "feature to switch to"]
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branches (list-branches repo)
              features (into #{}
                             (filter #(re-matches #"feature/.*" %))
                             branches)
              branch   (cond
                         name                   (str "feature/" name)
                         (= (count features) 1) (first features))
              name     (or name (nth (re-matches #"feature/(.*)" branch) 1))]
          (cond
            (nil? branch)
            (throw (Exception. "Please specify feature name"))

            (contains? branches branch)
            (do (util/info "Resuming feature: %s...%n" name)
                (git/git-checkout repo branch)
                (((feature-resume branch) handler) fileset))

            :else
            (do (util/info "Starting feature: %s...%n" name)
                (git/git-checkout repo branch true false "develop")
                (((feature-start branch) handler) fileset))))))))

(deftask hotfix []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branches (list-branches repo)
              hotfixes (into #{}
                             (filter #(re-matches #"hotfix/.*" %))
                             branches)]
          (case (count hotfixes)
            0 (do (read-version!)
                  (bump-patch!)
                  (let [ver    (version-string)
                        branch (str "hotfix/" ver)]
                    (util/info "Starting hotfix: %s...%n" ver)
                    (git/git-checkout repo branch true false "master")
                    (((comp (version :major `major
                                     :minor `minor
                                     :patch `patch)
                            (version-bump branch))
                      (fn [fileset]
                        (git/git-add repo "version.properties")
                        (git/git-commit repo (str "Bump version to " ver))
                        (((hotfix-start branch) handler) fileset)))
                     fileset)))
            1 (let [branch (first hotfixes)
                    ver    (nth (re-matches #"hotfix/(.*)" branch) 1)]
                (util/info "Resuming hotfix: %s...%n" ver)
                (git/git-checkout repo branch)
                (((hotfix-resume branch) handler) fileset))
            (throw (Exception. "More than one hotfix branch"))))))))

(deftask release [t type TYPE kw "either :major or :minor"]
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branches (list-branches repo)
              releases (into #{}
                             (filter #(re-matches #"release/.*" %))
                             branches)]
          (case (count releases)
            0 (do (read-version!)
                  (case type
                    :major (bump-major!)
                    :minor (bump-minor!))
                  (let [ver    (version-string)
                        branch (str "release/" ver)]
                    (util/info "Starting release: %s...%n" ver)
                    (git/git-checkout repo branch true false "develop")
                    (((comp (version :major `major
                                     :minor `minor
                                     :patch `patch)
                            (version-bump branch))
                      (fn [fileset]
                        (git/git-add repo "version.properties")
                        (git/git-commit repo (str "Bump version to " ver))
                        (((release-start branch) handler) fileset)))
                     fileset)))
            1 (let [branch (first releases)
                    ver    (nth (re-matches #"release/(.*)" branch) 1)]
                (util/info "Resuming release: %s...%n" ver)
                (git/git-checkout repo branch)
                (((release-resume branch) handler) fileset))
            (throw (Exception. "More than one release branch"))))))))

(deftask finish []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branch        (git/git-branch-current repo)
              [_ type name] (re-matches #"(feature|release)/(.*)" branch)]
          (util/info "Finishing %s: %s...%n" type name)
          (case type
            "feature"
            (do (git/git-checkout repo "develop")
                (git-merge! repo branch)
                (git/git-branch-delete repo [branch])
                (((feature-finish branch) handler) fileset))

            "hotfix"
            (do (git/git-checkout repo "master")
                (git-merge! repo branch)
                (.. repo tag (setName name) call)
                (((master-deploy branch)
                  (fn [fileset]
                    (let [branches (list-branches repo)
                          release  (first (filter #(re-matches #"release" %)
                                                  branches))]
                      (git/git-checkout repo (or release "develop"))
                      (git-merge! repo branch)
                      (git/git-branch-delete repo [branch])
                      (((hotfix-finish branch) handler) fileset))))))

            "release"
            (do (git/git-checkout repo "master")
                (git-merge! repo branch)
                (.. repo tag (setName name) call)
                (((master-deploy branch)
                  (fn [fileset]
                    (git/git-checkout repo "develop")
                    (git-merge! repo branch)
                    (git/git-branch-delete repo [branch])
                    (((release-finish branch) handler) fileset)))
                 fileset))))))))
