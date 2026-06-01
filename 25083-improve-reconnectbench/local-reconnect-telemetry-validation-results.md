# Local Reconnect Artifact Extraction Results

Date: `2026-05-22`

## Scope

This document validates what can be extracted from the local subprocess reconnect artifacts with respect to the
non-network requirements in `cluster-reconnectbench-run-protocol.md`.

Artifact root:

```text
/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node-reconnect-calibration-telemetry/hedera-node/test-clients/build/hapi-test
```

This is not a `ReconnectBench` result document. The benchmark output is intentionally not used here. The point is to
verify that ordinary node logs, node metrics, test output, and configuration files contain the fields needed for cluster
artifact analysis, excluding:

- network evidence;
- fallback diagnostics.

The log prefixes in these artifacts use local wall-clock time. The local timezone for this run was UTC+03, so the UTC
times below subtract three hours from the log prefix.

## Sources Used

- `node0/output/test-clients.log` for test flow, workload mix, stop/restart timing, and final status.
- `node2/output/swirlds.log` for learner reconnect lifecycle, state ranges, reconnect counters, and status.
- `node3/output/swirlds.log` for matching teacher reconnect lifecycle and state context.
- `node2/data/stats/MainNetStats2.csv` for learner reconnect role counters, reconnect transfer counters, and
  `vmap_size_state`.
- `node3/data/stats/MainNetStats3.csv` for teacher reconnect role counters and `vmap_size_state`.
- `node*/settingsUsed.txt` for traversal mode and metrics/config context.

## Executive Summary

The local subprocess artifacts contain the core non-network evidence expected by the cluster protocol:

- confirmed traversal mode;
- confirmed learner node and teacher peer;
- first learner reconnect start/end and learner duration;
- reconnect transfer and clean/dirty work-shape counters;
- learner/teacher state size from existing VirtualMap path ranges;
- workload profile while the learner was behind;
- a clear note that no later reconnect window occurred in this local run.

The local artifacts do not contain cluster-only run context such as commit SHA, image tag/digest, or a cluster baseline
restore identifier. They also do not contain network evidence, which is excluded from this document by scope.

Existing logs and metrics are enough for this local extraction check. No production reconnect telemetry changes are
justified by this evidence.

## Extracted Analysis Row

```text
Traversal mode: pullTopToBottom
Commit: missing from hapi-test artifacts
Image: missing from hapi-test artifacts
Learner node: 2
Teacher node: 3
First reconnect start UTC: 2026-05-22T10:50:51.924Z
First reconnect end UTC: 2026-05-22T10:50:52.482Z
Learner duration: 558 ms
Teacher reconnect context present: yes
Reconnect stats present: yes
Teacher/learner state size present: yes
Workload profile present: yes
RTT evidence present: excluded from this local document
Bandwidth evidence present: excluded from this local document
TCP/window evidence present: excluded from this local document
Later reconnects observed: no
Run accepted for local extraction validation: yes
Run accepted for cluster calibration: no
Reason if not accepted for cluster calibration: local subprocess run, one traversal mode, no network evidence, no cluster script context
```

## Protocol Coverage

| Protocol requirement | Local result | Evidence |
| --- | --- | --- |
| Traversal mode | Extracted | `node*/settingsUsed.txt:727`, `virtualMap.reconnectMode=pullTopToBottom` |
| Learner node ID | Extracted | `node2/output/swirlds.log:296`, receiver `nodeId=2` |
| Teacher peer node ID | Extracted | `node2/output/swirlds.log:296`, `otherNodeId=3` |
| Learner reconnect start UTC | Extracted | `node2/output/swirlds.log:296`, local `13:50:51.924`, UTC `10:50:51.924Z` |
| Learner reconnect end UTC | Extracted | `node2/output/swirlds.log:323`, local `13:50:52.482`, UTC `10:50:52.482Z` |
| Learner reconnect duration | Extracted from lifecycle timestamps | `558 ms` from start/end log prefixes |
| Transfers from teacher | Extracted | `node2/output/swirlds.log:320`, `transfersFromTeacher=771`; CSV confirms `transfersFromTeacherTotal=771` |
| Transfers from learner | Extracted | `node2/output/swirlds.log:320`, `transfersFromLearner=767`; CSV confirms `transfersFromLearnerTotal=767` |
| Internal/leaf clean and total counters | Extracted | `node2/output/swirlds.log:320`; CSV confirms the same totals |
| Clean/dirty work shape | Derived | Dirty values computed from total minus clean counters |
| Learner state size at reconnect start | Extracted from existing path range | `node2/output/swirlds.log:299`, range `[1009, 2018]`, size `1010` |
| Learner state size at reconnect end | Extracted from existing path range | `node2/output/swirlds.log:303` and `:336`, range `[1228, 2456]`, size `1229` |
| Reconnect status | Extracted | learner finish at `node2/output/swirlds.log:323`, `RECONNECT_COMPLETE` at `:340`, test passed in `node0/output/test-clients.log:47` |
| Teacher context | Extracted | `node3/output/swirlds.log:400` start, `:413` state, `:436` finish |
| Run context from script/test output | Partially extracted | local test output provides network size, learner stop/restart flow, workload, and local config; commit/image/cluster baseline are missing |
| Divergence strategy inputs | Extracted coarsely | learner/teacher state gap, behind window, workload mix, round gap, and reconnect clean/dirty counters |
| Later reconnects | Extracted | only one receiver start/finish and one sender start/finish were present across `node*/output/swirlds.log` |

## Local Subprocess Test Result

The artifacts came from:

```text
./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.MixedOpsNodeDeathReconnectTest"
```

JUnit result:

```text
tests=1
failures=0
errors=0
timestamp=2026-05-22T10:47:12.246Z
time=234.886 seconds
testcase="RestartMixedOps as written"
```

The test-client log reports final status:

```text
node0/output/test-clients.log:47 RestartMixedOps - final status: PASSED!
```

## Run Context Extracted Locally

```text
networkSize=4
mode=pullTopToBottom
learnerCandidate=2
teacherCandidate=3
workloadProfile=RestartMixedOps mixed provider run
transactionMix=crypto, token, consensus, file, and schedule operations; exact provided-op breakdown below
learnerBehindDuration=191.733 seconds from stopped node to restart command
controlledSleepWhileLearnerDown=180000 ms
workloadWhileLearnerDown=500 provided ops over 11.718 seconds, approximately 42.7 ops/s inferred from log timestamps
configSummary=node*/settingsUsed.txt plus node*/data/config/*
baseline=local saved state data/saved/com.hedera.services.ServicesMain/2/123/102
```

Config values present in all four `settingsUsed.txt` files:

```text
metrics.csvFileName=MainNetStats
metrics.csvOutputFolder=data/stats
prometheus.endpointEnabled=true
state.saveStatePeriod=30
sync.syncProtocolPermitCount=2
virtualMap.reconnectMode=pullTopToBottom
transaction.maxTransactionBytesPerEvent=2621440
transaction.transactionMaxBytes=2621440
hedera.transaction.maxTransactionBytesPerEvent=33554432
```

The local baseline is not a cluster restore identifier. It is still useful local evidence:

```text
node2/output/swirlds.log:166 wrote local round 102 periodic snapshot
node2/output/swirlds.log:178 loaded data/saved/com.hedera.services.ServicesMain/2/123/102
node2/output/swirlds.log:182 loaded saved state round=102, consensusTimestamp=2026-05-22T10:47:31.453680Z
```

## Workload While Learner Was Behind

The learner process stopped at:

```text
node0/output/test-clients.log:25 2026-05-22 13:47:34.722 Stopped node 'node3'
```

`node3` in the HAPI test maps to platform node ID `2` and working directory `build/hapi-test/node2`:

```text
node0/output/test-clients.log:35 NodeMetadata[nodeId=2, name=node3, ... workingDir=build/hapi-test/node2]
```

The explicit sleep while the learner was down was:

```text
node0/output/test-clients.log:29 Sleeping for 180000ms
node0/output/test-clients.log:30 finished HapiSpecSleep{timeMs=180000}
```

The mixed workload executed while the learner was still down produced 500 provided operations:

```text
CryptoTransfer=172
ConsensusSubmitMessage=73
TokenAssociateToAccount=46
TokenGetInfo=45
TransactionGetRecord=33
ConsensusGetTopicInfo=31
ScheduleCreate=24
CryptoGetInfo=17
CryptoUpdate=11
TransactionGetReceipt=7
TokenAccountWipe=6
TokenCreate=5
TokenDissociateFromAccount=5
TokenGrantKycToAccount=4
TokenRevokeKycFromAccount=5
TokenUnfreezeAccount=4
CryptoCreate=2
CryptoGetAccountRecords=2
ConsensusCreateTopic=2
FileGetContents=2
TokenMint=2
FileGetInfo=1
TokenBurn=1
```

The provider run began at `13:50:34.733` and ended at `13:50:46.451`, so the local artifact implies approximately
`42.7` provided ops/s for that provider run. This is an inferred local rate, not an explicit configured rate.

## Learner Reconnect Window

The learner fell behind relative to peers at:

```text
node2/output/swirlds.log:289 SELF_FALLEN_BEHIND local latestConsensusRound=127 remote latestConsensusRound=1398
node2/output/swirlds.log:290 SELF_FALLEN_BEHIND local latestConsensusRound=127 remote latestConsensusRound=1398
```

The accepted learner reconnect window is:

```text
start local: 2026-05-22 13:50:51.924
start UTC:   2026-05-22T10:50:51.924Z
end local:   2026-05-22 13:50:52.482
end UTC:     2026-05-22T10:50:52.482Z
duration:    558 ms
```

Lifecycle evidence:

```text
node2/output/swirlds.log:296 Starting reconnect in the role of the receiver, nodeId=2, otherNodeId=3, round=127
node2/output/swirlds.log:321 Finished synchronization, timeInSeconds=0.075
node2/output/swirlds.log:323 Finished reconnect in the role of the receiver, nodeId=2, otherNodeId=3, round=1391
node2/output/swirlds.log:340 Platform spent 647.0 ms in BEHIND. Now in RECONNECT_COMPLETE
```

The `Finished synchronization` payload gives the synchronization section as `75 ms`. For calibration timing, use the full
learner lifecycle duration from receiver start to receiver finish: `558 ms`.

## Teacher Context

The matching teacher was node `3`, serving learner node `2`:

```text
node3/output/swirlds.log:399 OTHER_FALLEN_BEHIND local latestConsensusRound=1398 remote latestConsensusRound=127
node3/output/swirlds.log:400 Starting reconnect in the role of the sender, nodeId=3, otherNodeId=2, round=1391
node3/output/swirlds.log:436 Finished reconnect in the role of the sender, nodeId=3, otherNodeId=2, round=1391
```

Teacher lifecycle timing from log prefixes:

```text
teacher start UTC: 2026-05-22T10:50:52.133Z
teacher end UTC:   2026-05-22T10:50:52.483Z
teacher duration:  350 ms
```

Teacher state-size context:

```text
node3/output/swirlds.log:413 VirtualMapMetadata firstLeafPath=1228, lastLeafPath=2456
teacher state size at reconnect start/result = 2456 - 1228 + 1 = 1229
teacher state size at exact reconnect end = not logged as a separate field
```

The next teacher-side state-info log after reconnect shows later post-reconnect growth and should not be used as the
teacher end size for the reconnect window.

Teacher duration is supporting context only. The learner duration is the primary timing target.

## State Size And Divergence

Learner state size before reconnect comes from the existing learner path-range log:

```text
node2/output/swirlds.log:299 Building learner view for map with path range [1009, 2018]
learner size before reconnect = 2018 - 1009 + 1 = 1010
```

Teacher state size and learner state size after reconnect come from the existing reconnect-state and metadata logs:

```text
node2/output/swirlds.log:303 Init reconnect state: firstLeafPath: 1009 -> 1228, lastLeafPath: 2018 -> 2456
node2/output/swirlds.log:336 VirtualMapMetadata firstLeafPath=1228, lastLeafPath=2456
node3/output/swirlds.log:413 VirtualMapMetadata firstLeafPath=1228, lastLeafPath=2456
teacher/learner size at reconnect result = 2456 - 1228 + 1 = 1229
```

Coarse divergence shape:

```text
state size gap at reconnect = 1229 - 1010 = 219 records
round gap at fallen-behind detection = 1398 - 127 = 1271 rounds
workload shape = mixed, with state growth plus token/account/consensus modifications
```

This is enough for the protocol's coarse divergence classification. It does not attempt exact per-key divergence.

## Reconnect Work-Shape Counters

Learner-side counters from the existing `ReconnectMapMetrics` log:

```text
node2/output/swirlds.log:320
transfersFromTeacher=771
transfersFromLearner=767
internalHashes=195
internalCleanHashes=86
internalData=196
internalCleanData=86
leafHashes=568
leafCleanHashes=83
leafData=573
leafCleanData=84
```

The learner CSV confirms the same values at `2026-05-22 10:50:54 UTC`:

```text
node2/data/stats/MainNetStats2.csv:1538
startsReconnectAsReceiver=1
endsReconnectAsReceiver=1
vmap_size_state=1229
transfersFromLearnerTotal=767
transfersFromTeacherTotal=771
internalCleanDataTotal=86
internalCleanHashesTotal=86
internalDataTotal=196
internalHashesTotal=195
leafCleanDataTotal=84
leafCleanHashesTotal=83
leafDataTotal=573
leafHashesTotal=568
```

Derived dirty counters:

```text
internalDirtyHashes = 195 - 86 = 109
internalDirtyData = 196 - 86 = 110
leafDirtyHashes = 568 - 83 = 485
leafDirtyData = 573 - 84 = 489
```

Teacher CSV confirms the sender role at `2026-05-22 10:50:55 UTC`:

```text
node3/data/stats/MainNetStats3.csv:1599
startsReconnectAsSender=1
endsReconnectAsSender=1
vmap_size_state=1229
```

Exact reconnect byte totals are not required by the protocol and were not extracted.

## Later Reconnects

Across all node `swirlds.log` files, the only reconnect lifecycle entries were:

```text
node2 receiver start
node2 receiver finish
node3 sender start
node3 sender finish
```

No later learner reconnect was observed in this artifact set.

The state continued to grow after reconnect, which is expected because the test resumed workload:

```text
node2/data/stats/MainNetStats2.csv:1538 vmap_size_state=1229 at 2026-05-22 10:50:54 UTC
node2/data/stats/MainNetStats2.csv:1542 vmap_size_state=1482 at 2026-05-22 10:51:06 UTC
node3/data/stats/MainNetStats3.csv:1599 vmap_size_state=1229 at 2026-05-22 10:50:55 UTC
node3/data/stats/MainNetStats3.csv:1603 vmap_size_state=1482 at 2026-05-22 10:51:07 UTC
```

## Missing Or Not Applicable Fields

Missing from local artifacts:

- `commit`: no commit SHA was found in the `hapi-test` artifact bundle.
- `image`: no image tag or digest was found in the `hapi-test` artifact bundle.
- `baseline`: no cluster restore identifier exists in this local subprocess run; the local saved-state path and round are
  present instead.
- `transactionRate`: not explicitly configured in the captured output; `42.7 ops/s` is inferred from local test-client
  timestamps.
- three-mode traversal matrix: this local validation covers only `pullTopToBottom`.

Excluded by scope:

- RTT evidence;
- bandwidth evidence;
- TCP/window evidence;
- fallback diagnostics.

## Conclusion

For the non-network portions of the protocol, the local subprocess artifacts prove that existing logs and metrics are
enough to extract the required reconnect evidence. The remaining cluster-prep needs are run-context capture
(`commit`, `image`, and baseline identifier), traversal-mode orchestration, and network evidence collection. Production
reconnect code does not need additional logs or metrics for this extraction path.
