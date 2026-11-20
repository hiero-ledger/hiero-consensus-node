#!/usr/bin/env bash
# Run validate-replay.sh locally with a CI run's parameters, after checking the platform prerequisites.
#
# Usage:
#   run-replay.sh <workdir> <params.env> [jar]
#     <workdir>     a new, run-specific directory (snapshots, out/, logs all land here)
#     <params.env>  export lines from ci-params.sh (ORIGIN_ROUND, TARGET_ROUND, ...); may also set STEPS, OUT,
#                   BLOCK_OUTPUT_DIR, CLOUDSDK_BILLING_QUOTA_PROJECT, JAVA_TOOL_OPTIONS
#     [jar]         validator jar (or set JAR in the env file)
#
# validate-replay.sh lives in hashgraph/hedera-state-validator (.github/scripts/). It is downloaded with gh from that
# repository (VALIDATE_REPLAY_REF, default main) unless VALIDATE_REPLAY points at a local copy. A copy with LF line
# endings is placed in <workdir> so the run is reproducible, and the script's commit is logged.
# Output is tee'd to <workdir>/replay-run.log.
# This wrapper itself is bash 3.2 compatible; validate-replay.sh needs bash >= 4 and GNU find/mv (checked below).
set -uo pipefail

workdir="${1:?usage: run-replay.sh <workdir> <params.env> [jar]}"
params="${2:?usage: run-replay.sh <workdir> <params.env> [jar]}"
jar_arg="${3:-}"

script_repo="hashgraph/hedera-state-validator"
script_path=".github/scripts/validate-replay.sh"
script_ref="${VALIDATE_REPLAY_REF:-main}"

# --- platform setup -------------------------------------------------------------------------------------------
case "$(uname -s)" in
    Darwin)
        # Stock macOS has bash 3.2 and BSD find/mv; put Homebrew's bash and GNU tools first.
        if command -v brew >/dev/null 2>&1; then
            p="$(brew --prefix)"
            export PATH="${p}/opt/coreutils/libexec/gnubin:${p}/opt/findutils/libexec/gnubin:${p}/bin:${PATH}"
        fi
        ;;
    Linux)
        if grep -qi microsoft /proc/version 2>/dev/null; then
            # WSL: gcloud storage multiprocessing can hang on exit; use one process with threads.
            export CLOUDSDK_STORAGE_PROCESS_COUNT="${CLOUDSDK_STORAGE_PROCESS_COUNT:-1}"
            export CLOUDSDK_STORAGE_THREAD_COUNT="${CLOUDSDK_STORAGE_THREAD_COUNT:-24}"
        fi
        ;;
esac

# --- prerequisites ---------------------------------------------------------------------------------------------
fail=0
bash_major="$(bash -c 'echo "${BASH_VERSINFO[0]}"')"
if [ "${bash_major}" -lt 4 ]; then
    echo "ERROR: validate-replay.sh needs bash >= 4 on PATH (found ${bash_major}). macOS: brew install bash" >&2; fail=1
fi
if ! find . -maxdepth 0 -printf '' >/dev/null 2>&1; then
    echo "ERROR: GNU find (-printf) needed on PATH. macOS: brew install findutils" >&2; fail=1
fi
if ! mv --version >/dev/null 2>&1; then
    echo "ERROR: GNU mv (mv -t) needed on PATH. macOS: brew install coreutils" >&2; fail=1
fi
if ! java -version 2>&1 | grep -qE 'version "25'; then
    echo "WARNING: Java 25 expected; found: $(java -version 2>&1 | head -1)" >&2
fi
if ! gcloud auth print-access-token >/dev/null 2>&1; then
    echo "ERROR: gcloud is not logged in (run: gcloud auth login <you>@hashgraph.com)" >&2; fail=1
fi
if [ -n "${VALIDATE_REPLAY:-}" ]; then
    [ -f "${VALIDATE_REPLAY}" ] || { echo "ERROR: VALIDATE_REPLAY not found: ${VALIDATE_REPLAY}" >&2; fail=1; }
elif ! gh auth status >/dev/null 2>&1; then
    echo "ERROR: gh is not installed or not logged in; it downloads validate-replay.sh from ${script_repo}" \
        "(or set VALIDATE_REPLAY to a local copy)" >&2; fail=1
fi
[ -f "${params}" ] || { echo "ERROR: params file not found: ${params}" >&2; fail=1; }
[ "${fail}" -eq 0 ] || exit 2

# --- parameters ------------------------------------------------------------------------------------------------
set -a
# shellcheck disable=SC1090
. "${params}"
set +a
for v in BILLING_PROJECT CLOUDSDK_BILLING_QUOTA_PROJECT; do
    eval "value=\${${v}:-}"
    case "${value}" in
        *ask-the-user*)
            echo "ERROR: ${v} is still the placeholder in ${params}; ask the user which GCP project to use." >&2
            exit 2
            ;;
    esac
done
[ -n "${jar_arg}" ] && JAR="${jar_arg}"
[ -n "${JAR:-}" ] || { echo "ERROR: no validator jar (pass it as 3rd argument or set JAR)" >&2; exit 2; }
JAR="$(cd "$(dirname "${JAR}")" && pwd)/$(basename "${JAR}")"
[ -f "${JAR}" ] || { echo "ERROR: jar not found: ${JAR}" >&2; exit 2; }
export JAR

mkdir -p "${workdir}"
workdir="$(cd "${workdir}" && pwd)"
if [ -n "${VALIDATE_REPLAY:-}" ]; then
    script_source="${VALIDATE_REPLAY}"
    tr -d '\r' < "${VALIDATE_REPLAY}" > "${workdir}/validate-replay.sh"
else
    script_commit="$(gh api "repos/${script_repo}/commits?path=${script_path}&sha=${script_ref}&per_page=1" \
        -q '.[0].sha' 2>/dev/null)"
    script_source="${script_repo}@${script_commit:-${script_ref}}:${script_path}"
    if ! gh api -H "Accept: application/vnd.github.raw" \
        "repos/${script_repo}/contents/${script_path}?ref=${script_commit:-${script_ref}}" \
        | tr -d '\r' > "${workdir}/validate-replay.sh" || [ ! -s "${workdir}/validate-replay.sh" ]; then
        echo "ERROR: could not download ${script_path} from ${script_repo} (ref ${script_ref})" >&2
        exit 2
    fi
fi

# --- warnings about leftovers from earlier runs in the same directory -------------------------------------------
for round in "${ORIGIN_ROUND:-}" "${TARGET_ROUND:-}"; do
    d="${workdir}/${round}"
    if [ -n "${round}" ] && [ -d "${d}" ] && [ -z "$(ls -A "${d}")" ]; then
        echo "WARNING: ${d} is empty (failed download?); validate-replay.sh will treat it as present. Remove it." >&2
    fi
done
if [ -d "${workdir}/data/saved" ]; then
    echo "WARNING: ${workdir}/data/saved exists from an earlier replay; replay-pces will read it too" \
        "(\"Upper bound must never decrease\"). Remove it if it came from your previous run." >&2
fi

# --- run -------------------------------------------------------------------------------------------------------
cd "${workdir}" || exit 2
ulimit -n 65536 2>/dev/null || echo "WARNING: could not raise the open-file limit (now $(ulimit -n))" >&2
echo "Running validate-replay.sh (${script_source}) in ${workdir} with JAR=${JAR} STEPS=${STEPS:-1-5}"
bash ./validate-replay.sh 2>&1 | tee replay-run.log
rc="${PIPESTATUS[0]}"
echo "VALIDATE_REPLAY_EXIT=${rc}" | tee -a replay-run.log
exit "${rc}"
