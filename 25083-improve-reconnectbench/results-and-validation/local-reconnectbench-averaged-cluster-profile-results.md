# Local ReconnectBench Calibration Notes

Updated: `2026-06-01`

Purpose: preserve local `ReconnectBench` calibration and validation notes, grouped by run date.

Covered local calibration dates:

- `2026-05-04`: smoke and small-state validation.
- `2026-05-05`: `500K`/`50M` saved-state calibration and initial RTT/window sensitivity.
- `2026-05-06`: additional `50M` RTT/window crossover checks.
- `2026-05-15`: averaged cluster-profile local diagnostic run.

## 2026-05-04

Machine:

- Model: Mac15,9
- CPU: Apple M3 Max
- Memory: 48 GiB
- JVM: Temurin OpenJDK 25.0.2+10 LTS
- JMH JVM args: `-Xmx16g`

Smoke commands:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=LOOPBACK -PnumFiles=1 -PnumRecords=1000 --no-daemon
```

Result:

- Passed.
- Score: `0.153 s/op`.
- State size: `1 * 1000` requested; generated teacher size logged as `1022`, learner size logged as `999`.
- Traversal mode: `pullTopToBottom`.
- Network profile: `LOOPBACK`; resolved latency `0 ns`, bandwidth `Long.MAX_VALUE B/s`, in-flight limit `Integer.MAX_VALUE`.
- Reconnect stats: `transfersFromTeacher=1023`, `transfersFromLearner=1023`, `leafData=1022`, `leafCleanData=883`.
- Network stats: teacher-to-learner `33982` bytes; learner-to-teacher `64457` bytes.

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=500 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=131072 -PnumFiles=1 -PnumRecords=1000 --no-daemon
```

Result:

- Passed.
- Score: `0.135 s/op`.
- State size: `1 * 1000` requested; generated teacher size logged as `1022`, learner size logged as `999`.
- Traversal mode: `pullTopToBottom`.
- Network profile: `REALISTIC`; resolved latency `500000 ns`, bandwidth `125000000 B/s`, in-flight limit `131072`.
- Reconnect stats: `transfersFromTeacher=1023`, `transfersFromLearner=1023`, `leafData=1022`, `leafCleanData=883`.
- Network stats: teacher-to-learner `33982` bytes; learner-to-teacher `64457` bytes.

The first attempted smoke run failed inside the forked JMH benchmark with `NoClassDefFoundError` for
`com.swirlds.benchmark.reconnect.network.NetworkProfile`. Root cause was that the JMH merged jar packaged `src/jmh`
classes but not the simulator classes under `src/main`. The build now adds `com/swirlds/benchmark/reconnect/network/**`
to `jmhJarWithMergedServiceFiles`.

Traversal sanity commands used temporary settings:

```text
benchmark.saveDataDirectory=true
benchmark.benchmarkData=/tmp/reconnectbench-comparison-20260504
virtualMap.reconnectMode=pullTopToBottom
```

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnumFiles=1 -PnumRecords=10000 --no-daemon
```

Then only `virtualMap.reconnectMode` changed to `pullTwoPhasePessimistic`, and the same command was rerun. Finally,
`pullTopToBottom` was run again against the already-restored state to avoid comparing generated-state setup with
restored-state setup.

Results:

- `pullTopToBottom`, first run, generated state under `/tmp/reconnectbench-comparison-20260504`: `0.551 s/op`.
- `pullTwoPhasePessimistic`, restored the same state: `0.306 s/op`.
- `pullTopToBottom`, restored the same state: `0.702 s/op`.

Notes:

- The restored `pullTwoPhasePessimistic` run was faster than the restored `pullTopToBottom` run at this small `10000`
  record size, despite `pullTopToBottom` transferring fewer items (`7353` teacher/learner transfers versus `10044`).
- This contradicts the expected broad traversal ordering and should not be treated as traversal evidence. It is a
  small-state sanity result only; use larger states and repeated runs before drawing conclusions.
- The benchmark now clearly logs whether state is generated or restored, the traversal mode, resolved network profile,
  reconnect stats, and network byte counters.

## 2026-05-05

The `10000` record calibration was too small to evaluate traversal ordering. A `500K` state was the first useful sanity
size, using `numFiles=50`, `numRecords=10000`, `networkProfile=REALISTIC`, `500us` one-way latency, `1 Gbps`, and
`128 KiB` in-flight limit.

Results on the same saved state:

- `pullTopToBottom`: `7.262 s/op`.
- `pullTwoPhasePessimistic`: `7.247 s/op`.
- `pullTopToBottom` repeat: `7.462 s/op`.

The wall times were effectively tied, but the stats showed `pullTopToBottom` doing less work:

- `pullTopToBottom`: `371215` teacher/learner transfers; teacher-to-learner `12196096` bytes; learner-to-teacher
  `23386553` bytes.
- `pullTwoPhasePessimistic`: `447747` teacher/learner transfers; teacher-to-learner `13400260` bytes;
  learner-to-teacher `28208069` bytes.

Verification was then disabled for larger timed runs because result verification consumed several seconds of the `500K`
run and would distort traversal timing.

The next useful calibration state was `50M`, generated with `numFiles=5000`, `numRecords=10000`, `keySize=32`,
`recordSize=128`, `benchmark.verifyResult=false`, and `-Xms24g -Xmx24g -XX:+AlwaysPreTouch`. The saved state was reused
from `platform-sdk/swirlds-benchmarks/data/ReconnectBench`.

Baseline `REALISTIC` results with `500us` one-way latency, `1 Gbps`, and `128 KiB` in-flight:

- `pullTopToBottom`: first run `209.591 s/op`; repeated measured iterations mean `199.220 s/op`.
- `pullParallelSync`: first run `137.628 s/op`; repeated measured iterations mean `148.145 s/op`.
- `pullTwoPhasePessimistic`: `320.868 s/op`.

The result was initially surprising because `pullTopToBottom` was expected to win. However, stats showed that
`pullTopToBottom` and `pullParallelSync` transferred nearly the same bytes:

- `pullTopToBottom`: teacher-to-learner about `1.175 GB`; learner-to-teacher about `2.176 GB`.
- `pullParallelSync`: teacher-to-learner about `1.166 GB`; learner-to-teacher about `2.164 GB`.

This ruled out "parallel sent far less data" as the explanation.

Loopback isolation on the same saved state:

- `pullTopToBottom`: `174.125 s/op`.
- `pullParallelSync`: `167.323 s/op`.

The large `REALISTIC` gap mostly collapsed under `LOOPBACK`. This suggested that the network shape, not basic benchmark
wiring, was driving most of the unexpected ordering.

Temporary wait diagnostics were added to `SimulatedNetworkStats` during investigation. With baseline `REALISTIC`:

- `pullTopToBottom`: `192.481 s/op`; cumulative empty-read wait was about `212.6s` across both directions.
- `pullParallelSync`: `144.576 s/op`; cumulative empty-read wait was about `107.1s` across both directions.

These wait numbers overlap across threads and directions and should not be added to wall-clock time. The useful signal is
the ratio: `pullTopToBottom` spends much more time waiting for peer responses. This matches the traversal algorithms:
top-to-bottom waits for parent responses before sending some descendants, while parallel sync keeps more chunks in
flight pessimistically.

Sensitivity checks:

```text
Profile                                         pullTopToBottom   pullParallelSync
REALISTIC, 500us one-way, 128 KiB in-flight      192.481 s/op      144.576 s/op
REALISTIC, 500us one-way, 16 MiB in-flight       171.917 s/op      152.291 s/op
REALISTIC, 0us one-way, 128 KiB in-flight        180.798 s/op      145.142 s/op
LOOPBACK                                         174.125 s/op      167.323 s/op
```

Interpretation:

- The `128 KiB` in-flight cap is a major part of the `pullTopToBottom` slowdown. Raising the cap to `16 MiB` moved
  `pullTopToBottom` close to loopback.
- Removing the `500us` latency helped `pullTopToBottom`, but less than increasing the in-flight cap.
- `pullParallelSync` remained faster under the baseline and zero-latency constrained-window profiles because it keeps
  the reconnect pipeline fuller.
- `pullTopToBottom` may still win in real low-latency/high-window data-center deployments because it transfers less
  work and is less redundant. A local Latitude-style deployment can have private or provider-local networking that is
  not equivalent to the benchmark's default constrained `128 KiB` window. Latitude documents both global locations and
  different private-network sharing behavior by location, so a "Latitude network" is not one uniform latency/window
  profile. See <https://www.latitude.sh/docs/regions-locations> and <https://www.latitude.sh/locations>.
- Hedera mainnet-style comparisons should not use the same assumptions as a single data-center run. Mainnet consensus
  nodes are distributed across operators and endpoints, and current node addresses should be taken from the live address
  book or Hashscan as described by Hedera docs: <https://docs.hedera.com/hedera/networks/mainnet/mainnet-nodes>.
  Cross-continent links have much larger bandwidth-delay products: at `1 Gbps`, `100ms` RTT implies about `12.5 MiB`
  in flight, and `200ms` RTT implies about `25 MiB`. A `128 KiB` cap on those paths would model a severe window
  bottleneck, not a well-tuned WAN link.

Additional mainnet-style bandwidth-delay-product checks used the same `50M` saved state and `1 Gbps` bandwidth. These
runs swept `100ms` and `200ms` RTT with `13 MiB` and `25 MiB` in-flight caps:

```text
Profile                         pullTopToBottom   pullParallelSync   Result
100ms RTT, 13 MiB in-flight       189.255 s/op      161.473 s/op     parallel by 14.7%
100ms RTT, 25 MiB in-flight       195.280 s/op      172.962 s/op     parallel by 11.4%
200ms RTT, 13 MiB in-flight       204.070 s/op      208.892 s/op     top-to-bottom by 2.3%
200ms RTT, 25 MiB in-flight       184.598 s/op      205.666 s/op     top-to-bottom by 10.2%
```

The traversal work pattern stayed stable across these runs. `pullTopToBottom` consistently transferred about
`1.175 GB` teacher-to-learner and `2.176 GB` learner-to-teacher, with `34.535M` transfers in each direction.
`pullParallelSync` consistently transferred about `1.166 GB` teacher-to-learner and `2.164 GB` learner-to-teacher,
with `34.357M` transfers in each direction. The difference was therefore not explained by one traversal sending much
less data.

The in-flight diagnostics showed a more useful distinction:

- `pullTopToBottom` is more window-sensitive. It reached the learner-to-teacher cap in the `100ms/13 MiB`,
  `200ms/13 MiB`, and `200ms/25 MiB` runs, and came close in the `100ms/25 MiB` run.
- `pullParallelSync` did not reach the learner-to-teacher cap in these runs. Its maximum learner-to-teacher in-flight
  bytes stayed around `11.6-12.4 MiB`.
- RTT changed the relative ordering more strongly than the cap did in this sample: `pullParallelSync` won both `100ms`
  runs, while `pullTopToBottom` won both `200ms` runs.

These runs suggest the benchmark needs a sweep of mainnet-style RTT/window profiles instead of a single `REALISTIC`
default. The crossover point between traversal modes is itself calibration evidence.

An additional diagnostic run used the same `50M` saved state and `1 Gbps` bandwidth, but raised
`networkInflightBytesLimit` to `128 MiB` (`134217728` bytes). This cap is intentionally much larger than the
bandwidth-delay product for both `100ms` and `200ms` RTT at `1 Gbps`, so it is useful for testing whether the cap is
driving the result. It should be treated as a diagnostic profile, not as a realistic default.

```text
Profile                          pullTopToBottom   pullParallelSync   Result
100ms RTT, 128 MiB in-flight       184.095 s/op      176.284 s/op     parallel by 4.2%
200ms RTT, 128 MiB in-flight       196.519 s/op      215.287 s/op     top-to-bottom by 8.7%
```

All channels in these runs reported `capacityWaitCount=0`, and maximum in-flight bytes stayed far below the
`128 MiB` cap. Maximum learner-to-teacher in-flight was about `26.5 MiB` for `pullTopToBottom` and `12.5 MiB` for
`pullParallelSync` at `100ms`, and about `31.4 MiB` for `pullTopToBottom` and `14.7 MiB` for `pullParallelSync` at
`200ms`.

This disproves the simpler hypothesis that making the cap effectively non-binding makes `pullTopToBottom` always win.
For this state shape, `pullParallelSync` still wins at `100ms` RTT without capacity waits, while `pullTopToBottom` wins
clearly at `200ms` RTT. The current best hypothesis is that traversal ordering is primarily RTT-driven for this
divergence shape; an undersized in-flight cap can amplify or distort the ordering, but it is not the only cause of the
observed crossover.

Follow-up calibration guidance:

- Treat `128 KiB` as a constrained-window profile.
- Use `LOOPBACK` only as the no-network storage/traversal baseline. To diagnose latency while removing cap effects, use
  `REALISTIC` with the target RTT, target bandwidth, and a large diagnostic cap such as `128 MiB`.
- For mainnet-style comparisons, sweep RTT and in-flight cap together. At `1 Gbps`, start with `100ms` RTT /
  `13 MiB`, `150ms` RTT / `19 MiB`, and `200ms` RTT / `25 MiB`, then add intermediate RTTs around the observed
  traversal crossover.
- If cluster data is available, use measured RTT and effective TCP window or `ss -ti` samples to narrow the sweep.
- If the temporary wait counters are useful, convert them into optional permanent diagnostics; otherwise remove them
  before finalizing the implementation.

## 2026-05-06

Additional `50M` saved-state runs compared `pullTopToBottom` and `pullParallelSync` at `50ms` and `200ms` RTT using
bandwidth-delay-product-sized in-flight caps for `1 Gbps`. These runs used the same saved state under
`platform-sdk/swirlds-benchmarks/data/ReconnectBench`, `numFiles=5000`, `numRecords=10000`, `keySize=32`,
`recordSize=128`, `benchmark.verifyResult=false`, and `-Xms24g -Xmx24g -XX:+AlwaysPreTouch`. The current worktree also
included the `SimulatedNetworkChannel` input-close, failed-write diagnostic, and progressive-read coalescing fixes.

Commands:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=25000 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=6553600 --no-daemon
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=100000 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=26214400 --no-daemon
```

The traversal mode was changed in `platform-sdk/swirlds-benchmarks/settings.txt` between runs.

```text
Profile                         pullTopToBottom   pullParallelSync   Result
50ms RTT, 6.25 MiB in-flight      195.919 s/op      173.976 s/op     parallel by 11.2%
200ms RTT, 25 MiB in-flight       196.529 s/op      220.312 s/op     top-to-bottom by 10.8%
```

Reconnect stats and bytes were stable within each traversal:

```text
Traversal          Transfers each direction   Teacher->learner bytes   Learner->teacher bytes
pullTopToBottom             34.535787M             1.175008844 GB           2.175754589 GB
pullParallelSync            34.356617M             1.165671114 GB           2.164466879 GB
```

In-flight and capacity-wait diagnostics:

```text
Profile / Traversal             T->L max in-flight   L->T max in-flight   Capacity waits
50ms / pullTopToBottom               1,673,419 bytes      6,553,575 bytes   L->T 11295 waits, 5.951s
50ms / pullParallelSync              2,273,011 bytes      6,553,575 bytes   L->T 828 waits, 0.712s
200ms / pullTopToBottom              3,716,507 bytes     20,416,221 bytes   none
200ms / pullParallelSync             4,179,302 bytes     12,246,331 bytes   none
```

These runs reinforce the observed crossover behavior. With a BDP-sized `50ms` profile, `pullParallelSync` remained
faster, even though both traversals moved nearly the same total bytes. With a BDP-sized `200ms` profile,
`pullTopToBottom` was faster. The `50ms` results also show that a nominal BDP cap can still be binding on the
learner-to-teacher direction for this workload shape, especially for `pullTopToBottom`.

## 2026-05-15: Averaged Cluster-Profile Diagnostic
These local diagnostic runs used one averaged cluster-derived network profile across traversal modes. They reused the
existing saved teacher/learner state and did not regenerate benchmark data.

### Configuration

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

### Run Summary

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

### Run 1: `pullTopToBottom`

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

### Run 2: `pullParallelSync`

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

### Run 3: `pullTopToBottom`

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

### Run 4: `pullTwoPhasePessimistic`

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

### Immediate Observations

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

