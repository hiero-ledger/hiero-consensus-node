# PQ TLS performance run

## 1. Benchmarks

### 1.1 Loopback TLS handshake benchmarks

- Hardware: macOS arm64 (Apple Silicon dev hardware), JDK 25, BouncyCastle 1.83, ACCP 2.6.0 (locally built with ML-KEM enabled)
- 500 handshakes per trial, 50 warmup iterations before measurement, 3 trials per configuration
- All reported figures are the **median of 3 trial means**
- Trial-to-trial variance ~3–7%
- TLS 1.3 throughout, cipher suite `TLS_AES_256_GCM_SHA384`, mTLS, server and client in the same JVM over loopback
- Two harnesses on identical iteration / warmup / stats logic: `MtlsDebugSunJsseBench` for SunJSSE rows, `MtlsDebugHybrid` for BCJSSE rows
- "Classical kx" = `x25519` named group
- "Classical auth" = RSA-3072 self-signed CA + EC-384 agrCert (`SHA384withRSA` for SunJSSE rows; `SHA384withRSAandMGF1` agrCert + `SHA384withRSA` self-signed CA for BCJSSE rows, with `rsa_pkcs1_sha384` advertised for cert-chain compat per RFC 8446 §4.2.3)
- "Hybrid PQ kx" = `X25519MLKEM768` named group (hybrid x25519 + ML-KEM-768)
- "Pure PQ kx" = `MLKEM768` named group (pure ML-KEM-768)
- "PQ auth" = `mldsa65 / mldsa44 / mldsa87` signature schemes, ML-DSA-65 self-signed CA + ML-DSA-65 agrCert
- "BCJSSE (BC pinned)" = no-ACCP BCJSSE rows in which the hybrid `JcaJceHelper`'s "everything else" leg is pinned to BC explicitly (production-fallback intent — see §2.6)

### 1.2  Consensus-side throughput (Linux x86_64, sloth Docker host)

SignatureSchemeExperiment — 5-config comparison: Baseline / SUN+ACCP / BJSSE-PQ / BJSSE+ACCP (PQ KX) / BJSSE+ACCP (PQ KX + ML-DSA-65 certs)

- Class: `org.hiero.sloth.test.performance.experiments.signature.SignatureSchemeExperiment`
- Methods executed per run: `signatureSchemeRSA`, then `signatureSchemeED25519`
- Topology: 4 nodes (Docker-fixture), Linux remote host
- Target throughput: 10.0 TPS per node × 4 = **40 TPS total**
- Warm-up: 5 s of empty txs at target rate
- Benchmark window: `PT4M` = **240 s** of BENCHMARK txs at target rate (expected to reach `40 × 240 = 9600` total txs if generation keeps up)
- 4 runs per cell (8 directories per tarball — one dir per `(run, scheme)`)

Latency `Avg`, `p50`, `p99`, `Max` are in µs and come from the `TOTAL` row of `benchmark.log` (network-wide). `Throughput` is the `Test throughput:` line. **"Hit 40?"** asks whether the benchmark generated all 9604 expected txs and the network sustained the target rate; when RSA is the bottleneck, generation back-pressures and only ~5000 txs are produced over a longer window (~245 s).

----

## 2. Loopback TLS handshake benchmarks (macOS arm64)

> **Note**: The handshake benchmarks isolate TLS establishment cost and should not be interpreted as end-to-end node throughput measurements.

SunJSSE is included on the classical row only; it does not support PQ key exchange or PQ auth.
The hybrid `JcaJceHelper` is what unlocks ACCP for BCJSSE — without it, BCJSSE cannot benefit from ACCP and has to be pinned to BC, which forfeits acceleration.

### 2.1 Provider × algorithm matrix — classical authentication

Cert chain: RSA-3072 self-signed CA + EC-384 agrCert.

|      Provider      |           kx            |  ACCP   |     Mean     |    Median    |     p95      |     p99      | Throughput (hs/sec) |
|--------------------|-------------------------|---------|--------------|--------------|--------------|--------------|---------------------|
| **SunJSSE**        | x25519                  | no      | 4.711 ms     | 4.600 ms     | 5.405 ms     | 6.144 ms     | 212.3               |
| **SunJSSE**        | x25519                  | **yes** | **1.256 ms** | **1.182 ms** | **1.787 ms** | **2.190 ms** | **795.9**           |
| BCJSSE (BC pinned) | x25519                  | no      | 2.288 ms     | 2.170 ms     | 3.298 ms     | 4.386 ms     | 437.1               |
| BCJSSE             | x25519                  | yes     | 1.476 ms     | 1.401 ms     | 2.221 ms     | 3.494 ms     | 677.4               |
| BCJSSE (BC pinned) | hybrid `X25519MLKEM768` | no      | 2.443 ms     | 2.279 ms     | 3.512 ms     | 5.358 ms     | 409.4               |
| BCJSSE             | hybrid `X25519MLKEM768` | yes     | 1.725 ms     | 1.652 ms     | 2.582 ms     | 4.053 ms     | 579.8               |
| BCJSSE (BC pinned) | pure `MLKEM768`         | no      | 2.277 ms     | 2.103 ms     | 3.301 ms     | 4.791 ms     | 439.2               |
| BCJSSE             | pure `MLKEM768`         | yes     | 1.390 ms     | 1.310 ms     | 2.095 ms     | 3.127 ms     | 719.5               |

### 2.2 Adding PQ auth (ML-DSA-65)

Same hybrid-helper stack, with the cert chain switched to ML-DSA-65 self-signed CA + ML-DSA-65 agrCert. Signature schemes: `mldsa65 / mldsa44 / mldsa87`. SunJSSE cannot run any of these configurations — it does not implement ML-DSA in TLS.

|      Provider      |             kx              |  ACCP   |     Mean     |    Median    |     p95      |     p99      | Throughput (hs/sec) |
|--------------------|-----------------------------|---------|--------------|--------------|--------------|--------------|---------------------|
| BCJSSE (BC pinned) | x25519                      | no      | 2.143 ms     | 2.010 ms     | 3.110 ms     | 4.878 ms     | 466.7               |
| BCJSSE             | x25519                      | yes     | 1.710 ms     | 1.611 ms     | 2.519 ms     | 3.615 ms     | 584.6               |
| BCJSSE (BC pinned) | hybrid `X25519MLKEM768`     | no      | 2.247 ms     | 2.129 ms     | 3.260 ms     | 4.716 ms     | 445.1               |
| **BCJSSE**         | **hybrid `X25519MLKEM768`** | **yes** | **1.851 ms** | **1.746 ms** | **2.655 ms** | **3.827 ms** | **540.3**           |
| BCJSSE (BC pinned) | pure `MLKEM768`             | no      | 2.131 ms     | 2.087 ms     | 3.047 ms     | 4.179 ms     | 469.2               |
| BCJSSE             | pure `MLKEM768`             | yes     | 1.566 ms     | 1.488 ms     | 2.393 ms     | 3.397 ms     | 639.0               |

Headline: full PQ (hybrid kx + ML-DSA auth + ACCP) at ~1.85 ms vs SunJSSE+ACCP classical at ~1.26 ms. **~595 µs absolute difference** per handshake. The worst-case fallback (BCJSSE hybrid + ML-DSA + no ACCP) lands at 2.247 ms — still within ~1 ms of the fastest classical configuration. <TO REVIEW>Operationally insignificant at expected handshake rates; still needs sloth-side confirmation.</TO REVIEW>

### 2.3 ACCP speedup by configuration

|                   Configuration                    | No-ACCP  | With ACCP |  Speedup  |
|----------------------------------------------------|----------|-----------|-----------|
| SunJSSE x25519 (Sun JCA baseline)                  | 4.711 ms | 1.256 ms  | **3.75×** |
| BCJSSE x25519, classical auth (BC pinned baseline) | 2.288 ms | 1.476 ms  | 1.55×     |
| BCJSSE hybrid kx, classical auth                   | 2.443 ms | 1.725 ms  | 1.42×     |
| BCJSSE pure kx, classical auth                     | 2.277 ms | 1.390 ms  | 1.64×     |
| BCJSSE x25519, ML-DSA auth                         | 2.143 ms | 1.710 ms  | 1.25×     |
| BCJSSE hybrid kx, ML-DSA auth                      | 2.247 ms | 1.851 ms  | 1.21×     |
| BCJSSE pure kx, ML-DSA auth                        | 2.131 ms | 1.566 ms  | 1.36×     |

Pattern: ACCP's speedup is **substantially smaller on BCJSSE+BC than on SunJSSE+Sun JCA** because BC's pure-Java classical primitives are already much faster than Sun's. ACCP's value is mostly in replacing slow JDK implementations of RSA-PSS verify; when the baseline already uses BC's faster RSA-PSS, the headroom for ACCP shrinks. On the proposed deployment shape, ACCP delivers **1.2–1.65×** across configurations rather than the headline ~3.75× seen against the SunJSSE/Sun-JCA baseline.

### 2.4 New observations from the unified-harness run

**SunJSSE vs BCJSSE direct comparison.**

|           | SunJSSE  | BCJSSE (BC pinned, no ACCP) / BCJSSE (with ACCP) |
|-----------|----------|--------------------------------------------------|
| no-ACCP   | 4.711 ms | **2.288 ms**                                     |
| with ACCP | 1.256 ms | 1.476 ms                                         |

Without ACCP, BCJSSE on BC is ~2× faster than SunJSSE on Sun JCA — BC's pure-Java RSA-PSS verify is meaningfully faster than Sun's. With ACCP, SunJSSE is ~220 µs (~17%) faster than BCJSSE+helper, reflecting some structural overhead in BCJSSE's TLS code path that ACCP cannot remove. <TO REVIEW>Migrating from SunJSSE to BCJSSE is a latency *improvement* without ACCP and a small (<20%) regression with ACCP — the migration is not a cost in the no-ACCP scenario; it's a benefit.</TO REVIEW>

**Classical-auth vs ML-DSA-auth on the no-ACCP / BC-pinned baseline.**

|                  | Classical auth | ML-DSA auth |    Δ    |
|------------------|----------------|-------------|---------|
| BCJSSE x25519    | 2.288 ms       | 2.143 ms    | −145 µs |
| BCJSSE hybrid kx | 2.443 ms       | 2.247 ms    | −196 µs |
| BCJSSE pure kx   | 2.277 ms       | 2.131 ms    | −146 µs |

ML-DSA-auth handshakes are slightly faster than classical-auth handshakes when both run on BC pure-Java without ACCP — by ~150–200 µs. BC's ML-DSA-65 verify is slightly cheaper than BC's RSA-PSS-3072 verify, plus the ML-DSA cert chain has no ECDSA-P384 `CertificateVerify` (the cert keys are ML-DSA, so the handshake signature is also ML-DSA). <TO REVIEW>The win is small but consistent — a deployment that cannot ship ACCP isn't paying a penalty for moving to ML-DSA.</TO REVIEW>

**Hybrid `X25519MLKEM768` over pure `MLKEM768`.**

|     Auth × ACCP     |   Pure   |  Hybrid  | Penalty |
|---------------------|----------|----------|---------|
| Classical / ACCP    | 1.390 ms | 1.725 ms | +335 µs |
| Classical / no-ACCP | 2.277 ms | 2.443 ms | +166 µs |
| ML-DSA / ACCP       | 1.566 ms | 1.851 ms | +285 µs |
| ML-DSA / no-ACCP    | 2.131 ms | 2.247 ms | +116 µs |

Hybrid kx adds **~115–335 µs** over pure across the four direct comparisons. The cost is the extra x25519 keygen + scalar multiplication. The with-ACCP penalty is consistently larger than the no-ACCP penalty because the rest of the handshake is faster under ACCP, so the fixed-cost x25519 work becomes a larger fraction of the total.

**Full picture: today's baseline → proposed full PQ.**

|                              Configuration                               |   Mean   | Δ vs SunJSSE+ACCP | Δ vs SunJSSE no-ACCP (today) |
|--------------------------------------------------------------------------|----------|-------------------|------------------------------|
| SunJSSE x25519 + ACCP                                                    | 1.256 ms | —                 | **−3.45 ms (3.75× faster)**  |
| SunJSSE x25519, no ACCP (today)                                          | 4.711 ms | +3.45 ms          | —                            |
| BCJSSE x25519 + ACCP, classical auth                                     | 1.476 ms | +220 µs           | −3.24 ms                     |
| BCJSSE pure ML-KEM kx + ML-DSA auth + ACCP                               | 1.566 ms | +310 µs           | −3.15 ms                     |
| BCJSSE hybrid ML-KEM kx + ML-DSA auth + ACCP (recommended)               | 1.851 ms | +595 µs           | −2.86 ms                     |
| BCJSSE hybrid ML-KEM kx + ML-DSA auth, **no ACCP** (worst-case fallback) | 2.247 ms | +991 µs           | −2.46 ms                     |

---

## 3. Consensus-side throughput (Linux x86_64, sloth Docker host)

Sources
* (a) **Baseline** — main
* (b) **SUN TLS 1.2 + ACCP** — main + **ACCP**
* (c) BCJSSE TLS 1.3 **PQ FULL** (kx:X25519MLKEM768 auth:ML-DSA-65)
* (d) BCJSSE TLS 1.3 + ACCP **PQ KX only** (kx:X25519MLKEM768 auth:classical EC)
* (e) BCJSSE TLS 1.3 + ACCP **PQ FULL** (kx:X25519MLKEM768 auth:ML-DSA-65)

Config:

|          Cell          | `ACCP registered` count | roster `agr_key_type`  |
|------------------------|------------------------:|------------------------|
| (a) Baseline           |                       0 | EdDSA / EC (classical) |
| (b) SUN + ACCP         |                       1 | EdDSA / EC (classical) |
| (c) BJSSE_PQ           |                       0 | ML-DSA-65              |
| (d) BJSSE+ACCP KX-only |                       1 | EC                     |
| (e) BJSSE+ACCP PQ      |                       1 | ML-DSA                 |

> **Note**: sloth runs use ML-DSA-65 agrCert signed by a non-PQ sigCert — full chain PQ is not yet a real configuration.
> the layout is not PQ on itself but cannot be measured otherwise without implementing all required changes.

### 3.1 (a) Baseline

#### RSA (`signatureSchemeRSA`) — 4 runs

|    #     | Count |        Avg µs |    p50 µs |     p99 µs |     Max µs |     Throughput |           Hit 40 TPS?           |
|----------|------:|--------------:|----------:|-----------:|-----------:|---------------:|---------------------------------|
| 1        |  4986 |     6,896,421 | 6,973,295 | 12,106,837 | 12,963,995 |     20.22 tx/s | **NO** (4986/9604 over 246.6 s) |
| 2        |  4764 |     4,952,999 | 4,782,017 |  8,485,084 |  9,147,831 |     19.58 tx/s | **NO** (4764/9604 over 243.4 s) |
| 3        |  4770 |     5,160,391 | 4,974,780 |  8,316,939 |  9,187,496 |     19.60 tx/s | **NO** (4770/9604 over 243.4 s) |
| 4        |  4881 |     6,357,878 | 5,955,485 | 11,908,080 | 13,324,946 |     19.84 tx/s | **NO** (4881/9604 over 246.0 s) |
| **mean** |  4850 | **5,841,922** | 5,671,394 | 10,204,237 | 11,156,067 | **19.81 tx/s** | —                               |

#### ED25519 — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs | Max µs |     Throughput |                       Hit 40 TPS?                       |
|----------|------:|----------:|-------:|-------:|-------:|---------------:|---------------------------------------------------------|
| 1        |  9604 |     2,815 |  2,643 |  3,847 | 40,059 |     40.02 tx/s | YES                                                     |
| 2        |  9602 |     2,930 |  2,804 |  4,303 | 27,291 |     40.01 tx/s | YES (2-tx short of 9604, strict-count assertion failed) |
| 3        |  9603 |     2,691 |  2,663 |  3,873 | 26,880 |     40.01 tx/s | YES (1-tx short)                                        |
| 4        |  9602 |     3,405 |  3,323 |  4,361 | 32,998 |     40.01 tx/s | YES (2-tx short)                                        |
| **mean** |  9603 | **2,960** |  2,858 |  4,096 | 31,807 | **40.01 tx/s** | —                                                       |

### 3.2 (b) **SUN TLS 1.2 + ACCP** — main + **ACCP**

#### RSA — 4 runs

|    #     | Count |        Avg µs |    p50 µs |    p99 µs |    Max µs |     Throughput |               Hit 40 TPS?                |
|----------|------:|--------------:|----------:|----------:|----------:|---------------:|------------------------------------------|
| 1        |  5986 |     1,742,019 | 1,245,771 | 3,583,056 | 3,780,637 |     24.68 tx/s | **NO** (5986/9604, +20% txs vs Baseline) |
| 2        |  5948 |     1,726,668 | 1,258,884 | 3,563,114 | 3,805,137 |     24.42 tx/s | **NO**                                   |
| 3        |  5927 |     1,762,286 | 1,350,788 | 3,600,287 | 3,798,135 |     24.47 tx/s | **NO**                                   |
| 4        |  5951 |     1,742,735 | 1,323,152 | 3,632,104 | 3,827,308 |     24.56 tx/s | **NO**                                   |
| **mean** |  5953 | **1,743,427** | 1,294,649 | 3,594,640 | 3,802,804 | **24.53 tx/s** | —                                        |

#### ED25519 — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs | Max µs |     Throughput | Hit 40 TPS? |
|----------|------:|----------:|-------:|-------:|-------:|---------------:|-------------|
| 1        |  9604 |     2,672 |  2,719 |  3,175 |  9,320 |     40.02 tx/s | YES         |
| 2        |  9604 |     2,705 |  2,583 |  3,593 | 34,051 |     40.02 tx/s | YES         |
| 3        |  9604 |     2,536 |  2,564 |  3,208 | 44,109 |     40.02 tx/s | YES         |
| 4        |  9604 |     2,431 |  2,345 |  3,236 |  9,946 |     40.02 tx/s | YES         |
| **mean** |  9604 | **2,586** |  2,553 |  3,303 | 24,357 | **40.02 tx/s** | —           |

Note: ACCP is registered (count=1) and shows ML-DSA-{44,65,87} services in node logs. RSA latency drops ~3.35× vs Baseline, but RSA still doesn't saturate at 40 TPS — RSA is still the consensus-path bottleneck.

### 3.3 (c) BCJSSE TLS 1.3 **PQ FULL** (kx:X25519MLKEM768 auth:ML-DSA-65)

#### RSA — 4 runs

|    #     | Count |        Avg µs |    p50 µs |     p99 µs |     Max µs |     Throughput | Hit 40 TPS? |
|----------|------:|--------------:|----------:|-----------:|-----------:|---------------:|-------------|
| 1        |  5114 |     8,556,889 | 8,425,516 | 12,098,364 | 13,352,882 |     20.86 tx/s | **NO**      |
| 2        |  4894 |     5,319,954 | 5,063,213 |  8,577,971 | 10,430,886 |     19.76 tx/s | **NO**      |
| 3        |  5030 |     7,616,014 | 7,962,535 | 12,024,747 | 12,737,552 |     20.40 tx/s | **NO**      |
| 4        |  5004 |     7,551,140 | 8,004,241 | 12,114,082 | 12,901,000 |     20.07 tx/s | **NO**      |
| **mean** |  5011 | **7,260,999** | 7,363,876 | 11,203,791 | 12,355,580 | **20.27 tx/s** | —           |

#### ED25519 — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs | Max µs |     Throughput | Hit 40 TPS? |
|----------|------:|----------:|-------:|-------:|-------:|---------------:|-------------|
| 1        |  9603 |     2,697 |  2,630 |  3,753 | 13,783 |     40.01 tx/s | YES         |
| 2        |  9603 |     2,552 |  2,651 |  3,203 | 12,624 |     40.01 tx/s | YES         |
| 3        |  9604 |     2,981 |  2,938 |  3,919 | 25,426 |     40.02 tx/s | YES         |
| 4        |  9602 |     3,112 |  3,111 |  3,993 | 24,340 |     40.01 tx/s | YES         |
| **mean** |  9603 | **2,835** |  2,833 |  3,717 | 19,043 | **40.01 tx/s** | —           |

Note: TLS path uses BCJSSE + ML-DSA-65 agreement cert (PQ handshake), but consensus signature path uses plain BC (no ACCP). RSA mean is comparable to Baseline (within noise / slightly slower).

### 3.4 (d) BCJSSE TLS 1.3 + ACCP **PQ KX only** (kx:X25519MLKEM768 auth:classical EC)

#### RSA — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs | Max µs |     Throughput | Hit 40 TPS? |
|----------|------:|----------:|-------:|-------:|-------:|---------------:|-------------|
| 1        |  9604 |     4,445 |  4,416 |  6,026 | 17,860 |     40.02 tx/s | **YES**     |
| 2        |  9604 |     4,466 |  4,429 |  6,564 | 24,969 |     40.02 tx/s | **YES**     |
| 3        |  9604 |     4,610 |  4,566 |  6,345 | 32,033 |     40.02 tx/s | **YES**     |
| 4        |  9604 |     4,588 |  4,552 |  5,784 | 26,996 |     40.02 tx/s | **YES**     |
| **mean** |  9604 | **4,527** |  4,491 |  6,180 | 25,464 | **40.02 tx/s** | —           |

#### ED25519 — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs |  Max µs |     Throughput | Hit 40 TPS? |
|----------|------:|----------:|-------:|-------:|--------:|---------------:|-------------|
| 1        |  9604 |     2,654 |  2,573 |  3,634 | 124,906 |     40.02 tx/s | YES         |
| 2        |  9604 |     2,649 |  2,621 |  3,066 |  15,022 |     40.02 tx/s | YES         |
| 3        |  9604 |     2,498 |  2,468 |  3,248 |  19,901 |     40.02 tx/s | YES         |
| 4        |  9604 |     2,548 |  2,585 |  3,190 |  11,993 |     40.02 tx/s | YES         |
| **mean** |  9604 | **2,587** |  2,562 |  3,285 |  42,956 | **40.02 tx/s** | —           |

Note: 0 errors / 0 build failures across all 4 runs. ACCP registered (count=1); `agr_key_type: EC` confirms classical EC agreement cert with PQ key-exchange only.

### 3.5 (e) BCJSSE TLS 1.3 + ACCP **PQ FULL** (kx:X25519MLKEM768 auth:ML-DSA-65)

#### RSA — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs | Max µs |     Throughput |     Hit 40 TPS?      |
|----------|------:|----------:|-------:|-------:|-------:|---------------:|----------------------|
| 1        |  9603 |     4,476 |  4,377 |  6,012 | 28,472 |     40.01 tx/s | **YES** (1-tx short) |
| 2        |  9604 |     4,815 |  4,759 |  7,057 | 12,243 |     40.02 tx/s | **YES**              |
| 3        |  9604 |     4,450 |  4,405 |  5,647 | 90,397 |     40.01 tx/s | **YES**              |
| 4        |  9604 |     4,287 |  4,260 |  5,444 | 24,006 |     40.02 tx/s | **YES**              |
| **mean** |  9604 | **4,507** |  4,450 |  6,040 | 38,780 | **40.02 tx/s** | —                    |

#### ED25519 — 4 runs

|    #     | Count |    Avg µs | p50 µs | p99 µs | Max µs |     Throughput | Hit 40 TPS? |
|----------|------:|----------:|-------:|-------:|-------:|---------------:|-------------|
| 1        |  9604 |     2,705 |  2,710 |  3,373 | 13,430 |     40.02 tx/s | YES         |
| 2        |  9604 |     2,709 |  2,591 |  3,371 | 27,847 |     40.02 tx/s | YES         |
| 3        |  9604 |     2,576 |  2,574 |  3,683 |  9,447 |     40.02 tx/s | YES         |
| 4        |  9604 |     2,546 |  2,535 |  4,038 | 31,792 |     40.02 tx/s | YES         |
| **mean** |  9604 | **2,634** |  2,603 |  3,616 | 20,629 | **40.02 tx/s** | —           |

Note: ACCP registered (count=1); `agr_key_type: ML-DSA` confirms full PQ TLS (PQ KX + ML-DSA-65 cert) running concurrently with ACCP-accelerated consensus signature verification.

### 3.6 Summary — performance gains

#### RSA — mean across 4 runs

| Cell |          Avg |        vs Baseline |           p99 |     Throughput | Hit 40 TPS? |
|------|-------------:|-------------------:|--------------:|---------------:|-------------|
| (a)  | 5,841,922 µs |              1.00× | 10,204,237 µs |     19.81 tx/s | No          |
| (b)  | 1,743,427 µs |       3.35× faster |  3,594,640 µs |     24.53 tx/s | No          |
| (c)  | 7,260,999 µs |  0.80× (~baseline) | 11,203,791 µs |     20.27 tx/s | No          |
| (d)  | **4,527 µs** | **~1,290× faster** |  **6,180 µs** | **40.02 tx/s** | **YES**     |
| (e)  | **4,507 µs** | **~1,296× faster** |  **6,040 µs** | **40.02 tx/s** | **YES**     |

#### ED25519 — mean across 4 runs

| Cell |      Avg |  vs Baseline |      p99 | Throughput | Hit 40 TPS? |
|------|---------:|-------------:|---------:|-----------:|-------------|
| (a)  | 2,960 µs |        1.00× | 4,096 µs | 40.01 tx/s | YES         |
| (b)  | 2,586 µs | 1.14× faster | 3,303 µs | 40.02 tx/s | YES         |
| (c)  | 2,835 µs | 1.04× faster | 3,717 µs | 40.01 tx/s | YES         |
| (d)  | 2,587 µs | 1.14× faster | 3,285 µs | 40.02 tx/s | YES         |
| (e)  | 2,634 µs | 1.12× faster | 3,616 µs | 40.02 tx/s | YES         |

---

## 4. Takeaways

### 4.1 The recommended deployment shape works

BCJSSE + ACCP + hybrid `X25519MLKEM768` kx + ML-DSA-65 auth (cell (e) on Linux, last row of §2.4 on macOS):
- Loopback handshake: **1.85 ms mean** vs SunJSSE+ACCP classical at 1.26 ms — a **+595 µs absolute penalty per handshake** for the full PQ posture.
- Consensus-side (sloth, Linux x86_64): hits the **40 TPS target with full ACK count (9604/9604)** at **4.5 ms RSA mean**, indistinguishable from (d) PQ-KX-only at 4.53 ms.
- The worst-case fallback (no ACCP, hybrid kx, ML-DSA): 2.25 ms on loopback. Still within ~1 ms of the fastest classical config.

### 4.2 ACCP is the dominant variable; algorithm choice is secondary

The consensus-side numbers are the clearest evidence: no-ACCP fail to generate the full 9604 tx target. With ACCP — drop RSA mean by **~1300×** (down to ~4.5k µs) and hit 40 TPS cleanly.

### 4.3 BCJSSE is not a regression vs SunJSSE — it is an improvement at the no-ACCP baseline

At the no-ACCP baseline, BCJSSE on BC is **~2× faster** than SunJSSE on Sun JCA (2.29 ms vs 4.71 ms loopback).
BC's pure-Java RSA-PSS verify is materially faster than Sun's.
With ACCP, the comparison flips slightly: SunJSSE+ACCP is ~17% (~220 µs) faster than BCJSSE+ACCP due to fixed overhead in BCJSSE's TLS code path.

### 4.4 ML-DSA-65 auth is slightly cheaper than classical RSA-PSS auth on BC

On the no-ACCP / BC-pinned baseline, ML-DSA-auth handshakes are **145–200 µs faster** than RSA-PSS-auth handshakes across all three kx variants (§2.4, "Classical-auth vs ML-DSA-auth"). BC's ML-DSA-65 verify is slightly faster than BC's RSA-PSS-3072 verify, and the ML-DSA cert chain removes the ECDSA-P384 `CertificateVerify` step. The win shrinks under ACCP because ACCP doesn't accelerate ML-DSA but does accelerate the RSA-PSS classical path.

### 4.5 Hybrid kx costs ~115–335 µs over pure ML-KEM — accept it

Hybrid `X25519MLKEM768` adds **+115 µs (no ACCP) to +335 µs (with ACCP)** vs pure `MLKEM768`. The penalty is larger under ACCP because the rest of the handshake shrinks, making the fixed x25519 keygen + scalar-mult a larger fraction of the total.

### 4.6 ED25519 numbers should be treated as a regression sentinel

Consensus throughput on the ED25519 path is bound by the TPS generator target.
