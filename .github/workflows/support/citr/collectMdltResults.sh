#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Collects the artifacts of an MDLT run from its namespace and publishes them to GCS.
#
# Usage: collectMdltResults.sh <namespace> <build-tag> [fsts-report]
#   <namespace>    Kubernetes namespace holding the MDLT run
#   <build-tag>    Build tag the run is associated with (e.g. build-12345); used in the GCS path
#   [fsts-report]  "true" to also generate FSTS_Insight PDFs (requires fsts_insight on PATH)
#
# Reads from the environment:
#   GS_ROOT_DIR          gs:// root the report directory is uploaded under (required)
#   GS_ROOT_HTTPS        https root used for the "see results in" link (required)
#   GITHUB_WORKSPACE     working directory root (defaults to the current directory)
#   GITHUB_STEP_SUMMARY  when set, the results URL is appended to it
#
# Requires an authenticated kubectl context (Teleport) and gcloud credentials in the environment.

# Best-effort collection: an individual kubectl/copy failure must not stop the upload of whatever
# else was gathered, so errors are non-fatal here.
set +x
set +e

NAMESPACE=${1}
BUILD_TAG=${2}
FSTS_REPORT=${3:-false}

TOOLDIR=$(dirname "${0}")
WORKSPACE=${GITHUB_WORKSPACE:-$(pwd)}
REPORT_DIR=${WORKSPACE}/report
STEP_SUMMARY=${GITHUB_STEP_SUMMARY:-/dev/null}

if [[ -z "${NAMESPACE}" ]] || [[ -z "${BUILD_TAG}" ]]; then
  echo "Usage: $(basename "${0}") <namespace> <build-tag> [fsts-report]" >&2
  exit 1
fi

if [[ -z "${GS_ROOT_DIR}" ]] || [[ -z "${GS_ROOT_HTTPS}" ]]; then
  echo "Error: GS_ROOT_DIR and GS_ROOT_HTTPS must be set in the environment." >&2
  exit 1
fi

kubectlt="sh ${TOOLDIR}/kubectlt"

echo "Pods:"
${kubectlt} -n "${NAMESPACE}" get pods
nlgpod=$(${kubectlt} -n "${NAMESPACE}" get pods | grep nlg-network-load-generator | awk '{print $1}')

echo "NLG test client log (tail):"
# shellcheck disable=SC2086 # kubectlt is "sh <path>": it has to split into two words here.
timeout --preserve-status --foreground 1m ${kubectlt} -n "${NAMESPACE}" exec "${nlgpod}" -c nlg -- \
  bash -c "tail -n 100 /app/client.log"

echo "NLG test client java pid:"
${kubectlt} -n "${NAMESPACE}" exec "${nlgpod}" -c nlg -- bash -c "ps -aef | grep java"

echo "Collecting logs ..."
mkdir -p "${REPORT_DIR}"
cd "${REPORT_DIR}" || exit 1
# Collects CN pod logs/stats/config and an error summary into podlog_<namespace>/
sh "${TOOLDIR}/getClusterErrors.sh" "${NAMESPACE}"

# Optional FSTS PDF reports from the per-node MainNetStats CSVs (paths relative to report/).
if [[ "${FSTS_REPORT}" == "true" ]]; then
  echo "Generating FSTS PDFs ..."
  throttle_flag=0
  # shellcheck disable=SC2044 # stats CSV names never contain whitespace; keeps the throttling loop simple.
  for csv in $(find "podlog_${NAMESPACE}"/network-node*_logs/stats -type f -name 'MainNetStats*.csv' -print); do
    reportdir=$(dirname "${csv}")
    mkdir -p "${reportdir}/ignorable"
    if [[ ${throttle_flag} -eq 0 ]]; then
      fsts_insight -r "${reportdir}/ignorable" -f "${csv}" -o "${csv}.pdf" >/dev/null 2>&1 &
      throttle_flag=1
    else
      fsts_insight -r "${reportdir}/ignorable" -f "${csv}" -o "${csv}.pdf" >/dev/null 2>&1
      wait
      throttle_flag=0
    fi
  done
  wait
  find "podlog_${NAMESPACE}"/network-node*_logs/stats -type d -name ignorable -exec rm -rf {} +

  # Combined report across all nodes.
  mkdir -p ignorable
  # shellcheck disable=SC2046 # the perl pipeline emits a "-f <csv> -f <csv> ..." list that must word-split.
  fsts_insight -r ignorable $(find "podlog_${NAMESPACE}"/network-node*_logs/stats/ -type f -name 'MainNetStats*.csv' -print | perl -pne '~s/podlog_/ -f podlog_/g; ~s/\n//g;') -o "podlog_${NAMESPACE}/MainNetStats_combined.pdf"
  rm -rf ignorable
fi

${kubectlt} -n "${NAMESPACE}" cp "${nlgpod}":/app/client.log ./client.log
${kubectlt} -n "${NAMESPACE}" get pods > pod_state.txt
${kubectlt} -n "${NAMESPACE}" cp "${nlgpod}":/app/version_run.txt ./version_run.txt || true

hederaversion=$(grep 'hederaversion=' ./version_run.txt 2>/dev/null | awk -F = '{print $NF}')
run_number=$(grep 'run_number=' ./version_run.txt 2>/dev/null | awk -F = '{print $NF}')
cd "${WORKSPACE}" || exit 1

timestamp=$(date +%T:%m-%d-%Y)
run_path="${BUILD_TAG}/${hederaversion}_${NAMESPACE}_${run_number}_${timestamp}/report"
gcloud --no-user-output-enabled storage cp --recursive report "${GS_ROOT_DIR}/${run_path}"
echo "Done: see results in ${GS_ROOT_HTTPS}/${run_path}" | tee -a "${STEP_SUMMARY}"
