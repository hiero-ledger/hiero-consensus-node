#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests .github/workflows/support/scripts/resolve-cn-configs.sh against a throw-away git repository.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVE="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/resolve-cn-configs.sh"

if ! command -v jq &>/dev/null; then
  echo "ERROR: jq is not installed or not in PATH"
  exit 1
fi

failures=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then
    echo "PASS: $1"
  else
    echo "FAIL: $1 (expected '$2', got '$3')"
    failures=$((failures + 1))
  fi
}
check_fails() { # name command...
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "FAIL: ${name} should fail"
    failures=$((failures + 1))
  else
    echo "PASS: ${name} fails"
  fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# commit_ref <branch> <solo> <jdk> <bn> [omit-key]
commit_ref() {
  git -C "${WORK}/origin" checkout -q -B "$1"
  mkdir -p "${WORK}/origin/.github/workflows/support/citr" "${WORK}/origin/gradle"
  {
    echo "citr-solo-version=$2"
    echo "bn-mirror-node-version=v0.165.0"
    echo "tck-version=v0.14.0"
    echo "js-sdk-version=v2.90.1"
    echo "block-node-version=$4"
  } | { if [[ -n "$5" ]]; then grep -v "^$5="; else cat; fi; } \
    > "${WORK}/origin/.github/workflows/support/citr/.citr-env"
  echo "jdk=$3" > "${WORK}/origin/gradle/toolchain-versions.properties"
  git -C "${WORK}/origin" add -A
  git -C "${WORK}/origin" commit -q -m "$1"
}

git init -q "${WORK}/origin"
commit_ref main v0.91.0 25.0.2 v0.44.1
commit_ref release/0.72 v0.60.0 21.0.1 v0.43.0
commit_ref old-solo v0.43.0 21.0.1 v0.43.0
commit_ref no-tck v0.91.0 25.0.2 v0.44.1 tck-version
git clone -q "${WORK}/origin" "${WORK}/clone"
cd "${WORK}/clone"

out="$(CN_REFS=' main , release/0.72,main' "${RESOLVE}")"
check "duplicates dropped, order kept" '["main","release/0.72"]' "$(echo "${out}" | jq -c '[.[]."cn-ref"]')"
check "slug replaces slashes" 'release-0.72' "$(echo "${out}" | jq -r '.[1]."cn-slug"')"
check "each ref gets its own versions" 'v0.91.0/25.0.2|v0.60.0/21.0.1' \
  "$(echo "${out}" | jq -r '[.[] | ."solo-version" + "/" + ."java-version"] | join("|")')"
check "pinned BN version is per ref" 'v0.44.1|v0.43.0' \
  "$(echo "${out}" | jq -r '[.[]."pinned-bn-version"] | join("|")')"
check "other pinned versions are read" 'v0.165.0 v0.14.0 v0.91.0 v2.90.1' \
  "$(echo "${out}" | jq -r '.[0] | [."bn-mirror-node-version", ."tck-version", ."solo-version", ."js-sdk-version"] | join(" ")')"

check_fails "solo < 0.44.0" env CN_REFS=old-solo "${RESOLVE}"
check_fails "missing key in .citr-env" env CN_REFS=no-tck "${RESOLVE}"
check_fails "unknown ref" env CN_REFS=does-not-exist "${RESOLVE}"
check_fails "empty refs" env CN_REFS=" , " "${RESOLVE}"
check_fails "unset refs" env -u CN_REFS "${RESOLVE}"
for bad in 'main;id' '$(id)' 'a b' 'main"x'; do
  check_fails "invalid ref '${bad}'" env CN_REFS="${bad}" "${RESOLVE}"
done

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} test(s) failed"
  exit 1
fi
echo "All tests passed"
