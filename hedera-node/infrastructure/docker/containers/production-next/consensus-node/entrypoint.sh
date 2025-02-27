#!/command/with-contenv bash
# shellcheck shell=bash

########################################################################################################################
# Copyright 2016-2022 Hedera Hashgraph, LLC                                                                            #
#                                                                                                                      #
# Licensed under the Apache License, Version 2.0 (the "License");                                                      #
# you may not use this file except in compliance with the License.                                                     #
# You may obtain a copy of the License at                                                                              #
#                                                                                                                      #
#     http://www.apache.org/licenses/LICENSE-2.0                                                                       #
#                                                                                                                      #
# Unless required by applicable law or agreed to in writing, software                                                  #
# distributed under the License is distributed on an "AS IS" BASIS,                                                    #
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.                                             #
# See the License for the specific language governing permissions and                                                  #
# limitations under the License.                                                                                       #
########################################################################################################################

set -eo pipefail

SCRIPT_PATH="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd "${SCRIPT_PATH}" || exit 64

### Utility method to wait for the presence of a file with size > 0
function waitForFile() {
  local fileName="${1}"
  local attempts
  local size

  [[ -z "${fileName}" ]] && return 1
  for (( attempts = 0; attempts < 20; attempts++ )); do
    if [[ -f "${fileName}" ]]; then
      size="$(stat --format='%s' "${fileName}")"
      [[ -n "${size}" && "${size}" -gt 0 ]] && return 0
    fi
    sleep 6
  done

  return 1
}

## Extended waitForFile method which adds stdout output facilities and support for optionally present files
function waitForFileEx() {
  local fileName="${1}"
  local message="${2}"
  local optional="${3}"

  [[ -z "${optional}" ]] && optional="false"

  local rc="0"
  printf "%s ..... " "${message}"
  set +e
  waitForFile "${fileName}"
  rc="${?}"
  set -e

  if [[ "${rc}" -ne 0 ]]; then
    if [[ "${optional}" != true ]]; then
      printf "ERROR (exit code: %s)\n" "${rc}"
    else
      printf "NOT PRESENT\n"
      rc=0
    fi
  else
    printf "OK\n"
  fi

  return "${rc}"
}

if [[ -z "${JAVA_OPTS}" ]]; then
  JAVA_OPTS=""
fi

# Setup Heap Options
JAVA_HEAP_OPTS=""

if [[ -n "${JAVA_HEAP_MIN}" ]]; then
  JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS} -Xms${JAVA_HEAP_MIN}"
fi

if [[ -n "${JAVA_HEAP_MAX}" ]]; then
  JAVA_HEAP_OPTS="${JAVA_HEAP_OPTS} -Xmx${JAVA_HEAP_MAX}"
fi

# Setup Main Class - Updated to default to the ServicesMain entrypoint class introduced in release v0.35.0 and production
# ready as of the v0.52.0 release.
[[ -z "${JAVA_MAIN_CLASS}" ]] && JAVA_MAIN_CLASS="com.hedera.node.app.ServicesMain"

# Setup Classpath
JCP_OVERRIDDEN="false"
if [[ -z "${JAVA_CLASS_PATH}" ]]; then
  JAVA_CLASS_PATH="data/lib/*:data/apps/*"
else
  JCP_OVERRIDDEN="true"
fi

if [[ "${JCP_OVERRIDDEN}" != true && "${JAVA_MAIN_CLASS}" == "com.swirlds.platform.Browser" ]]; then
  JAVA_CLASS_PATH="data/lib/*"
fi

# Setup Consensus Node Arguments
CONSENSUS_NODE_ARGS=""
[[ -n "${CONSENSUS_NODE_ID}" && "${CONSENSUS_NODE_ID}" -ge 0 ]] && CONSENSUS_NODE_ARGS="-local ${CONSENSUS_NODE_ID}"

# Override Log Directory Name (if provided)
LOG_DIR_NAME="${LOG_DIR_NAME:-output}"

# Ensure the log directory exists
if [[ ! -d "${SCRIPT_PATH}/${LOG_DIR_NAME}" ]]; then
 mkdir -p "${SCRIPT_PATH}/${LOG_DIR_NAME}"
fi

cat <<EOF


****************        ****************************************************************************************
************                ************                                                                       *
*********                      *********                                                                       *
******                            ******                                                                       *
****                                ****      ___           ___           ___           ___           ___      *
***        ĦĦĦĦ          ĦĦĦĦ        ***     /\  \         /\  \         /\  \         /\  \         /\  \     *
**         ĦĦĦĦ          ĦĦĦĦ         **    /::\  \       /::\  \       /::\  \       /::\  \       /::\  \    *
*          ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ          *   /:/\:\  \     /:/\:\  \     /:/\:\  \     /:/\:\  \     /:/\:\  \   *
           ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ             /::\~\:\  \   /:/  \:\__\   /::\~\:\  \   /::\~\:\  \   /::\~\:\  \  *
           ĦĦĦĦ          ĦĦĦĦ            /:/\:\ \:\__\ /:/__/ \:|__| /:/\:\ \:\__\ /:/\:\ \:\__\ /:/\:\ \:\__\ *
           ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ            \:\~\:\ \/__/ \:\  \ /:/  / \:\~\:\ \/__/ \/_|::\/:/  / \/__\:\/:/  / *
*          ĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦĦ          *  \:\ \:\__\    \:\  /:/  /   \:\ \:\__\      |:|::/  /       \::/  /  *
**         ĦĦĦĦ          ĦĦĦĦ         **   \:\ \/__/     \:\/:/  /     \:\ \/__/      |:|\/__/        /:/  /   *
***        ĦĦĦĦ          ĦĦĦĦ        ***    \:\__\        \::/__/       \:\__\        |:|  |         /:/  /    *
****                                ****     \/__/         ~~            \/__/         \|__|         \/__/     *
******                            ******                                                                       *
*********                      *********                                                                       *
************                ************                                                                       *
****************        ****************************************************************************************


#### Starting Hedera Consensus Node Software ####


EOF

echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN USER IDENT >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
id
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END USER IDENT   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
echo

echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN JAVA VERSION >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
/usr/bin/env java -version
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END JAVA VERSION   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
echo

echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN WAITING FOR FILES >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
## In Kubernetes environments intermittent failures have been observed due to the fragile config.txt/settings.txt loading
## code when the file is not immediately available. This can occur due to various reasons including Persistent Volumes
## being slow to bind and attach or projected Config Maps/Secrets not being fully initialized when the container starts.

## This is a permanent belt and suspenders type of fix in addition to any resiliency which may also be added to the
## Platform configuration loading mechanism.
waitForFileEx "log4j2.xml" "Checking for log4j2.xml presence"
waitForFileEx "config.txt" "Checking for config.txt presence"
waitForFileEx "settings.txt" "Checking for settings.txt presence"
waitForFileEx "data/config/application.properties" "Checking for application.properties presence"
waitForFileEx "data/config/api-permission.properties" "Checking for api-permission.properties presence"
waitForFileEx "hedera.crt" "Checking for hedera.crt presence (optional)" "true"
waitForFileEx "hedera.key" "Checking for hedera.key presence (optional)" "true"
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END WAITING FOR FILES   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
echo

set +e
echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN NODE OUTPUT >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
## Due to a current bug in CoreDNS combined with the Platform config loading brittleness, it is necessary to retry
## starting the platform software if the exit code is 205 which indicates a config.txt/address book loading issue.
ATTEMPTS=0
while true; do
  /usr/bin/env java ${JAVA_HEAP_OPTS} ${JAVA_OPTS} -cp "${JAVA_CLASS_PATH}" "${JAVA_MAIN_CLASS}" ${CONSENSUS_NODE_ARGS}
  EC="${?}"
  if [[ "${EC}" -eq 205 && "${ATTEMPTS}" -lt 20 ]]; then
    printf "\n\n############# Retrying system initialization - DNS or Address Book Failure (Exit Code: %s) #############\n\n" "${EC}"
    ATTEMPTS=$(( ATTEMPTS + 1 ))
    sleep 6
  else
    break
  fi
done
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END NODE OUTPUT   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"

echo
echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN EXIT CODE >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
printf "Process Exit Code: %s\n" "${EC}"
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END EXIT CODE   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"

printf "\n\n#### Hedera Services Node Software Stopped ####\n\n"

## Explicitly & intentionally using GNU sleep here - Not using the shell built-in due to lack of and/or buggy infinity
## support depending on the shell version
if [[ -n "${CONTAINER_TSR_ENABLED}" ]] && \
    [[ "${CONTAINER_TSR_ENABLED}" == true || "${CONTAINER_TSR_ENABLED}" -gt 0 ]]; then
  printf "#### Container TSR is ENABLED - Entering infinite sleep ####\n\n"
  /usr/bin/env sleep infinity
else
  printf "#### Container TSR is DISABLED - Exiting with Final Disposition (Exit Code: %s) ####\n\n" "${EC}"
  exit "${EC}"
fi
