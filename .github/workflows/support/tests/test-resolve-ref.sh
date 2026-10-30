#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Tests .github/workflows/support/citr/resolve-ref.sh against a throwaway origin repository:
#
#   main:          c1 -- c2 (tag build-00012, v0.76.0, build-12345-rc)
#   release/0.75:   \-- r1 (tags v0.75.1, 0.74)
#   release/0.74:   \-- r0
#   orphan:              o1 (tag stray)  pushed only as a tag, on no branch
#
# gh is replaced by a stub that answers the compare and branches calls from that repository, with
# the caller's --jq filter applied, so the script's own filters are exercised.
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVE_REF="$(cd "${SCRIPT_DIR}/../citr" && pwd)/resolve-ref.sh"

for tool in git jq; do
  if ! command -v "${tool}" &>/dev/null; then
    echo "ERROR: ${tool} is not installed or not in PATH"
    exit 1
  fi
done

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

# Keep the user's and the system's git config (signing, hooks, default branch) out of the fixture.
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME="CITR Test" GIT_AUTHOR_EMAIL="citr-test@example.com"
export GIT_COMMITTER_NAME="CITR Test" GIT_COMMITTER_EMAIL="citr-test@example.com"

ORIGIN="${WORK_DIR}/origin.git"
SEED="${WORK_DIR}/seed"
STUB_BIN="${WORK_DIR}/bin"

git init --quiet --bare -b main "${ORIGIN}"
git init --quiet -b main "${SEED}"
(
  cd "${SEED}"
  git commit --quiet --allow-empty -m c1
  git checkout --quiet -b release/0.74
  git commit --quiet --allow-empty -m r0
  git checkout --quiet -b release/0.75 main
  git commit --quiet --allow-empty -m r1
  git tag v0.75.1
  git tag 0.74
  git checkout --quiet main
  git commit --quiet --allow-empty -m c2
  git tag build-00012
  git tag v0.76.0
  git tag build-12345-rc
  git checkout --quiet --orphan orphan
  git commit --quiet --allow-empty -m o1
  git tag stray
  git checkout --quiet main
  git remote add origin "${ORIGIN}"
  git push --quiet origin main release/0.74 release/0.75 --tags
)

C2="$(git -C "${ORIGIN}" rev-parse main)"
R1="$(git -C "${ORIGIN}" rev-parse release/0.75)"
O1="$(git -C "${ORIGIN}" rev-parse stray)"

mkdir -p "${STUB_BIN}"
cat > "${STUB_BIN}/gh" <<EOF
#!/usr/bin/env bash
# Stub for: gh api repos/<repo>/compare/<base>...<head> --jq <filter>
#           gh api repos/<repo>/branches --paginate --jq <filter>
set -eo pipefail
ORIGIN="${ORIGIN}"
EOF
cat >> "${STUB_BIN}/gh" <<'EOF'
[[ "${1}" == "api" ]] || exit 1
path="${2}"
filter="."
shift 2
while [[ $# -gt 0 ]]; do
  case "${1}" in
    --jq) filter="${2}"; shift 2 ;;
    *) shift ;;
  esac
done
case "${path}" in
  repos/*/compare/*)
    range="${path#*/compare/}"
    base="${range%%...*}"
    head="${range#*...}"
    base_sha="$(git -C "${ORIGIN}" rev-parse --verify --quiet "refs/heads/${base}")" || exit 1
    head_sha="$(git -C "${ORIGIN}" rev-parse --verify --quiet "${head}^{commit}")" || exit 1
    if [[ "${base_sha}" == "${head_sha}" ]]; then
      status=identical
    elif git -C "${ORIGIN}" merge-base --is-ancestor "${head_sha}" "${base_sha}"; then
      status=behind
    elif git -C "${ORIGIN}" merge-base --is-ancestor "${base_sha}" "${head_sha}"; then
      status=ahead
    else
      status=diverged
    fi
    jq -r "${filter}" <<< "{\"status\":\"${status}\"}"
    ;;
  repos/*/branches)
    git -C "${ORIGIN}" for-each-ref --format='%(refname:short)' refs/heads/ \
      | jq -R . | jq -s 'map({name: .})' | jq -r "${filter}"
    ;;
  *) exit 1 ;;
esac
EOF
chmod +x "${STUB_BIN}/gh"

FAILED=0
TOTAL=0

# check <name> <ref> <commit> <branch> <build-tag-number> <build-number>
# Clones origin at the ref, runs the script there, and compares every output.
check() {
  local name="${1}" ref="${2}"
  local -a expected=("commit=${3}" "branch=${4}" "build-tag-number=${5}" "build-number=${6}")
  local clone="${WORK_DIR}/clone-${TOTAL}"
  local output="${WORK_DIR}/output-${TOTAL}"
  local summary="${WORK_DIR}/summary-${TOTAL}"
  TOTAL=$((TOTAL + 1))

  local target="${ref#refs/*/}"
  git clone --quiet "${ORIGIN}" "${clone}"
  if git -C "${clone}" rev-parse --verify --quiet "origin/${target}^{commit}" >/dev/null; then
    target="origin/${target}"
  fi
  git -C "${clone}" checkout --quiet --detach "${target}"

  if ! (cd "${clone}" && PATH="${STUB_BIN}:${PATH}" GITHUB_REPOSITORY=test/repo GITHUB_OUTPUT="${output}" \
      GITHUB_STEP_SUMMARY="${summary}" "${RESOLVE_REF}" -r "${ref}" -d main -l TEST >/dev/null); then
    echo "FAIL: ${name} — resolve-ref.sh exited non-zero"
    FAILED=$((FAILED + 1))
    return
  fi

  local line case_failed=0
  for line in "${expected[@]}"; do
    if ! grep -qxF "${line}" "${output}"; then
      echo "FAIL: ${name} — expected '${line}', got '$(grep -E "^${line%%=*}=" "${output}" || true)'"
      case_failed=1
    fi
  done
  if ! grep -qxF "### Running TEST" "${summary}"; then
    echo "FAIL: ${name} — summary is missing its label"
    case_failed=1
  fi

  if [[ "${case_failed}" -eq 0 ]]; then
    echo "PASS: ${name}"
  else
    FAILED=$((FAILED + 1))
  fi
}

#     name                                  ref                      commit   branch         build-tag  build
check "branch name"                         main                     "${C2}"  main           ""         ""
check "branch as refs/heads/"               refs/heads/release/0.75  "${R1}"  release/0.75   ""         ""
check "build tag"                           build-00012              "${C2}"  ""             00012      00012
check "build tag as refs/tags/"             refs/tags/build-00012    "${C2}"  ""             00012      00012
check "loose build match, not a build tag"  build-12345-rc           "${C2}"  ""             ""         12345
check "non-build tag on the default branch" v0.76.0                  "${C2}"  ""             ""         ""
check "SHA on the default branch"           "${C2}"                  "${C2}"  ""             ""         ""
check "release tag off the default branch"  refs/tags/v0.75.1        "${R1}"  release/0.75   ""         ""
check "SHA off the default branch"          "${R1}"                  "${R1}"  release/0.75   ""         ""
check "commit on no branch"                 stray                    "${O1}"  ""             ""         ""

# A bare ls-remote pattern matches by trailing path components, so the tag 0.74 would pass for the
# branch release/0.74; it must resolve through the release branch lookup instead.
check "tag named like a branch's tail"      0.74                     "${R1}"  release/0.75   ""         ""

echo ""
echo "Results: $((TOTAL - FAILED))/${TOTAL} passed"

if [[ "${FAILED}" -gt 0 ]]; then
  exit 1
fi
