#!/usr/bin/env bash
# Extract the replay parameters and failure summary from a Transaction Replay Diff workflow run.
#
# Usage:
#   ci-params.sh <run-url>                 # e.g. https://github.com/hashgraph/hedera-state-validator/actions/runs/<run>/job/<job>
#   ci-params.sh <run-id> [job-id]
#
# Prints `export VAR=value` lines (save them to a file for run-replay.sh) followed by commented
# summary lines: the jar the run downloaded, where CI uploaded the diff files, and the step/error lines.
# The GCP billing project is not copied from CI: BILLING_PROJECT is emitted as a placeholder that the user has to fill
# in with a project their account may bill (run-replay.sh refuses to start until it's set).
# Needs `gh` logged in with `repo` scope (SSO authorized for the org).
# Compatible with bash 3.2 (stock macOS).
set -euo pipefail

REPO="${REPO:-hashgraph/hedera-state-validator}"
arg="${1:?usage: ci-params.sh <run-url> | <run-id> [job-id]}"
job="${2:-}"

case "${arg}" in
    http*)
        run=$(printf '%s' "${arg}" | sed -nE 's#.*/actions/runs/([0-9]+).*#\1#p')
        url_job=$(printf '%s' "${arg}" | sed -nE 's#.*/job/([0-9]+).*#\1#p')
        [ -n "${url_job}" ] && job="${url_job}"
        ;;
    *) run="${arg}" ;;
esac
[ -n "${run}" ] || { echo "Could not parse a run id from: ${arg}" >&2; exit 1; }

if [ -z "${job}" ]; then
    job=$(gh run view "${run}" --repo "${REPO}" --json jobs \
        -q '.jobs[] | select(.name | test("Run transaction replay")) | .databaseId' | head -1)
fi
[ -n "${job}" ] || { echo "No 'Run transaction replay' job found in run ${run}" >&2; exit 1; }

log=$(mktemp)
trap 'rm -f "${log}"' EXIT
gh run view --job "${job}" --repo "${REPO}" --log > "${log}"

# gh log lines are "<job name>\t<step>\t<timestamp> <text>"; keep only the text.
body() { cut -f3- "${log}" | sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z //'; }

echo "# run ${run}, job ${job} (${REPO})"
for v in ORIGIN_ROUND TARGET_ROUND BLOCK_STREAM_DIR SNAPSHOT_BUCKET NODE_ID CHAIN_ID; do
    val=$(body | sed -nE "s/^ +${v}: (.*)$/\1/p" | head -1)
    if [ -n "${val}" ]; then echo "export ${v}=${val}"; else echo "# ${v}: not found in log"; fi
done

echo "# GCP project the user's account may bill for the requester-pays block stream: ask the user."
echo "export BILLING_PROJECT=\"<ask-the-user>\""
echo "# Only if gcloud fails with 403 serviceusage.services.use: ask the user for a quota project, then uncomment."
echo "# export CLOUDSDK_BILLING_QUOTA_PROJECT=\"<ask-the-user>\""

jar=$(body | sed -nE 's#.*Copying (gs://[^ ]*/hedera-state-validator-[^ ]*\.jar)\.\.\..*#\1#p' | head -1)
echo "# jar: ${jar:-unknown}   (download: gcloud storage cp <jar-uri> .   then: unzip -p <jar> git.properties)"
echo "# export JAR=\$PWD/$(basename "${jar:-validator.jar}")"
diffs=$(body | sed -nE 's#.*destination: (.*replay_diff_test.*)$#\1#p' | head -1)
[ -n "${diffs}" ] && echo "# diff files (only uploaded on failure): gs://${diffs}/"
echo "# --- steps and errors ---"
body | grep -E '\[INFO\]  === Step|\[ERROR\]|Exception|Caused by|##\[error\]|genuine stale|cutover|RESULT|Using contracts.chainId' \
    | awk '!seen[$0]++' | cut -c1-240 | sed 's/^/# /'
