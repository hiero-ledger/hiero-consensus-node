#!/usr/bin/env bash

#
# Copyright 2016-2022 Hedera Hashgraph, LLC
#
# This software is the confidential and proprietary information of
# Hedera Hashgraph, LLC. ("Confidential Information"). You shall not
# disclose such Confidential Information and shall use it only in
# accordance with the terms of the license agreement you entered into
# with Hedera Hashgraph.
#
# HEDERA HASHGRAPH MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
# THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
# TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
# PARTICULAR PURPOSE, OR NON-INFRINGEMENT. HEDERA HASHGRAPH SHALL NOT BE LIABLE FOR
# ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
# DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
#

# This script provides a convenient wrapper for launching the signed state comparison utility,
# a tool maintained by the platform team to find the differences between two signed states.

# The location were this script can be found. This path is used
# to find the location of swirlds.jar, so that this script is not
# sensitive to the current working directory when it is called.
SCRIPT_PATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 || exit ; pwd -P )"
JAR_PATH="${SCRIPT_PATH}/../../sdk/swirlds.jar"

MAIN_CLASS_NAME='com.swirlds.platform.state.signed.SignedStateComparisonUtility'

# Example JVM arguments:
#    To increase memory to 32 gigabytes:
#        export SWIRLDS_JVM_ARGS='-Xmx32g'
#    To enable a debugger to attach to the process:
#        export SWIRLDS_JVM_ARGS='-agentlib:jdwp=transport=dt_socket,address=8888,server=y,suspend=y'
echo "To specify arguments passed to the JVM, set the environment variable SWIRLDS_JVM_ARGS."
echo "Current JVM arguments = \"${SWIRLDS_JVM_ARGS}\"."

# shellcheck disable=SC2086
java $SWIRLDS_JVM_ARGS -cp "${JAR_PATH}" $MAIN_CLASS_NAME "$@"
