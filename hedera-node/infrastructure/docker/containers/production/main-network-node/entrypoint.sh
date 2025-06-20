#!/usr/bin/env bash

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

# Setup Main Class
[[ -z "${JAVA_MAIN_CLASS}" ]] && JAVA_MAIN_CLASS="com.hedera.node.app.ServicesMain"

# Setup Classpath
JCP_OVERRIDDEN="false"
if [[ -z "${JAVA_CLASS_PATH}" ]]; then
  JAVA_CLASS_PATH="data/lib/*"
else
  JCP_OVERRIDDEN="true"
fi

if [[ "${JCP_OVERRIDDEN}" != true && "${JAVA_MAIN_CLASS}" != "com.swirlds.platform.Browser" ]]; then
  JAVA_CLASS_PATH="${JAVA_CLASS_PATH}:data/apps/*"
fi

# Setup Consensus Node Arguments
CONSENSUS_NODE_ARGS=""
[[ -n "${CONSENSUS_NODE_ID}" && "${CONSENSUS_NODE_ID}" -ge 0 ]] && CONSENSUS_NODE_ARGS="-local ${CONSENSUS_NODE_ID}"

# Ensure the log directory exists
if [[ ! -d "${SCRIPT_PATH}/output" ]]; then
 mkdir -p "${SCRIPT_PATH}/output"
fi

echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN USER IDENT >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
id
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END USER IDENT   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
echo

echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> BEGIN JAVA COMMAND >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
echo "/usr/bin/env java ${JAVA_HEAP_OPTS} ${JAVA_OPTS} -cp \"${JAVA_CLASS_PATH}\" \"${JAVA_MAIN_CLASS}\" ${CONSENSUS_NODE_ARGS}"
echo "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< END JAVA COMMAND   <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
echo

/usr/bin/env java ${JAVA_HEAP_OPTS} ${JAVA_OPTS} -cp "${JAVA_CLASS_PATH}" "${JAVA_MAIN_CLASS}" ${CONSENSUS_NODE_ARGS}
printf "java exit code %s" "${?}\n"
