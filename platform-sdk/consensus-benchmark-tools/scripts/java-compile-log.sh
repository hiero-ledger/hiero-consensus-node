#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# THROWAWAY PROBE for #27940, never merged: starts the JVM of a JMH fork with a compile log, so that the forks of any
# benchmark write one log each without a change to the benchmark. The logs go into the results directory of the
# calibration workflow, as compile-pid<process id>.log.
#
# Usage: pass it to JMH as the JVM of the forks, from the repository's root:
#   -jvm platform-sdk/consensus-benchmark-tools/scripts/java-compile-log.sh
#
# JMH also starts this JVM once per run to read its properties; only the forks get a log.
set -o errexit -o nounset

java="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
if [[ " $* " == *" org.openjdk.jmh.runner.ForkedMain "* ]]; then
  exec "${java}" -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation \
    "-XX:LogFile=${RESULTS_DIR:-build/benchmark-calibration}/compile-%p.log" "$@"
fi
exec "${java}" "$@"
