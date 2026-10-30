#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Shows how the CPU time of running consensus nodes splits across the consensus-layer components.
#
# What it is for: a JMH benchmark measures one piece of code in isolation; it does not show where a running node
# spends its CPU time as a whole. Java Flight Recorder (JFR), the profiler built into the JVM, records a running
# program's CPU samples: at regular intervals, the call stack of a running thread. This script assigns each sample to
# a component and prints each component's share of the samples: event intake, event creation, hashgraph/consensus,
# gossip, PCES (the stream of events written to disk), state/signatures, app/transactions, metrics, and wiring. Use it
# to find the component worth optimizing, or to see whether a change moved CPU time from one component to another.
#
# Usage: jfr-components.sh <file.jfr>...
#
# The recordings come from running nodes, for example from a Sloth run (platform-sdk/consensus-sloth) with profiling
# enabled: ConsensusLayerBenchmark.runBenchmark then records one file per node in the node's output directory. The
# samples of several files are added up.
#
# A sample counts toward the innermost method on its stack (the one closest to where the sample was taken) that lies
# in an org.hiero, com.swirlds, or com.hedera package and belongs to a component. Time in JDK, crypto, and
# serialization code thus counts toward the component that called it;
# the crypto or serialization code on the way is shown in brackets. Samples without such a method are
# "<unattributed>". Uses the jfr tool from JAVA_HOME if set, otherwise from PATH.
J="${JAVA_HOME:+$JAVA_HOME/bin/}jfr"
for f in "$@"; do
  "$J" print --events jdk.ExecutionSample --stack-depth 96 "$f"
done | awk '
  function bucket(fr) {
    if (fr ~ /event\.intake/) return "intake"
    if (fr ~ /event\.creator/) return "event creation"
    if (fr ~ /hashgraph/) return "hashgraph/consensus"
    if (fr ~ /\.gossip|\.sync|broadcast|shadowgraph/) return "gossip"
    if (fr ~ /\.pces/) return "pces"
    if (fr ~ /\.state|StateSignature|iss|platformstate/) return "state/signatures"
    if (fr ~ /transaction|\.sloth\./) return "app/transactions"
    if (fr ~ /\.metrics|Stats[A-Z]|statistics/) return "metrics"
    if (fr ~ /wiring/) return "wiring"
    return ""
  }
  function helper(fr) {
    if (fr ~ /Libsodium|base\.crypto|\.crypto\./) return "crypto"
    if (fr ~ /pbj|StreamingData|OutputStream|InputStream|Bytes|ProtoWriter|Hash/) return "serialization/hashing"
    return ""
  }
  /^jdk.ExecutionSample/ { inStack=0; comp=""; help=""; total++; next }
  /stackTrace = \[/ { inStack=1; next }
  inStack && /^[[:space:]]*\]/ {
    inStack=0
    if (comp=="") comp="<unattributed>"
    key=comp (help!="" ? " [" help "]" : ""); c[key]++; next
  }
  inStack && comp=="" && /(org\.hiero|com\.swirlds|com\.hedera)\./ {
    line=$0; sub(/^[[:space:]]+/, "", line); sub(/\(.*/, "", line)
    h=helper(line); if (h!="" && help=="") help=h
    b=bucket(line); if (b!="") comp=b
  }
  END { for (k in c) printf "%6d %5.1f%% %s\n", c[k], 100*c[k]/total, k }
' | sort -rn
