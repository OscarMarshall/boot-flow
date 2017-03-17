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
(defn finish-check [_] identity)
(defn hotfix-finish [_] identity)
(defn hotfix-resume [_] identity)
(defn hotfix-start [_] identity)
(defn master-deploy [_] identity)
(defn release-finish [_] identity)
(defn release-resume [_] identity)
(defn release-start [_] identity)
(defn version-bump [_] identity)

(def ^:private current-version (atom nil))

(defn- clean? [repo]
  (empty? (reduce set/union (vals (git/git-status repo)))))

(defn- dirty? [repo]
  (not (clean? repo)))

(defn- ensure-clean [repo]
  (when (dirty? repo)
    (throw (Exception. "Please commit or stash your changes"))))

(defn- git-merge! [^Git repo branch]
  (let [current-branch (git/git-branch-current repo)]
    (.. repo
        merge
        (include ^ObjectId (giti/resolve-object branch repo))
        (setFastForward MergeCommand$FastForwardMode/NO_FF)
        (setMessage (str "Merge branch '" branch "' into " current-branch))
        call)))

(defn- list-branches
  ([repo type singular]
   (let [branches
         (into
          #{}
          (comp (map (fn [^Ref ref]
                       (nth (re-matches
                             (if type
                               (re-pattern (format "refs/heads/(%s/.*)" type))
                               #"refs/heads/(.*)")
                             (.getName ref))
                            1)))
                (filter some?))
          (git/git-branch-list repo))]
     (cond
       (not singular)          branches
       (<= (count branches) 1) (first branches)
       :else                   (throw (ex-info (format "More than one %s branch"
                                                       type)
                                               branches)))))
  ([repo type] (list-branches repo type false))
  ([repo] (list-branches repo nil)))

(defn- incorporate-changes!
  ([repo branch destination]
   (boot/with-pass-thru _
     (git/git-checkout repo destination)
     (git-merge! repo branch)
     (git/git-branch-delete repo [branch])))
  ([repo branch] (incorporate-changes! repo branch "develop")))

(defn- make-production! [^Git repo branch]
  (boot/with-pass-thru _
    (let [[_ name] (re-matches #"(?:release|hotfix)/(.*)" branch)]
      (git/git-checkout repo "master")
      (git-merge! repo branch)
      (.. repo tag (setName name) call))))

(defn- read-version! []
  (reset! current-version
          (into [] (map read-string) (string/split (get-version) #"\."))))

(defn- version-string [] (string/join "." @current-version))

(defn- major ([] (nth @current-version 0)) ([_] (major)))

(defn- minor ([] (nth @current-version 1)) ([_] (minor)))

(defn- patch ([] (nth @current-version 2)) ([_] (patch)))

(defn- bump-major! []
  (read-version!)
  (swap! current-version (fn [[x _ _]] [(inc x) 0 0])))

(defn- bump-minor! []
  (read-version!)
  (swap! current-version (fn [[x y _]] [x (inc y) 0])))

(defn- bump-patch! []
  (read-version!)
  (swap! current-version (fn [[x y z]] [x y (inc z)])))

(defn- commit-version! [repo]
  (comp (version :major `major, :minor `minor, :patch `patch)
        (boot/with-pass-thru _
          (git/git-add repo "version.properties")
          (version-bump (git/git-branch-current repo))
          (git/git-commit repo (str "Bump version to " (version-string))))))

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
        (let [features (list-branches repo "feature")
              branch   (cond
                         name                   (str "feature/" name)
                         (= (count features) 1) (first features))
              name     (or name (nth (re-matches #"feature/(.*)" branch) 1))]
          (((cond
              (nil? branch)
              (throw (Exception. "Please specify feature name"))

              (contains? features branch)
              (do (util/info "Resuming feature: %s...%n" name)
                  (git/git-checkout repo branch)
                  (feature-resume branch))

              :else
              (do (util/info "Starting feature: %s...%n" name)
                  (git/git-checkout repo branch true false "develop")
                  (feature-start branch)))
            handler)
           fileset))))))

(deftask hotfix []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [hotfix (list-branches repo "hotfix" true)]
          (((if hotfix
              (let [ver (nth (re-matches #"hotfix/(.*)" hotfix) 1)]
                (util/info "Resuming hotfix: %s...%n" ver)
                (git/git-checkout repo hotfix)
                (hotfix-resume hotfix))
              (let [_      (git/git-checkout repo "master")
                    _      (bump-patch!)
                    ver    (version-string)
                    branch (str "hotfix/" ver)]
                (util/info "Starting hotfix: %s...%n" ver)
                (git/git-checkout repo branch true false "master")
                (comp (commit-version! repo) (hotfix-start branch))))
            handler)
           fileset))))))

(deftask release [t type TYPE kw "either :major or :minor"]
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [release (list-branches repo "release" true)]
          (((if release
              (let [ver (nth (re-matches #"release/(.*)" release) 1)]
                (util/info "Resuming release: %s...%n" ver)
                (git/git-checkout repo release)
                (release-resume release))
              (let [_      (git/git-checkout repo "develop")
                    _      (case type
                             :major (bump-major!)
                             :minor (bump-minor!))
                    ver    (version-string)
                    branch (str "release/" ver)]
                (util/info "Starting release: %s...%n" ver)
                (git/git-checkout repo branch true false "develop")
                (comp (commit-version! repo) (release-start branch))))
            handler)
           fileset))))))

(deftask finish []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branch        (git/git-branch-current repo)
              [_ type name] (re-matches #"(feature|hotfix|release)/(.*)"
                                        branch)]
          (util/info "Finishing %s: %s...%n" type name)
          (((comp (finish-check branch)
                  (case type
                    "feature" (comp (incorporate-changes! repo branch)
                                    (feature-finish branch))
                    "hotfix"  (comp (make-production! repo branch)
                                    (master-deploy branch)
                                    (incorporate-changes!
                                     repo
                                     branch
                                     (or (list-branches repo "release" true)
                                         "develop"))
                                    (hotfix-finish branch))
                    "release" (comp (make-production! repo branch)
                                    (master-deploy branch)
                                    (incorporate-changes! repo branch)
                                    (release-finish branch))))
            handler)
           fileset))))))
