#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Summarizes how long events take through a consensus node and how long the network takes to reach consensus, from
# the metrics of a Sloth run.
#
# What it is for: Sloth (platform-sdk/consensus-sloth) runs a small network of consensus nodes under load, and every
# node writes its metrics to metrics.txt. This script averages the metrics that describe latency and throughput over
# the end of the run and over all nodes, in one table. Use it to compare two Sloth runs, for example before and after a
# change, or to find the stage of event processing where events wait. The metrics:
#   - eventDelay_<n>_<stage>_<origin>: the time from receiving an event until after stage n of the node's event
#     processing (hashing, validation, deduplication, ...), on average and at most (MAX), in microseconds. The origin
#     is gossip for events from other nodes and runtime for the node's own events. EventPipelineTracker defines them.
#   - sec*: consensus timings in seconds, for example secC2C, the time from creating an event to knowing its
#     consensus order. ConsensusMetricsImpl and ConsensusEngineMetrics describe each one.
#   - rounds_per_sec, events_per_sec, cEvents_per_sec: consensus rounds, events received, and events created per
#     second.
#
# Usage: sloth-stages.sh <run dir> [lastN]
#
# The run dir contains node-*/data/stats/metrics.txt, for example
# platform-sdk/consensus-sloth/build/container/ConsensusLayerBenchmark/benchmarkBaseline. For each metric, the script
# prints the mean over all nodes of the mean of each node's last N samples (default 10, about the last 30 s of a run).
dir=$1
n=${2:-10}
pattern='^(eventDelay_[0-9]+_[A-Za-z]+_(gossip|runtime)(MAX)?|secC2C|secC2R|secR2C|secC2RC|secR2F|secR2nR|secSC2T|secOR2T|secGossipRoundtrip|rounds_per_sec|events_per_sec|cEvents_per_sec)\{'
for f in "$dir"/node-*/data/stats/metrics.txt; do
  grep -E "$pattern" "$f" | sed 's/{[^}]*}//' | awk -v n="$n" '
    { name=$1; c[name]++; v[name, c[name]]=$2 }
    END { for (k in c) { s=0; m=0; for (i=c[k]-n+1; i<=c[k]; i++) if (i>0) { s+=v[k,i]; m++ } ; printf "%s %.6f\n", k, s/m } }'
done | awk '{ s[$1]+=$2; c[$1]++ } END { for (k in s) printf "%-40s %14.6f\n", k, s[k]/c[k] }' | sort -V
