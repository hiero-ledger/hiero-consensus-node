#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

# ANSI Colors
#
readonly RED=$'\e[31m'
readonly GREEN=$'\e[32m'
readonly RESET=$'\e[0m'

# Logging (stderr, stdout is reserved for the Jenkins queue id)
#
log()  { printf '%b[%s]%b %s\n' "$2" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$RESET" "$1" >&2; }
die()  { log "$1" "$RED"; exit "${2:-1}"; }

# Usage
#
Usage() {
cat >&2 <<EOF
Usage: $0 <build-tag> <build-commit> <version-service> <version-blocknode> <version-mirrornode> [test-name]

Starts the SDCT Jenkins job and prints its queue id on stdout.

  build-commit     build artifact name used to reset the perf1 network (build-main-<sha8>, build-v0.79.0)
  version-service  service version for reporting (same as build-commit on main, the ref otherwise)
  test-name        SDCT test profile: sdct (full test, default), mini or custom

Environment variables required:
  USERNAME    Jenkins user
  PASSWORD    Jenkins token
  SERVER      Jenkins base URL (https://jenkins.example.com)
  GH_RUN_ID   GitHub workflow run id, used to correlate the Jenkins build
  GH_RUN_URL  GitHub workflow run URL
EOF
exit 1
}

# Preflight Checks
#
[[ $# -eq 5 || $# -eq 6 ]] || Usage
[[ -v USERNAME   && -n ${USERNAME}   ]] || die "USERNAME not set" 2
[[ -v PASSWORD   && -n ${PASSWORD}   ]] || die "PASSWORD not set" 2
[[ -v SERVER     && -n ${SERVER}     ]] || die "SERVER not set"   2
[[ -v GH_RUN_ID  && -n ${GH_RUN_ID}  ]] || die "GH_RUN_ID not set" 2
[[ -v GH_RUN_URL && -n ${GH_RUN_URL} ]] || die "GH_RUN_URL not set" 2

readonly BUILD_TAG=${1}
readonly BUILD_COMMIT=${2}
readonly VERSION_SERVICE=${3}
readonly VERSION_BLOCKNODE=${4}
readonly VERSION_MIRRORNODE=${5}
readonly SDCT_TEST=${6:-sdct}
[[ "${SDCT_TEST}" =~ ^(sdct|mini|custom)$ ]] || die "Unknown test name: ${SDCT_TEST}" 2
readonly USERPASSWORD="${USERNAME}:${PASSWORD}"

command -v curl >/dev/null || die "❌ curl is not installed"
command -v mktemp >/dev/null || die "❌ mktemp is not available"

# Jenkins CSRF crumbs
#
COOKIEJAR="$(mktemp -t cookies.XXXXXXXXX)"
HEADERS="$(mktemp -t headers.XXXXXXXXX)"
trap 'rm -f "${COOKIEJAR}" "${HEADERS}"' EXIT INT TERM HUP

CRUMB=$(curl --no-progress-meter -f -u "${USERPASSWORD}" --cookie-jar "${COOKIEJAR}" \
        "${SERVER}/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,%22:%22,//crumb)") \
  || die "❌ Error: Failed to fetch Jenkins crumb" 3

# Start Jenkins Job
#
curl --no-progress-meter -f -X POST -u "$USERPASSWORD" --cookie "$COOKIEJAR" \
     -H "${CRUMB:?Missing CRUMB header}"                     \
     -D "${HEADERS}" -o /dev/null                            \
     -F "BUILD_TAG=${BUILD_TAG}"                             \
     -F "BUILD_COMMIT=${BUILD_COMMIT}"                       \
     -F "VERSION_SERVICE=${VERSION_SERVICE}"                 \
     -F "VERSION_BLOCKNODE=${VERSION_BLOCKNODE}"             \
     -F "VERSION_MIRRORNODE=${VERSION_MIRRORNODE}"           \
     -F "GH_RUN_ID=${GH_RUN_ID}"                             \
     -F "GH_RUN_URL=${GH_RUN_URL}"                           \
     -F "SDCT_TEST=${SDCT_TEST}"                             \
     "${SERVER}/job/nightly/job/sdct/buildWithParameters"    \
  || die "❌ Error: Canonical Test failed to start for [${BUILD_TAG}] [${VERSION_SERVICE}]" 4

# Jenkins answers with "Location: <server>/queue/item/<id>/"
QUEUE_ID=$(tr -d '\r' < "${HEADERS}" | sed -n -E 's#^[Ll]ocation:.*/queue/item/([0-9]+)/?$#\1#p' | tail -1)
[[ -n "${QUEUE_ID}" ]] || die "❌ Error: No queue item returned by Jenkins for [${BUILD_TAG}]" 5

log "✅ Canonical test [${SDCT_TEST}] started for [${BUILD_TAG}] [${BUILD_COMMIT}] [${VERSION_SERVICE}] [BN ${VERSION_BLOCKNODE}] [MN ${VERSION_MIRRORNODE}], queue item ${QUEUE_ID}" "$GREEN"
echo "${QUEUE_ID}"

exit 0
