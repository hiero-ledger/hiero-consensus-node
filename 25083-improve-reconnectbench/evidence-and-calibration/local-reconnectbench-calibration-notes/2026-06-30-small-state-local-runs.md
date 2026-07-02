# Small-State Local ReconnectBench Runs

## Parameters

Configuration source: `platform-sdk/swirlds-benchmarks/build.gradle.kts`, `jmhReconnect`.

| Parameter | Value |
|---|---:|
| Date | `2026-06-30` |
| Requested base state size | Varies by run; see results table |
| `maxKey` | `10000000` |
| `numFiles` | `1000` |
| `numRecords` | Varies by run; see results table |
| `randomSeed` | `9823452658` |
| `teacherAddProbability` | `0.1` |
| `teacherModifyProbability` | `0.3` |
| `teacherRemoveProbability` | `0.0` |
| `keySize` | `32` |
| `recordSize` | `128` |
| `numThreads` | `32` |
| `networkProfile` | `REALISTIC` |
| `networkLatencyMicroseconds` | Varies by run; see results table |
| `networkBandwidthMegabitsPerSecond` | `200` |
| `networkInflightBytesLimit` | Varies by run; see results table |
| JMH mode | `SingleShotTime` |
| JMH measurement count | Varies by run; see results table |
| JVM heap | `-Xms24g -Xmx24g` |
| Result file | `platform-sdk/swirlds-benchmarks/build/results/jmh/results-reconnect.txt` |

## Run Results

| Run | Requested state | `numRecords` | Traversal | Latency | In-flight cap | Count | Data verification | Mean score | Error | Median | p90 | Total run time |
|---|---:|---:|---|---:|---:|---:|---|---:|---:|---:|---:|---:|
| S1 | `1M` | `1000` | `pullTopToBottom` | `270 us` | `128 MiB` | `5` | Not recorded | `6.756 s/op` | `± 0.829 s/op` | `6.674 s/op` | `7.048 s/op` | `00:01:15` |
| S2 | `1M` | `1000` | `pullParallelSync` | `270 us` | `128 MiB` | `5` | Not recorded | `5.783 s/op` | `± 1.049 s/op` | `5.811 s/op` | `6.157 s/op` | `00:00:53` |
| S3 | `1M` | `1000` | `pullTwoPhasePessimistic` | `270 us` | `128 MiB` | `5` | Not recorded | `5.817 s/op` | `± 1.847 s/op` | `5.753 s/op` | `6.621 s/op` | `00:00:56` |
| S4 | `1M` | `1000` | `pullTopToBottom` | `270 us` | `128 MiB` | `10` | Disabled | `5.951 s/op` | `± 1.214 s/op` | Not provided | Not provided | Not provided |
| S5 | `1M` | `1000` | `pullParallelSync` | `270 us` | `128 MiB` | `10` | Disabled | `6.146 s/op` | `± 0.696 s/op` | Not provided | Not provided | Not provided |
| S6 | `1M` | `1000` | `pullTwoPhasePessimistic` | `270 us` | `128 MiB` | `10` | Disabled | `5.917 s/op` | `± 0.326 s/op` | Not provided | Not provided | Not provided |
| S7 | `1M` | `1000` | `pullTopToBottom` | `75000 us` | `128 MiB` | `10` | Disabled | `7.378 s/op` | `± 0.593 s/op` | Not provided | Not provided | Not provided |
| S8 | `1M` | `1000` | `pullParallelSync` | `75000 us` | `128 MiB` | `10` | Disabled | `7.082 s/op` | `± 0.564 s/op` | Not provided | Not provided | Not provided |
| S9 | `1M` | `1000` | `pullTwoPhasePessimistic` | `75000 us` | `128 MiB` | `10` | Disabled | `5.821 s/op` | `± 0.614 s/op` | Not provided | Not provided | Not provided |
| S10 | `10M` | `10000` | `pullTopToBottom` | `270 us` | `128 MiB` | `10` | Disabled | `53.588 s/op` | `± 5.584 s/op` | `52.787 s/op` | `59.918 s/op` | `00:09:01` |
| S11 | `10M` | `10000` | `pullParallelSync` | `270 us` | `128 MiB` | `10` | Disabled | `54.411 s/op` | `± 4.724 s/op` | `53.464 s/op` | `60.444 s/op` | `00:09:08` |
| S12 | `10M` | `10000` | `pullTwoPhasePessimistic` | `270 us` | `128 MiB` | `10` | Disabled | `47.672 s/op` | `± 3.535 s/op` | `48.638 s/op` | `50.089 s/op` | `00:08:03` |
| S13 | `10M` | `10000` | `pullTopToBottom` | `75000 us` | `128 MiB` | `10` | Disabled | `55.015 s/op` | `± 4.327 s/op` | `54.596 s/op` | `59.385 s/op` | `00:09:14` |
| S14 | `10M` | `10000` | `pullParallelSync` | `75000 us` | `128 MiB` | `10` | Disabled | `65.157 s/op` | `± 2.412 s/op` | `65.197 s/op` | `67.557 s/op` | `00:10:56` |
| S15 | `10M` | `10000` | `pullTwoPhasePessimistic` | `75000 us` | `128 MiB` | `10` | Disabled | `46.609 s/op` | `± 1.671 s/op` | `46.351 s/op` | `48.939 s/op` | `00:07:50` |
| S16 | `10M` | `10000` | `pullTopToBottom` | `75000 us` | `16 MiB` | `10` | Disabled | `52.043 s/op` | `± 3.409 s/op` | `51.541 s/op` | `55.324 s/op` | `00:10:33` |
| S17 | `10M` | `10000` | `pullParallelSync` | `75000 us` | `16 MiB` | `10` | Disabled | `64.995 s/op` | `± 4.027 s/op` | `63.918 s/op` | `69.807 s/op` | `00:10:55` |
| S18 | `10M` | `10000` | `pullTwoPhasePessimistic` | `75000 us` | `16 MiB` | `10` | Disabled | `47.512 s/op` | `± 2.036 s/op` | `47.377 s/op` | `49.438 s/op` | `00:08:01` |

## Conclusion

The complete `n=5` 1M requested-state pass does not reproduce the current high-state trend
`pullTopToBottom < pullTwoPhasePessimistic < pullParallelSync`.

By mean score, the observed 1M order was
`pullParallelSync` (`5.783 s/op`) < `pullTwoPhasePessimistic` (`5.817 s/op`) < `pullTopToBottom` (`6.756 s/op`).
By median score, the observed order was
`pullTwoPhasePessimistic` (`5.753 s/op`) < `pullParallelSync` (`5.811 s/op`) < `pullTopToBottom` (`6.674 s/op`).

`pullParallelSync` and `pullTwoPhasePessimistic` are too close relative to their reported error to separate confidently
in the `n=5` run set, but both are faster than `pullTopToBottom`. So 1M can serve as a quick execution smoke run, but
the completed `n=5` data does not support it as a smoke-trend state size for the 100M-style traversal ordering.

The complete `n=10` pass, with data verification disabled, is closer to the desired high-state trend but still does not
cleanly reproduce it. By mean score, the observed order was
`pullTwoPhasePessimistic` (`5.917 s/op`) < `pullTopToBottom` (`5.951 s/op`) < `pullParallelSync` (`6.146 s/op`).

This puts `pullParallelSync` slowest, matching the high-state endpoint, but `pullTopToBottom` and
`pullTwoPhasePessimistic` are effectively tied: their mean difference is only `0.034 s/op`, much smaller than the
reported error. So `n=10` improves the smoke-trend shape versus `n=5`, but 1M still does not provide a clear
three-algorithm ordering signal.

The complete `1M`, `75000 us` one-way latency pass, corresponding to roughly `150 ms` ping/RTT, does not reproduce the
desired high-state trend. By mean score, the observed order was
`pullTwoPhasePessimistic` (`5.821 s/op`) < `pullParallelSync` (`7.082 s/op`) < `pullTopToBottom` (`7.378 s/op`).

The `pullParallelSync` versus `pullTopToBottom` gap is still smaller than their reported errors, so that part remains
noisy. But `pullTwoPhasePessimistic` is clearly separated from both by mean score and error range, making it the fastest
mode in this high-latency 1M run set rather than the middle mode.

The complete `10M`, `270 us` latency pass does not reproduce the desired high-state trend. By mean score, the observed
order was
`pullTwoPhasePessimistic` (`47.672 s/op`) < `pullTopToBottom` (`53.588 s/op`) < `pullParallelSync` (`54.411 s/op`).

The `pullTopToBottom` versus `pullParallelSync` endpoint ordering is directionally consistent with the desired trend,
but the mean gap is only `0.823 s/op`, much smaller than the reported errors. `pullTwoPhasePessimistic` is fastest by
mean score, not the middle mode; its gap from `pullTopToBottom` is `5.916 s/op`, which is larger but still not clean
relative to the combined reported errors. The attached logs reported learner metadata size `9,999,999` and teacher
metadata size `11,116,466`.

The complete `10M`, `75000 us` one-way latency pass does not reproduce the desired high-state trend. By mean score, the
observed order was
`pullTwoPhasePessimistic` (`46.609 s/op`) < `pullTopToBottom` (`55.015 s/op`) < `pullParallelSync` (`65.157 s/op`).

This run set is well separated, but in the wrong order for the first two modes. `pullParallelSync` is slowest as
desired, and the endpoint gap versus `pullTopToBottom` is `10.142 s/op` with non-overlapping reported error ranges.
However, `pullTwoPhasePessimistic` is fastest, not the middle mode, and its gap versus `pullTopToBottom` is
`8.406 s/op` with non-overlapping reported error ranges. The attached logs reported learner metadata size `9,999,999`
and teacher metadata size `11,116,466`.

The complete `10M`, `75000 us`, `16 MiB` in-flight cap pass does not reproduce the desired high-state trend. By mean
score, the observed order was
`pullTwoPhasePessimistic` (`47.512 s/op`) < `pullTopToBottom` (`52.043 s/op`) < `pullParallelSync` (`64.995 s/op`).

This preserves the strong desired endpoint separation: `pullTopToBottom` is faster than `pullParallelSync` by
`12.952 s/op`, and the reported error ranges do not overlap. But `pullTwoPhasePessimistic` remains fastest by mean
score, not the middle mode; its `4.531 s/op` gap versus `pullTopToBottom` still overlaps reported error ranges.
Compared with the matching `128 MiB` cap rows, the smaller cap shifts means by `-2.972 s/op` for `pullTopToBottom`,
`-0.162 s/op` for `pullParallelSync`, and `+0.903 s/op` for `pullTwoPhasePessimistic`, with overlapping reported
errors in all cap-to-cap comparisons. So this cap change does not materially change the 10M high-latency
traversal-order conclusion. The logs show the `16 MiB` cap was reached in both directions, with max in-flight around
`16777216` bytes.
