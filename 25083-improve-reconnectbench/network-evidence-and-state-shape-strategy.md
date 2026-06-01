# Network Evidence And State Shape Strategy

Date: `2026-05-22`

## Purpose

This note captures the follow-up analysis after choosing the performance-analysis reconnect workflow as the preferred
cluster evidence path. It answers two questions:

- whether active bandwidth/throughput evidence is required for the first calibration pass;
- how to infer cluster state shape, state size, and divergence from the NLG configuration plus consensus-node artifacts.

No script edits are made by this note. When edits begin, keep them surgical in the performance-analysis reconnect
scenario path.

## Decisions

- Use the performance-analysis automation path for the first calibration pass:
  `/Users/thenswan/Work/LimeChain/playground/performance-analysis-automation/.github/workflows/performance-reconnect-test.yml`
  and
  `/Users/thenswan/Work/LimeChain/playground/performance-analysis-automation/reconnect_test/profileReconnectLoopK8s.sh`.
- Treat `network-node1-0` as the intended learner. The script already hard-codes `LEARNER_POD=network-node1-0` and
  `LEARNER_NODEID=0`.
- Run the whole job once per traversal order. Do not build a three-mode matrix into the first script pass.
- Do not require active bandwidth testing for calibration pass 1.
- Use existing node logs, node stats, NLG logs, run context, passive socket samples, and passive interface counters as
  the first network evidence set.
- If active bandwidth is needed later, use a temporary debug pod with `iperf3` before or after reconnect. Do not run it
  during reconnect, and do not install extra packages into consensus-node containers for the first pass.

## Why Active Throughput Is Not Required First

`ReconnectBench` needs plausible values for:

```text
networkLatencyMicroseconds
networkBandwidthMegabitsPerSecond
networkInflightBytesLimit
```

For the first pass, we do not need to prove raw link capacity. We need enough evidence to choose a defensible local
sweep and avoid modeling an obviously wrong network. The previously analyzed run in
`cluster-metrics-analysis.md` was not enough by itself: sustained directional TCP throughput, direct reconnect RTT,
socket/window evidence, and reconnect clean/dirty counters were missing. The next candidate-1 run should close the
network side of that gap by adding passive socket/interface evidence to the already-collected logs, metrics, config, and
NLG artifacts:

- `platform.ping` and per-peer `ping_us_*` metrics constrain latency.
- `bytes_per_sec_sent` and per-peer `bytes_per_sec_sent_*` metrics constrain observed outbound traffic.
- learner reconnect logs report synchronization duration and MiB received, which gives an effective reconnect receive
  rate.
- passive `/proc/net/dev` snapshots can bracket pod interface byte deltas around the reconnect window.
- passive `ss -tinp` samples can show socket queues, retransmission symptoms, and TCP window pressure if `ss` exists.
  `netstat -antp` is a weaker fallback if only `net-tools` is present.

Active bandwidth would answer a different question: "what is the approximate idle link capacity between two pods or
nodes?" That can be useful later, especially if local/cluster results disagree after passive calibration or if we need
to separate real link capacity from application/reconnect throughput. It is less directly tied to reconnect behavior
than the observed reconnect byte rate, application load, and TCP/window samples. It also adds tooling and perturbation
risk.

Use this first-pass mapping:

| Cluster evidence | Local benchmark use |
| --- | --- |
| RTT or `ping_us_*` | choose `networkLatencyMicroseconds`, using about half RTT for one-way simulator latency |
| reconnect MiB divided by synchronization seconds | lower-bound observed reconnect throughput |
| `bytes_per_sec_sent_*` and interface deltas | bandwidth sweep sanity check |
| `ss`/`netstat` socket queues, retransmits, windows | decide whether `networkInflightBytesLimit` should be constrained |
| no TCP/window evidence | keep `networkInflightBytesLimit` neutral, for example `134217728`, and record the gap |

## If Active Throughput Becomes Necessary Later

Rank the mechanisms this way:

1. Debug pod in the same namespace, preferably node-pinned near the learner/teacher placement. This is least invasive
   and easy to remove. Run it before stopping the learner or after the first reconnect completes.
2. Temporary sidecar only if DevOps needs a measurement from inside the exact consensus pod network context. This is
   more invasive because it changes pod shape.
3. Package installation inside consensus-node containers only as a last resort. It mutates the runtime environment,
   needs package manager/network access, and muddies evidence from the run.

Do not use NLG as raw bandwidth evidence. NLG reports application workload throughput and success behavior, not link
capacity.

Local file inspection gives partial tool confidence:

- consensus-node Dockerfiles install `net-tools`, so `netstat` is likely available in node containers;
- the searched Dockerfiles did not show `iproute2`, `iputils-ping`, or `iperf3`, so `ss`, `ip`, `ping`, and `iperf3`
  should not be assumed;
- the runner setup installs `net-tools` and `iputils-ping`, but runner tools do not prove pod-local tool availability.

## Passive Network Evidence Placement

Keep collection in the performance-analysis reconnect flow because it owns the timing boundaries:

```text
stop learner -> downtime -> restart learner -> wait for BEHIND/CHECKING -> wait for ACTIVE
```

Minimum collection before stopping the learner:

```text
kubectl get pods -o wide
kubectl get services -o wide
kubectl get nodes -o wide
kubectl describe pod network-node1-0
tool inventory inside consensus pods: ss, netstat, ip, ping
initial /proc/net/dev from learner and all network-node pods
short RTT probe if ping exists and peer pod IPs are known
```

During reconnect, sample learner and all plausible teacher pods. The teacher is identified only after the reconnect logs
show the peer, so broad sampling is simpler and safer for the first pass:

```text
date -u
ss -tinp || netstat -antp || true
cat /proc/net/dev || ip -s link || true
```

Stop the sampler immediately after the first `ACTIVE` transition for the learner. Preserve the files under the normal
`report` tree or under copied node output directories. Avoid writing evidence only to pod-local temporary locations.

The current workflow already collects the core non-network artifacts through
`/Users/thenswan/Work/LimeChain/playground/performance-analysis-automation/performance_analysis_scripts/getClusterErrors.sh`:
node output logs, stats, config, `config.txt`, and `settingsUsed.txt`.

## Loop Count Caution

The current candidate-1 workflow sets:

```text
downtime=300
warmtime=120
NofLoops=2
```

The current shell loop in `profileReconnectLoopK8s.sh` uses an inclusive condition with `counter <= NofLoops` and
increments the counter inside the loop. If the load test has not finished, `NofLoops=2` can produce three reconnect
attempts.

For calibration, analyze only the first successful learner reconnect. When we make script edits, either pass `NofLoops=0`
for exactly one reconnect with the current loop semantics, or make the loop semantics explicit in the script. This is a
small scenario edit, not a production-code change.

## NLG-Derived State Shape

The scheduled reconnect watcher defaults in the performance-analysis workflow are:

```text
run_NLG_Test=NftTransferLoadTest
run_NLG_Accounts=100000000
run_NLG_Time=6h
run_NLGDparams=-Dbenchmark.maxtps=8000
```

The workflow constructs NLG arguments like this for `NftTransferLoadTest`:

```text
-R -c 32 -a 100000000 -n 100000 -T 1000 -S hot -p 50 -tt 6h
```

Meaning:

| Parameter | Meaning |
| --- | --- |
| `-R` | reuse the NLG `saved/` identity mapping if present |
| `-c 32` | 32 submit clients plus 32 receipt clients |
| `-a 100000000` | 100M benchmark accounts |
| `-T 1000` | 1,000 NFT token types |
| `-n 100000` | 100,000 NFTs per token for `NftTransferLoadTest` |
| `-S hot -p 50` | hot/cold NFT transfer schema with 50 percent hot-involved transfer target |
| `-tt 6h` | transfer phase duration |
| `-Dbenchmark.maxtps=8000` | global NLG submit queue cap |

The intended prebuilt state is therefore:

```text
accounts: 100,000,000
NFT token types: 1,000
NFT serials: 100,000,000
hot treasury accounts: first 1,000 accounts
cold receiver accounts: remaining accounts, partitioned/overlapped by token association rules
default token associations per account: 2
```

The workload during learner downtime is mostly modify-heavy, not append-heavy, assuming the saved NLG state exists. Each
successful NFT transfer changes ownership and related account/token state, but it does not create new accounts, token
types, or NFT serials.

The rough upper bound on learner divergence during a 300 second outage is:

```text
8000 TPS * 300 seconds = 2,400,000 transfer attempts
```

Use actual NLG log throughput and success lines for the real divergence estimate. The target TPS is only a cap.

## What Must Be Confirmed In Artifacts

Deterministic from workflow/NLG config:

- intended account count, NFT token count, NFT serial count;
- hot/cold schema and transfer duration;
- target max TPS;
- learner downtime and warmup timing;
- traversal mode from `settingsUsed.txt` or `AddSettings`.

Must be confirmed from logs or metrics:

- whether NLG reused `saved/` state or rebuilt accounts/tokens/NFTs;
- whether setup completed before the learner was stopped;
- actual NLG transfer rate during the learner outage;
- actual first reconnect start/end timestamps;
- teacher selected for the learner reconnect;
- reconnect MiB received and synchronization seconds;
- `ReconnectMapMetrics` clean/dirty and teacher/learner transfer counters;
- `VirtualMap` size/path evidence near reconnect;
- whether later reconnects occurred and should be excluded.

If the NLG log shows account/token/NFT creation overlapping the learner outage, do not model that run as a stable-size
modify-heavy calibration run. Flag it as a workload-shape mismatch.

## Consensus-Node Artifacts To Extract

Consensus-node code already emits the first-pass reconnect/state evidence we need, as long as the next run preserves the
right logs and metrics:

- learner receiver start/finish with peer node ID and round;
- teacher sender start/finish and teacher state information;
- learner `Finished synchronization` duration;
- learner `Reconnect data usage report` MiB received;
- `ReconnectMapMetrics` counters for teacher/learner transfers and clean/dirty internal/leaf work;
- platform status transitions into `BEHIND`, `CHECKING`, and `ACTIVE`;
- `VirtualMap` size and lifecycle metrics;
- `MerkleDB` file, flush, read, compaction, and off-heap metrics;
- gossip/network metrics such as `ping_us_*`, `bytes_per_sec_sent_*`, disconnects, sync permits, and RPC/backpressure
  gauges.

For the next run, these should be enough for first-pass calibration when paired with passive network samples. The older
analyzed run was missing some of these practical artifacts, especially reconnect clean/dirty logs and socket/window
evidence.

## Suggested Future Script Shape

When we are ready to edit scripts, keep the scenario changes small:

- add or document a way to set exactly one traversal mode per job, probably through `AddSettings`:
  `virtualMap.reconnectMode, <mode>`;
- ensure the run context records the traversal mode, learner, downtime, warmtime, NLG args, commit/image, namespace, and
  job URL;
- collect `kubectl get pods -o wide`, services, nodes, and learner pod description before the reconnect loop;
- add a tiny passive sampler function to `profileReconnectLoopK8s.sh`, or call a very small helper if readability wins;
- sample all `network-node*` pods during the first reconnect window;
- preserve sampler output under `report/network/<mode>/reconnect-1/`;
- keep active throughput out of the reconnect window and out of the first implementation pass.

## Open DevOps Questions

### Actual Job-Log Findings

The downloaded GitHub job logs for run `26133522860` narrow the image question but do not fully answer it.

Observed in the logs:

- workflow run number: `280`;
- namespace input: `AdHoc14`, resolved to Kubernetes namespace `solo-sdpt-n14`;
- `inputs.hederaversion=v0.75.0-rc.1`;
- `hederahash=2685d66fd8600510ffdc361af2d3c9d8afea4b2e`;
- `inputs.soloversion=latest_tested_solo-charts0.59_balanced`;
- Solo CLI version used by the job: `0.63.0`;
- installed Solo chart version: `0.62.0`;
- `inputs.NLG_Test=LongevityLoadTest` for this run;
- Solo deploy command did not print a `--release-tag`, but `CONSENSUS_NODE_VERSION` was present in the environment;
- Solo node setup used:

  ```text
  consensus node setup ... --local-build-path .../hiero-consensus-node/hedera-node/data
  ```

- Solo logged:

  ```text
  Checkout version v0.75.0-rc.1 does not match the release version v0.59.0
  ```

Interpretation:

- the running Java artifacts should come from the local `v0.75.0-rc.1` build copied by `--local-build-path`;
- the underlying `root-container` OS/runtime image is still the Solo S6 consensus-node image;
- from Solo `v0.63.0` source defaults, the likely image is
  `ghcr.io/hashgraph/solo-containers/ubi8-s6-java25:0.43.0`, unless overridden by environment or chart values;
- the job logs do not print the actual `network-node*` pod image or image digest, so exact image identity still needs a
  Kubernetes pod query in the next run.

The logs also show `net-tools` and `iputils-ping` being installed on the GitHub runner. That does not prove those tools
exist inside the consensus-node `root-container`.

First try to infer the deployed image and tool availability from the actual run logs/artifacts. The performance-analysis
workflow records the consensus-node git hash in `version_run.txt`, deploys through Solo with a release tag, and uses a
local build path for node setup. The exact container image should ideally be captured with a Kubernetes pod query in the
next run.

Ask DevOps only for anything we cannot infer or verify from the run:

- Can the actual run logs/artifacts identify the exact `root-container` image used by `network-node*` pods? If not, can
  we add `kubectl get pod network-node1-0 -o jsonpath=...` or `kubectl describe pod network-node1-0` to the report?
- In the `root-container` of that image, are `netstat`, `ss`, `ip`, and `ping` available?
- Is ICMP ping allowed between consensus pods, or should RTT rely on existing `ping_us_*` node metrics?
- Is it acceptable to run low-frequency `kubectl exec` sampling across all `network-node*` pods during the reconnect
  window?
- If active throughput becomes necessary later, is a temporary debug pod with `iperf3` allowed in the same namespace,
  and can it be scheduled or pinned close enough to the learner/teacher pods to be meaningful?

## Bottom Line

The right first-pass strategy is passive-first and state-shape-aware:

- run candidate 1 once per traversal order;
- use `network-node1-0` as the learner;
- keep only the first learner reconnect as the calibration window;
- infer the large NFT state shape from NLG config and confirm it from `client.log`;
- use existing consensus-node logs/metrics for reconnect work shape;
- add passive socket/interface evidence around the reconnect window;
- defer active bandwidth testing unless the passive evidence cannot explain the local benchmark sweep.
