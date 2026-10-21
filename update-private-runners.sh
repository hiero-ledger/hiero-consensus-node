#!/usr/bin/env bash
# Prepares a release branch in private-hedera-services: switches runners to hg,
# disables Snyk and Codacy publishing, removes CODEOWNERS, runs spotless, commits and pushes.
#
# Usage: ./update-private-runners.sh [--force] <release-version> [repo-dir]
#   e.g. ./update-private-runners.sh 0.79 ~/Projects/private-hedera-services
# Normally does NOT create the PR; it prints the command to do so.
# --force: if the update branch already exists, close its open PR, delete the branch
#   locally and on origin, re-create it from the release branch, and open a new PR.

# Exit on any error, on unset variables, and on failures inside pipelines.
set -euo pipefail

# ---- Argument parsing -------------------------------------------------------

# --force must come first, before the version.
FORCE=false
if [[ "${1:-}" == "--force" ]]; then
  FORCE=true
  shift
fi
if [[ $# -lt 1 ]]; then
  echo "usage: $0 [--force] <release-version, e.g. 0.79> [repo-dir]" >&2
  exit 1
fi

VERSION="$1"
REPO_DIR="${2:-.}"                           # defaults to the current directory
RELEASE_BRANCH="release/${VERSION}"          # branch the PR will target
NEW_BRANCH="${VERSION}-update-runners"       # branch this script creates

# Timestamped progress line so long steps (e.g. gradle) are easy to follow.
step() { echo "[$(date +%H:%M:%S)] $*"; }

cd "$REPO_DIR"

# Pass --repo to gh explicitly: the clone also has a public remote, so gh can't
# pick a default repository on its own.
PR_REPO="hashgraph/private-hedera-services"
PR_TITLE="ci(chore): update runners for release ${VERSION}"

# Prints the manual PR command plus the remaining manual steps.
# Uses the absolute repo path so the command works from any directory.
print_next_steps() {
  step "Next:"
  echo "  cd $(pwd) && gh pr create --repo $PR_REPO --base $RELEASE_BRANCH --head $NEW_BRANCH --title '$PR_TITLE' --fill"
  echo "  Then in GitHub, disable the auto-unapprove workflow (080/701-flow-auto-unapprove)"
  echo "  and hand off the repo."
}

# ---- Safety checks ----------------------------------------------------------

# Never run against the public repo: origin must be the private one.
remote_url="$(git remote get-url origin)"
if [[ "$remote_url" != *private-hedera-services* ]]; then
  echo "ERROR: origin is '$remote_url', expected private-hedera-services" >&2
  exit 1
fi
# Uncommitted changes would get swept into the commit by `git add -A` below.
if [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: working tree is not clean" >&2
  exit 1
fi

# ---- Existing branch handling ----------------------------------------------

git fetch origin
# The branch counts as existing if it is present locally or on origin.
if git show-ref --verify --quiet "refs/heads/$NEW_BRANCH" \
  || git show-ref --verify --quiet "refs/remotes/origin/$NEW_BRANCH"; then
  if [[ "$FORCE" != true ]]; then
    step "Branch $NEW_BRANCH already exists; nothing to do (use --force to re-create it)."
    print_next_steps
    exit 0
  fi
  step "--force: removing existing branch $NEW_BRANCH and its open PRs"
  git fetch origin "$RELEASE_BRANCH"
  # Switch away first: git refuses to delete the branch we are currently on.
  git checkout "$RELEASE_BRANCH"
  # Close open PRs from the old branch (deleting the remote branch would also
  # close them, but this leaves an explicit closed state on each PR).
  gh pr list --repo "$PR_REPO" --head "$NEW_BRANCH" --state open --json number --jq '.[].number' |
    while read -r pr; do
      step "Closing PR #$pr"
      gh pr close "$pr" --repo "$PR_REPO"
    done
  # Delete the local and remote copies of the branch (each only if present).
  if git show-ref --verify --quiet "refs/heads/$NEW_BRANCH"; then
    git branch -D "$NEW_BRANCH"
  fi
  if git show-ref --verify --quiet "refs/remotes/origin/$NEW_BRANCH"; then
    git push origin --delete "$NEW_BRANCH"
  fi
fi

# ---- Create the working branch ---------------------------------------------

step "Checking out $RELEASE_BRANCH"
git fetch origin "$RELEASE_BRANCH"
git checkout "$RELEASE_BRANCH"
# --ff-only: fail rather than create a merge commit if local has diverged.
git pull --ff-only origin "$RELEASE_BRANCH"

step "Creating branch $NEW_BRANCH"
git checkout -b "$NEW_BRANCH"
git push --set-upstream origin "$NEW_BRANCH"

# ---- File edits -------------------------------------------------------------

# Portable in-place sed: BSD sed (macOS) needs an empty backup suffix, GNU sed does not.
sedi() { if [[ "$(uname)" == "Darwin" ]]; then sed -i '' "$@"; else sed -i "$@"; fi; }

# Runner labels appear in workflows and in .trunk/trunk.yaml, where the required
# check names embed the runner label (e.g. "Gradle: Verify Artifacts (hl-cn-...)").
# Without the rename there, the renamed jobs would never satisfy those checks.
# 'runs-on: hl' covers any bare hl runner; 'hl-cn' covers the hl-cn-* labels.
step "Updating runners (hl -> hg)"
grep -rlE 'runs-on: hl|hl-cn' .github .trunk/trunk.yaml | while read -r f; do
  sedi -e 's/runs-on: hl/runs-on: hg/g' -e 's/hl-cn/hg-cn/g' "$f"
done

# Two spellings of "snyk enabled" exist: a literal "true" and an expression
# driven by the detect-change-type job. Both become a literal "false".
step "Disabling Snyk"
grep -rlE 'enable-snyk-scan: ("true"|\$\{\{ needs\.detect-change-type\.outputs\.enable-tests \}\})' .github | while read -r f; do
  sedi -e 's/enable-snyk-scan: "true"/enable-snyk-scan: "false"/' \
       -e 's/enable-snyk-scan: \${{ needs\.detect-change-type\.outputs\.enable-tests }}/enable-snyk-scan: "false"/' "$f"
done

# git rm (not plain rm) so the deletion is staged; fails loudly if the file is missing.
step "Deleting CODEOWNERS"
git rm -f .github/CODEOWNERS

step "Disabling Codacy publishing"
# The 'if:' line directly below the CODACY_PROJECT_TOKEN env line gets 'false &&' prepended.
# Other CODACY_PROJECT_TOKEN matches are just secret pass-throughs and have no 'if:' to change.
codacy_files="$(grep -rl 'CODACY_PROJECT_TOKEN:' .github || true)"
if [[ -z "$codacy_files" ]]; then
  echo "ERROR: no CODACY_PROJECT_TOKEN env entries found" >&2
  exit 1
fi
for f in $codacy_files; do
  # Use awk because the edit depends on the line after the match: prev_token is
  # true only on the line right after a CODACY_PROJECT_TOKEN line. The !/false &&/
  # guard keeps the edit idempotent. Writes to a temp file, then replaces the original.
  awk '
    prev_token && /^[[:space:]]*if: *\$\{\{/ && !/false &&/ { sub(/\$\{\{ */, "${{ false \\&\\& "); }
    { print; prev_token = ($0 ~ /CODACY_PROJECT_TOKEN:/) }
  ' "$f" > "$f.tmp"
  mv "$f.tmp" "$f"
done
# Sanity check: fail if no 'if:' was actually disabled (e.g. the workflow layout changed).
if ! grep -rq 'if: \${{ false &&' $codacy_files; then
  echo "ERROR: did not find an 'if:' below any CODACY_PROJECT_TOKEN entry to disable" >&2
  exit 1
fi

# ---- Format, commit, push ---------------------------------------------------

# Confirms formatting is correct (and fixes it if the edits broke any).
step "Running spotlessApply"
./gradlew spotlessApply

# Show what is about to be committed.
step "Changes:"
git status --short

step "Committing and pushing"
git add -A
git commit -m "chore: update runners and workflows for private release ${VERSION}"
git push

# ---- Pull request -----------------------------------------------------------

# With --force the PR was closed earlier, so open a fresh one; otherwise leave
# PR creation to the user and just print the command.
if [[ "$FORCE" == true ]]; then
  step "Creating PR"
  gh pr create --repo "$PR_REPO" --base "$RELEASE_BRANCH" --head "$NEW_BRANCH" --title "$PR_TITLE" --fill
  step "Remember: disable the auto-unapprove workflow (080/701-flow-auto-unapprove) and hand off the repo."
else
  print_next_steps
fi
