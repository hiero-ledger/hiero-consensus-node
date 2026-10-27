#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

# Tests .github/workflows/support/scripts/discover-cn-refs.sh against a throw-away git repository.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DISCOVER="$(cd "${SCRIPT_DIR}/../scripts" && pwd)/discover-cn-refs.sh"

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
finish() {
  if [[ "${failures}" -gt 0 ]]; then
    echo "${failures} test(s) failed"
    exit 1
  fi
  echo "All tests passed"
}

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# make_origin <dir> <branch>...: a repository whose branches are exactly the arguments (the first stays checked out)
make_origin() {
  local dir="$1"
  shift
  git init -q -b "$1" "${dir}"
  git -C "${dir}" commit -q --allow-empty -m init
  shift
  for b in "$@"; do git -C "${dir}" branch -q "$b"; done
}
discover_in() { # branches...: runs the script in a clone of an origin holding them
  rm -rf "${WORK}/o" "${WORK}/c"
  make_origin "${WORK}/o" "$@"
  git clone -q "${WORK}/o" "${WORK}/c" 2>/dev/null
  (cd "${WORK}/c" && "${DISCOVER}")
}

check "main plus newest release by version, not by name" 'main,release/0.79' \
  "$(discover_in main release/0.9 release/0.78 release/0.79 feature/x release/0.79-rc)"
check "two-digit minor beats one-digit" 'main,release/0.10' "$(discover_in main release/0.9 release/0.10)"
check_fails "no release branch" bash -c "$(declare -f make_origin discover_in); WORK='${WORK}' DISCOVER='${DISCOVER}'; discover_in main feature/x"
check_fails "no main" bash -c "$(declare -f make_origin discover_in); WORK='${WORK}' DISCOVER='${DISCOVER}'; discover_in trunk release/0.78"

finish
