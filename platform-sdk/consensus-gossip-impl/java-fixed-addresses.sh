#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# THROWAWAY PROBE for #27940, never merged: starts the JVM with the randomization of its address space layout switched
# off, so that the heap, the code cache, the libraries, and the stacks lie at the same addresses in every fork.
#
# Usage: pass it to JMH as the JVM of the forks, from the repository's root:
#   -jvm platform-sdk/consensus-gossip-impl/java-fixed-addresses.sh
#
# Fails if setarch is missing or the kernel refuses the switch; JMH then reports that the fork did not start.
set -o errexit -o nounset

exec setarch "$(uname -m)" -R "${JAVA_HOME:+${JAVA_HOME}/bin/}java" "$@"
