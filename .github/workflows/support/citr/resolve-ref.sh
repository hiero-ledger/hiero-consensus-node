#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# Resolves the ref a CITR controller runs on, from a checkout of that ref, and writes to GITHUB_OUTPUT:
#
#   commit            the full SHA of HEAD
#   branch            the branch, when the ref is one. For a tag or SHA, the newest release branch
#                     containing the commit when the default branch does not contain it, otherwise empty.
#   build-tag-number  NNNNN, only when the ref is exactly a build-NNNNN tag
#   build-number      the five characters after "build-" anywhere in the ref; the loose match the
#                     controllers have always used, kept as it is for compatibility
#
# Chewie refuses a suite report for an unrecorded commit that is not reachable from the reported
# branch, or from the default branch when none is named; "branch" is what satisfies that check. The
# release branch lookup uses the same GitHub compare Chewie checks with, so it reads GH_TOKEN and
# GITHUB_REPOSITORY from the environment. Outside a workflow the outputs go to stdout.

usage() {
  echo "Usage: ${0} -r <ref> [-d <default_branch>] [-l <summary_label>]" >&2
}

# Succeeds when the commit is reachable from the base branch.
commit_on_branch() {
  local base="${1}" commit="${2}" status
  status="$(gh api "repos/${GITHUB_REPOSITORY}/compare/${base}...${commit}" --jq .status 2>/dev/null || true)"
  [[ "${status}" == "identical" || "${status}" == "behind" ]]
}

resolve_ref() {
  local REF=""
  local DEFAULT_BRANCH="main"
  local LABEL=""
  local arg
  while getopts "r:d:l:" arg; do
    case $arg in
      r) REF="${OPTARG}" ;;
      d) DEFAULT_BRANCH="${OPTARG}" ;;
      l) LABEL="${OPTARG}" ;;
      *)
        usage
        return 1
        ;;
    esac
  done

  if [[ -z "${REF}" ]]; then
    echo "Error: Missing required option -r <ref>" >&2
    usage
    return 1
  fi
  if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
    echo "Error: GITHUB_REPOSITORY must be set" >&2
    return 1
  fi

  local outputs="${GITHUB_OUTPUT:-/dev/stdout}"
  local summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

  local COMMIT
  COMMIT="$(git rev-parse HEAD)"

  # The branch is looked up as a full ref; a bare ls-remote pattern also matches any branch whose
  # name ends in the same path components.
  local BRANCH=""
  local REF_NAME="${REF#refs/heads/}"
  if [[ "${REF}" == refs/heads/* ]] || git ls-remote --exit-code --heads origin "refs/heads/${REF_NAME}" >/dev/null 2>&1; then
    BRANCH="${REF_NAME}"
  fi

  # A tag or SHA off the default branch (a release tag, say) needs a branch that contains it: take
  # the newest release branch that does.
  local candidate
  if [[ -z "${BRANCH}" ]] && ! commit_on_branch "${DEFAULT_BRANCH}" "${COMMIT}"; then
    while IFS= read -r candidate; do
      if commit_on_branch "${candidate}" "${COMMIT}"; then
        BRANCH="${candidate}"
        break
      fi
    done < <(gh api "repos/${GITHUB_REPOSITORY}/branches" --paginate --jq '.[].name | select(startswith("release/"))' | sort -rV)
    if [[ -z "${BRANCH}" ]]; then
      echo "::warning title=Commit Not On A Release Branch::${COMMIT} is not on ${DEFAULT_BRANCH} or any release branch; Chewie will refuse a suite report for it."
    fi
  fi

  local BUILD_TAG_NUMBER=""
  if [[ "${REF#refs/tags/}" =~ ^build-([0-9]{5})$ ]]; then
    BUILD_TAG_NUMBER="${BASH_REMATCH[1]}"
  fi

  local BUILD_NUMBER=""
  if [[ "${REF}" =~ build-(.{5}) ]]; then
    BUILD_NUMBER="${BASH_REMATCH[1]}"
  else
    echo "::notice title=Invalid Build Tag::The provided tag (${REF}) is not a valid build tag."
  fi

  {
    echo "commit=${COMMIT}"
    echo "branch=${BRANCH}"
    echo "build-tag-number=${BUILD_TAG_NUMBER}"
    echo "build-number=${BUILD_NUMBER}"
  } >> "${outputs}"

  {
    if [[ -n "${LABEL}" ]]; then
      echo "### Running ${LABEL}"
    fi
    if [[ -n "${BUILD_NUMBER}" ]]; then
      echo "Build number: ${BUILD_NUMBER}"
    else
      echo "Ref: ${REF}"
    fi
    echo "Commit: ${COMMIT}"
    echo "Branch: ${BRANCH:-(none)}"
    echo "Build tag number: ${BUILD_TAG_NUMBER:-(none)}"
  } >> "${summary}"
}

resolve_ref "$@"
