#!/usr/bin/env bash
set -eo pipefail

TAG=${1:-'0.1.0'}
SCRIPT_SOURCE="${BASH_SOURCE[0]}"

READLINK_OPTS=""

if readlink -f . >/dev/null 2>&1; then
  READLINK_OPTS="-f"
fi

if [[ -n "$(readlink ${READLINK_OPTS} "${SCRIPT_SOURCE}")" ]]; then
  SCRIPT_SOURCE="$(readlink ${READLINK_OPTS} "${SCRIPT_SOURCE}")"
fi

SCRIPT_PATH="$(cd "$(dirname "${SCRIPT_SOURCE}")" && pwd)"

OLD_CWD="$(pwd)"

cd "${SCRIPT_PATH}/../../../../"
./gradlew assemble cleanValidation validationJar copyValidation
cd "${SCRIPT_PATH}/.."

docker build -t validation-scenarios:$TAG .
cd "${OLD_CWD}"
