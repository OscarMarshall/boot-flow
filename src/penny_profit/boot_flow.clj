(ns penny-profit.boot-flow
  (:require [boot.core :as boot, :refer [deftask]]
            [boot.util :as util]
            [clj-jgit.internal :as giti]
            [clj-jgit.porcelain :as git]
            [clj-jgit.querying :as gitq]
            [clojure.set :as set]
            [clojure.string :as string]
            [degree9.boot-semver :as semver :refer [get-version version]])
  (:import (java.io FileNotFoundException)
           (org.eclipse.jgit.api Git MergeCommand$FastForwardMode MergeResult)
           (org.eclipse.jgit.lib ObjectId Ref)
           (org.eclipse.jgit.revwalk RevCommit)))

(set! *warn-on-reflection* true)

(defn code-check [_] identity)
(defn feature-cancel [_] identity)
(defn feature-finish [_] identity)
(defn feature-resume [_] identity)
(defn feature-start [_] identity)
(defn hotfix-cancel [_] identity)
(defn hotfix-finish [_] identity)
(defn hotfix-resume [_] identity)
(defn hotfix-start [_] identity)
(defn production-deploy [_] identity)
(defn release-cancel [_] identity)
(defn release-finish [_] identity)
(defn release-resume [_] identity)
(defn release-start [_] identity)
(defn snapshot-deploy [_] identity)
(defn version-bump [_] identity)

(def ^:private current-version (atom nil))

(def working-branch-re #"(feature|hotfix|release)/(.*)")

(defn- english-list [xs]
  (let [xs-count (count xs)]
    (string/join (if (> xs-count 2) ", " " ")
                 (cond-> xs
                   (> xs-count 1) (update (dec xs-count) #(str "and " %))))))

(defn- clean? [repo]
  (empty? (reduce set/union (vals (git/git-status repo)))))

(defn- dirty? [repo]
  (not (clean? repo)))

(defn- ensure-clean [repo]
  (when (dirty? repo)
    (throw (Exception. "Please commit or stash your changes"))))

(defn- merge-conflicts [^MergeResult result]
  (into {} (.getConflicts result)))

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
                                               {:branches branches})))))
  ([repo type] (list-branches repo type false))
  ([repo] (list-branches repo nil)))

(defn- delete-branch! [repo branch]
  (boot/with-pass-thru _
    (util/dbug "Deleting %s...%n" branch)
    (git/git-branch-delete repo [branch])))

(defn- incorporate-changes!
  ([repo branch destination]
   (boot/with-pass-thru _
     (util/dbug "Merging %s into %s...%n" branch destination)
     (git/git-checkout repo destination)
     (let [merge-result (git-merge! repo branch)]
       (when-let [conflicts (keys (merge-conflicts merge-result))]
         (throw (ex-info (str "Conflicts found in " (english-list conflicts))
                         {:conflicts conflicts}))))))
  ([repo branch] (incorporate-changes! repo branch "develop")))

(defn- make-production! [^Git repo branch]
  (boot/with-pass-thru _
    (util/dbug "Merging %s into master...%n" branch)
    (let [[_ name] (re-matches #"(?:release|hotfix)/(.*)" branch)]
      (git/git-checkout repo "master")
      (git-merge! repo branch))))

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
        (util/info "Creating master branch...%n")
        (git/git-branch-create repo "master"))
      (when-not (contains? branches "develop")
        (util/info "Creating develop branch...%n")
        (git/git-branch-create repo "develop")))))

(deftask cancel []
  (fn [handler]
    (fn [fileset]
      (let [repo   (git/load-repo ".")
            branch (git/git-branch-current repo)]
        (ensure-clean repo)
        (if-let [[_ type name] (re-matches working-branch-re branch)]
          (do (util/info "Canceling %s: %s...%n" type name)
              (git/git-checkout repo "develop")
              (git/git-branch-delete repo [branch] true)
              ((((case type
                   "feature" feature-cancel
                   "hotfix"  hotfix-cancel
                   "release" release-cancel)
                 branch)
                handler)
               fileset))
          (throw (ex-info (str "Can't cancel branch: " branch)
                          {:branch branch})))))))

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
                    _      ((case type
                              :major       bump-major!
                              (:minor nil) bump-minor!))
                    ver    (version-string)
                    branch (str "release/" ver)]
                (util/info "Starting release: %s...%n" ver)
                (git/git-checkout repo branch true false "develop")
                (comp (commit-version! repo) (release-start branch))))
            handler)
           fileset))))))

(deftask snapshot []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branch (git/git-branch-current repo)]
          (if-let [[_ type ver] (re-matches #"(hotfix|release)/(.*)" branch)]
            (do (util/info "Snapshotting %s: %s...%n" type ver)
                (read-version!)
                (((comp (code-check branch)
                        (version :develop     true
                                 :major       `major
                                 :minor       `minor
                                 :patch       `patch
                                 :pre-release `semver/snapshot)
                        (snapshot-deploy branch))
                  handler)
                 fileset))
            (throw (ex-info (str "Can't snapshot branch: " branch)
                            {:branch branch}))))))))

(deftask finish []
  (fn [handler]
    (fn [fileset]
      (let [repo (git/load-repo ".")]
        (ensure-clean repo)
        (let [branch             (git/git-branch-current repo)
              [_ working-branch] (re-matches #"Merge branch '(.*)' into .*"
                                             (-> repo
                                                 git/git-log
                                                 ^RevCommit first
                                                 .getShortMessage))
              resuming           (contains? (list-branches repo) working-branch)
              working-branch     (if resuming working-branch branch)
              [_ type name]      (re-matches working-branch-re working-branch)]
          (util/info "Finishing %s: %s...%n" type name)
          (((comp
             (code-check branch)
             (case type
               "feature" (comp (if resuming
                                 identity
                                 (incorporate-changes! repo working-branch))
                               (delete-branch! repo working-branch)
                               (feature-finish working-branch))
               "hotfix"  (comp (if resuming
                                 identity
                                 (comp (make-production! repo working-branch)
                                       (version)
                                       (production-deploy working-branch)
                                       (incorporate-changes!
                                        repo
                                        working-branch
                                        (or (list-branches repo
                                                           "release"
                                                           true)
                                            "develop"))))
                               (delete-branch! repo working-branch)
                               (hotfix-finish working-branch))
               "release" (comp (if resuming
                                 identity
                                 (comp (make-production! repo working-branch)
                                       (version)
                                       (production-deploy working-branch)
                                       (incorporate-changes! repo
                                                             working-branch)))
                               (delete-branch! repo working-branch)
                               (release-finish working-branch))))
            handler)
           fileset))))))
