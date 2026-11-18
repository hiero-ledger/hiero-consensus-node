# Metric topics

Topics group metrics by what they are about. They add a level between category and family:
category → topic → family → metric. The topics drive the selection menu (`catalog.py OUT_DIR --menu`) and the
report's sidebar.

They also fix up the CSV's own categories:
- **`internal` is not a real category.** The node uses it as a catch-all, so every `internal` metric is placed in the
  topic and category it actually belongs to.
- **Tiny categories are folded in:** `bpss` (bytes sent per peer) and `ping` go to platform's Network topic,
  `platform:info` goes to platform, and `app_` goes to app.

Each metric keeps its CSV category internally, for reading the data.

## Format

- **Heading:** `## topic: <category> / <topic name>`. The category is where the topic is shown. Topics appear in the
  menu and the sidebar in the order of this file, so put the more commonly used topics first.
- **Description:** one line under the heading.
- **Selectors:** a fenced block with one selector per line, using the selection-file syntax (see `scripts/catalog.py`).
  `names: a, b, c` matches exact names, which is the safest choice here.
- **Optional `from:` line:** inside the block, `from: <csv category>[, ...]` limits matching to those CSV categories.
  Use it whenever a pattern could catch metrics from another category.
- **First match wins:** a metric belongs to the first topic that selects it.
- **Unmatched metrics:** anything no topic selects goes to an "Other" topic in its CSV category.
  `catalog.py OUT_DIR --menu` lists any "Other" metrics, so new metrics can be given a topic here.

## topic: platform / Health & status
Platform status, health checks, and how much of the network is healthy.
```
from: platform
names: PlatformStatus, healthy, degraded, unhealthyDuration, healthyNetworkFraction
```

## topic: platform / Consensus latency
End-to-end latencies (seconds) from event creation or receipt to consensus and handling.
```
from: platform, internal
names: secC2C, secC2R, secC2RC, SecC2H, secR2C, secR2F, secR2nR, secGossipRoundtrip, secOR2T, secSC2T
```

## topic: platform / Consensus progress
Rounds, voting, witnesses, consensus time, and the consensus engine's phases.
```
from: platform, internal
names: rounds_per_sec, judgesWeight, consensusTime, consensusTimeDeviation, coinR, eventsPerRound, roundIncEqParents, timeFracDot, uptimeComputationTime
names: numVotingRounds, numVotingRoundsMAX, numEventsAddedUntilConsensus, numEventsAddedUntilConsensusMAX
names: graphDepthUntilConsensus, graphDepthUntilConsensusMAX, witnessesSeen, witnessesSeenMAX
name: consensus_fraction_*
name: consensus_time_*
```

## topic: platform / Transactions
Transactions received, submitted, buffered, handled, and their latency.
```
from: platform, internal
names: trans_per_sec, trans_per_event, trans, transH_per_sec, secTransH, staleTransactions, preHandleMicros
names: bytes_per_sec_trans, bytes_per_trans, acceptedAppTransactions, rejectedAppTransactions, submittedPlatformTransactions
names: bufferedTransactions, bufferedPriorityTransactions
name: transactionLatency_*
```

## topic: platform / Scheduler queues
Backlog and busy time of the platform's task schedulers (components).
```
from: platform
name: *_unhandled_task_count
name: *_busy_fraction
```

## topic: platform / Event creation
How this node creates events: rate, phases, parent choice, tipset scores.
```
from: platform
names: cEvents_per_sec, createdEventParents, tipsetAdvancement, selfishness, opAgeDiff
name: eventCreation_fraction_*
name: tipsetParent*
name: pityParent*
name: futureEventBuffer_eventCreator_*
```

## topic: platform / Event intake & validation
Events received, duplicates, invalid or orphaned events, branching, and events in memory.
```
from: platform, internal
names: events_per_sec, dupEv_per_sec, dupEvPercent, eventsFailedSignatureValidation, missingParents, parentBirthRoundMismatch, timeCreatedMismatch
names: orphanBufferSize, indicatorsWaitingForExpiry, branchingEvents, branchingNodeCount, branchingWeightFraction
names: eventRecRate, eventsInMem, staleEvents
name: eventsWith*
name: futureEventBuffer_consensus_*
```

## topic: platform / Event pipeline latency
Per-stage delay and latency of events through the intake pipeline (hashing → validation → … → PCES).
```
from: platform
name: eventDelay_*
name: eventLatency_*
```

## topic: platform / Gossip & sync
Syncs with peers: rates, permits, reasons for not syncing, phase timing, broadcast.
```
from: platform, internal
names: acceptedSyncRequests_per_sec, incomingSyncRequests_per_sec, outgoingSyncRequests_per_sec, syncs_per_sec, syncs_in_progress
names: knownSetSize, multiTips_per_sync, tips_per_sync, syncIndicatorDiff, syncEventsHeldBack, syncFilterTime, syncRoundLag
names: farFutureBroadcastEventsRejected, farFutureSyncEventsRejected
name: syncPermits*
name: doNotSync*
name: sync_phase_*
name: sync_per_*
name: ev_per_*
name: broadcast*
```

## topic: platform / Network
Connections, ping, bytes sent per peer, disconnects, and the RPC layer.
```
from: platform, internal, bpss, ping
names: conns, ping, bytes_per_sec_sent, rpcReadThrottled
name: ping_us_*
name: bytes_per_sec_sent_*
name: disconnects_per_sec_*
name: reconnectRejections_per_sec_*
name: rpc*
```

## topic: platform / Signed state & ISS
Signing, hashing, writing and deleting signed states, and ISS detection.
```
from: platform, internal
names: averageTimeToFullySignState, roundSup, sigStateHash, sstatesSigned_per_sec, stateSignatureAge, stateSignaturesGathered_per_sec
names: statesIneligibleForDeletion, timeToDeleteState, totalNeverSignedDiskStates, totalNeverSignedStates, unsignedStates
names: stateToDisk, writeStateToDisk, writeMerkleRootToDisk, stateCopyMicros
names: countInMemorySignedState, oldestSignedStateSeconds, issCount, issWeight
```

## topic: platform / PCES (preconsensus event stream)
Preconsensus event files and writes, and the event stream queues.
```
from: platform, platform:info
name: pces*
name: preconsensusEvent*
names: eventStreamQueueSize, hashQueueSize
```

## topic: platform / JVM & host resources
CPU, memory, threads, and disk on the node's machine.
```
from: platform, internal
names: cpuLoadSys, directMemInMB, directMemPercent, memFree, memMax, memTot, proc, threads, recycled_file_count
names: DiskspaceFree, DiskspaceUsed, DiskspaceWhole
```

## topic: platform / Node info
This node's id and the roster size.
```
from: platform:info
names: memberID, members
```

## topic: app / Throttles
Instantaneous use of the consensus (cons*) and HAPI throttle buckets.
```
from: app
name: cons*PercentUsed
name: hapi*PercentUsed
name: SmartContractService:OpsDuration_TxnsThrottled
```

## topic: app / Entity utilization
Count, maximum and percent used for each entity type (accounts, tokens, topics, …).
```
from: app
re: ^(accounts|airdrops|contracts|files|nfts|nodes|schedules|storageSlots|tokenAssociations|tokens|topics)(Used|PercentUsed|MaxNumber)$
```

## topic: app / Smart contracts
Smart contract service: durations, gas, rejections, system contract calls.
```
from: app
name: SmartContractService:*
```

## topic: app / hinTS signing
Aggregated hinTS signatures.
```
from: app
name: hints*
```

## topic: app / Block node rewards
Block node reward eligibility.
```
from: app, app_
names: blockNodeRewardsEligibleCount
```

## topic: blockStream / Block buffer
Blocks held in the block buffer, acknowledgements, and saturation.
```
from: blockStream
name: buffer_*
```

## topic: blockStream / Connections
Block node connections: opened, closed (with reasons), errors, latencies.
```
from: blockStream
name: conn_*
```

## topic: blockStream / Requests sent
Requests sent to block nodes, including endStream requests.
```
from: blockStream
name: connSend_*
```

## topic: blockStream / Responses received
Responses received from block nodes, including endStream responses.
```
from: blockStream
name: connRecv_*
```

## topic: blockStream / Record hashes
Wrapped record hashes file coverage.
```
from: blockStream
name: recordHashes_*
```

## topic: block / Block size limits
Max block size throttle, circuit breaker, and indirect proofs.
```
from: block
name: *
```

## topic: crypto / Signature verification
Signature verification rate, batches, queues, workers, intake, and totals.
```
from: crypto
re: ^(Sig|TtlSig|MaxSig|MinSig|PlatSig)
```

## topic: crypto / Digests
Digest (hash) computation rate, batches, queues, workers, and totals.
```
from: crypto
re: ^(Dig|TtlDig|MaxDig|MinDig)
```

## topic: merkle_db / Compactions
Compaction time and space saved per level for hashes, leaf keys, and leaves.
```
from: merkle_db
name: ds_compactions_*
```

## topic: merkle_db / Files
Store file counts and sizes, in total and per level.
```
from: merkle_db
name: ds_files_*
```

## topic: merkle_db / Flushes
Data written, deleted and file sizes created during flushes.
```
from: merkle_db
name: ds_flushes_*
```

## topic: merkle_db / Off-heap memory
Off-heap memory used by indexes and lists.
```
from: merkle_db
name: ds_offheap_*
```

## topic: merkle_db / Reads & instances
Hash, leaf key and leaf reads, and the number of MerkleDb instances.
```
from: merkle_db
name: ds_reads_*
names: merkledb_count
```

## topic: virtual_map / Lifecycle
Virtual map pipeline: flushes, hashing, merging, backpressure, cache.
```
from: virtual_map
name: vmap_lifecycle_*
```

## topic: virtual_map / Queries & size
Entities added, read, updated and removed, and the map size.
```
from: virtual_map
name: vmap_queries_*
names: vmap_size_state
```

## topic: Reconnect / Reconnect
Reconnects as sender or receiver: starts, ends, and durations.
```
from: Reconnect
name: *
```
