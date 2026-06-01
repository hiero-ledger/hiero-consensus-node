# Local ReconnectBench Averaged Cluster-Profile Results

Date: `2026-05-15`

Purpose: capture the first local diagnostic runs using one averaged cluster-derived network profile across traversal
modes. These runs reused the existing saved teacher/learner state and did not regenerate benchmark data.

## Configuration

Gradle task:

```text
./gradlew :swirlds-benchmarks:jmhReconnect \
  -PnetworkProfile=REALISTIC \
  -PnetworkLatencyMicroseconds=68 \
  -PnetworkBandwidthMegabitsPerSecond=171 \
  -PnetworkInflightBytesLimit=134217728 \
  -PnumFiles=5000 \
  -PnumRecords=10000
```

Traversal mode was changed in `platform-sdk/swirlds-benchmarks/settings.txt` before each run.

Network profile:

```text
networkProfile=REALISTIC
networkLatencyMicroseconds=68
networkBandwidthMegabitsPerSecond=171
networkInflightBytesLimit=134217728
```

Observed resolved network config in the benchmark log:

```text
latencyNanos=68000
bandwidthBytesPerSecond=21375000
inflightBytesLimit=134217728
```

State:

```text
benchmarkData=/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/platform-sdk/swirlds-benchmarks/data
saveDataDirectory=true
teacher state restored from data/ReconnectBench/teacher/saved0
learner state restored from data/ReconnectBench/learner/saved0

Starting Tree metadata=VirtualMapMetadata[firstLeafPath=49999998,lastLeafPath=99999996,size=49999999]
Desired Tree metadata=VirtualMapMetadata[firstLeafPath=50121145,lastLeafPath=100242290,size=50121146]
```

JMH/runtime:

```text
Build: 0.75.0-SNAPSHOT (9be3c24)
JDK: 25.0.2
Heap args: -Xms24g -Xmx24g -XX:+AlwaysPreTouch
Benchmark mode: SingleShotTime
Warmup: 0
Measurement iterations: 1
Forks: 1
```

Important caveat: the JMH parameter output still lists `teacherAddProbability=0.05`,
`teacherModifyProbability=0.05`, and `teacherRemoveProbability=0.05`, but the saved maps were restored. Since data was
not regenerated, those probability parameters did not define a new state shape for these runs.

## Run Summary

| Run | Traversal mode | Score | Capacity waits | Notes |
| --- | --- | ---: | --- | --- |
| 1 | `pullTopToBottom` | `184.958 s/op` | zero in both directions | first top-to-bottom run |
| 2 | `pullParallelSync` | `174.218 s/op` | zero in both directions | parallel run |
| 3 | `pullTopToBottom` | `187.698 s/op` | zero in both directions | top-to-bottom repeat |
| 4 | `pullTwoPhasePessimistic` | `241.197 s/op` | learner-to-teacher cap hit | two-phase pessimistic run |

Derived comparison:

```text
pullTopToBottom average: (184.958 + 187.698) / 2 = 186.328 s/op
pullTopToBottom repeat spread: 2.740 s
pullParallelSync: 174.218 s/op
pullTwoPhasePessimistic: 241.197 s/op
pullParallelSync vs top average: 12.110 s faster, about 6.5%
pullTwoPhasePessimistic vs top average: 54.869 s slower, about 29.4%
pullTwoPhasePessimistic vs pullParallelSync: 66.979 s slower, about 38.4%
```

## Run 1: `pullTopToBottom`

```text
Score: 184.958 s/op

Reconnect stats:
transfersFromTeacher=34535787
transfersFromLearner=34535787
internalHashes=10598304
internalCleanHashes=5004965
internalData=10598305
internalCleanData=5004965
leafHashes=23937482
leafCleanHashes=18951223
leafData=23937482
leafCleanData=18951223

Network teacherToLearner:
bytesWritten=1175008844
bytesRead=1175008844
maxInflightBytes=6962319
writeCalls=4014300
writeRanges=4014300
readCalls=4960373
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=43475
emptyReadWaitNanos=94421258260
arrivalWaitCount=901008
arrivalWaitNanos=55459215721

Network learnerToTeacher:
bytesWritten=2175754589
bytesRead=2175754589
maxInflightBytes=39429873
writeCalls=5091936
writeRanges=5091936
readCalls=5981634
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=15561
emptyReadWaitNanos=41894669792
arrivalWaitCount=922653
arrivalWaitNanos=62577165258
```

## Run 2: `pullParallelSync`

```text
Score: 174.218 s/op

Reconnect stats:
transfersFromTeacher=34356617
transfersFromLearner=34356617
internalHashes=25078422
internalCleanHashes=16159993
internalData=25078423
internalCleanData=16159993
leafHashes=9278194
leafCleanHashes=4291935
leafData=9278194
leafCleanData=4291935

Network teacherToLearner:
bytesWritten=1165671114
bytesRead=1165671114
maxInflightBytes=4463593
writeCalls=4391730
writeRanges=4391730
readCalls=5446662
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=48640
emptyReadWaitNanos=75027511832
arrivalWaitCount=1165361
arrivalWaitNanos=64201819153

Network learnerToTeacher:
bytesWritten=2164466879
bytesRead=2164466879
maxInflightBytes=10567381
writeCalls=5639500
writeRanges=5639500
readCalls=6761268
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=17482
emptyReadWaitNanos=33136823943
arrivalWaitCount=1223404
arrivalWaitNanos=66707023690
```

## Run 3: `pullTopToBottom`

```text
Score: 187.698 s/op

Reconnect stats:
transfersFromTeacher=34535787
transfersFromLearner=34535787
internalHashes=10598304
internalCleanHashes=5004965
internalData=10598305
internalCleanData=5004965
leafHashes=23937482
leafCleanHashes=18951223
leafData=23937482
leafCleanData=18951223

Network teacherToLearner:
bytesWritten=1175008844
bytesRead=1175008844
maxInflightBytes=2699848
writeCalls=3886863
writeRanges=3886863
readCalls=4751151
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=41324
emptyReadWaitNanos=98699252189
arrivalWaitCount=863235
arrivalWaitNanos=56817248536

Network learnerToTeacher:
bytesWritten=2175754589
bytesRead=2175754589
maxInflightBytes=37611137
writeCalls=5172517
writeRanges=5172517
readCalls=5969684
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=17666
emptyReadWaitNanos=45240487434
arrivalWaitCount=832330
arrivalWaitNanos=57792502811
```

## Run 4: `pullTwoPhasePessimistic`

```text
Score: 241.197 s/op

Reconnect stats:
transfersFromTeacher=45176448
transfersFromLearner=45176448
internalHashes=15733165
internalCleanHashes=12099598
internalData=15733166
internalCleanData=12099598
leafHashes=29443282
leafCleanHashes=24457023
leafData=29443282
leafCleanData=24457023

Network teacherToLearner:
bytesWritten=1338538303
bytesRead=1338538303
maxInflightBytes=2811928
writeCalls=2827145
writeRanges=2827145
readCalls=4037348
capacityWaitCount=0
capacityWaitNanos=0
emptyReadWaitCount=65463
emptyReadWaitNanos=143732845300
arrivalWaitCount=1217103
arrivalWaitNanos=68063369841

Network learnerToTeacher:
bytesWritten=2846116232
bytesRead=2846116232
maxInflightBytes=134217728
writeCalls=4399061
writeRanges=4399061
readCalls=5565246
capacityWaitCount=110122
capacityWaitNanos=16448179343
emptyReadWaitCount=20202
emptyReadWaitNanos=53979536637
arrivalWaitCount=1133162
arrivalWaitNanos=71007752034
```

## Immediate Observations

- `pullParallelSync` won this four-run starter check by about `12.1s` against the average of the two
  `pullTopToBottom` runs.
- The top-to-bottom repeat spread was `2.74s`, so the `12.1s` gap is larger than the observed top-to-bottom repeat
  noise in this small sample.
- `networkInflightBytesLimit=128 MiB` behaved as a neutral cap for `pullTopToBottom` and `pullParallelSync`:
  - all capacity waits were `0` in those runs;
  - max in-flight bytes stayed well below the cap in all directions.
- `pullTwoPhasePessimistic` did hit the cap in the learner-to-teacher direction:
  - `maxInflightBytes=134217728`;
  - `capacityWaitCount=110122`;
  - `capacityWaitNanos=16448179343`, about `16.4s`.
- The traversal modes still produce different reconnect work shapes:
  - `pullTopToBottom` transfers more leaf hashes/data and fewer internal hashes/data;
  - `pullParallelSync` transfers more internal hashes/data and fewer leaf hashes/data.
  - `pullTwoPhasePessimistic` transfers the most total work and the most bytes in both network directions.
- Because this reused the existing `50M` saved state and the old `0.05/0.05/0.05` generated divergence, this is a
  network-profile diagnostic, not a cluster-shaped state/divergence validation.
