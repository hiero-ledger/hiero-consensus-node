# Network Evidence Location Analysis

Date: `2026-05-22`

## Purpose

This document compares the two DevOps-provided places where the reconnect calibration run could gather network
evidence for `ReconnectBench` calibration.

The question is not whether either path can run reconnect. Both can. The question is which path gives the cleanest,
lowest-cost way to collect:

- traversal mode and run context;
- node logs, node metrics, and node config/settings artifacts;
- RTT evidence between learner and teacher;
- bandwidth or throughput evidence outside the reconnect window;
- TCP/window/backpressure samples during the reconnect window.

## Recommendation

Use the performance-analysis reconnect workflow for the first calibration pass:

```text
/Users/thenswan/Work/LimeChain/playground/performance-analysis-automation/.github/workflows/performance-reconnect-test.yml
/Users/thenswan/Work/LimeChain/playground/performance-analysis-automation/reconnect_test/profileReconnectLoopK8s.sh
```

The reconnect loop script is the best place for reconnect-window evidence because it owns the stop, downtime, restart,
and wait-for-active lifecycle. The workflow is the right place for traversal-mode orchestration, run context, output
directory plumbing, active pre/post throughput checks, and artifact publishing.

Use the single-day longevity reconnect path only as a later confirmation run if we need evidence from that exact CITR
longevity environment:

```text
/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/.github/workflows/zxc-single-day-longevity-test.yaml
/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/.github/workflows/support/citr/profileReconnectLoopK8s.sh
```

Candidate 2 is viable, but it is slower and less focused: reconnect is gated behind the longevity flow, starts fresh
load again, and currently runs three reconnect loops. That is useful for endurance confidence, but it is not the leanest
first calibration vehicle.

## Candidate 1: Performance Automation Reconnect

### Relevant Control Points

Workflow:

```text
performance-analysis-automation/.github/workflows/performance-reconnect-test.yml
```

Important points:

- `hederaversion`, NLG inputs, and `AddSettings` are workflow-dispatch inputs.
- the consensus-node commit SHA is written as `hederahash`;
- `version_run.txt` is copied into the NLG pod;
- the reconnect watcher calls `reconnect_test/profileReconnectLoopK8s.sh`;
- final collection copies `client.log`, `version_run.txt`, and `podlog_<namespace>` into `report`;
- final publishing uploads `report` to the performance analytics location.

Reconnect script:

```text
performance-analysis-automation/reconnect_test/profileReconnectLoopK8s.sh
```

Useful lifecycle boundaries:

- learner pod is hard-coded as `network-node1-0`;
- the script stops the learner service;
- it sleeps for the controlled downtime;
- it restarts the learner service;
- it polls learner `swirlds.log` for `BEHIND|CHECKING`;
- it then polls for `ACTIVE`.

### Why This Is Preferred

This path is already reconnect-specific and reportedly runs in about four hours. It also has all the artifact lanes we
need: script output, NLG client output, node logs, node stats, config, `config.txt`, and `settingsUsed.txt`.

For calibration, we can make a small, targeted script strategy:

- one run per traversal mode, using `virtualMap.reconnectMode=<mode>`;
- one accepted reconnect window per run, with later reconnects excluded;
- low-volume passive network sampling during the reconnect window;
- optional active throughput checks only before or after reconnect.

This is closer to the protocol than the longevity path and should require less DevOps time per failed attempt.

### Candidate 1 Risks

- The learner is hard-coded to `network-node1-0`; this is acceptable only if the cluster scenario intentionally uses that
  learner.
- The teacher is not known before reconnect. Passive TCP sampling should either cover all consensus peers or collect
  broad enough socket state to match later against logs.
- Pod-local `ss` is not guaranteed. The sampler should use a guarded sequence such as `ss` when available, then
  `netstat` fallback, and record when neither exists.
- Active throughput tooling such as `iperf3` is not provided by Solo or NLG today. If we need active bandwidth tests, add
  an explicit debug pod/image or separate installation step.
- Outputs written only to pod `/tmp` will be lost. Write sampler outputs into copied node output directories or copy them
  into `report`.

## Candidate 2: Single-Day Longevity Reconnect

### Relevant Control Points

Workflow:

```text
hiero-consensus-node/.github/workflows/zxc-single-day-longevity-test.yaml
```

Reconnect script:

```text
hiero-consensus-node/.github/workflows/support/citr/profileReconnectLoopK8s.sh
```

Useful properties:

- Chewie/resource acquisition provides namespace, cluster FQDN, network ID, and owner.
- the workflow records useful run context in `version_run.txt`;
- pod-to-Kubernetes-node distribution and Grafana links are already printed;
- reconnect script output is captured to `report/reconnect/reconnect.log`;
- `report/reconnect` is uploaded to GCS/performance analytics.

### Why It Is Less Suitable For The First Pass

This path is more realistic for the formal CITR longevity environment, but it is not lean:

- the normal run shape is much longer, around 20 hours in the referenced run context;
- reconnect is tied to the broader longevity workflow;
- the reconnect job starts fresh NLG load and runs three reconnect loops;
- reducing it to one reconnect per traversal mode would require workflow changes anyway.

It is therefore a better follow-up validation path than a first calibration pass.

### Candidate 2 Risks

- Long runtime makes iteration expensive.
- Existing shape does not match the desired one-mode, one-first-reconnect calibration matrix.
- Same `ss`/`netstat` uncertainty applies.
- Same unknown-teacher issue applies.
- Existing artifact preservation is good, but sampler outputs must be written under `report/reconnect` or node output
  paths before namespace deletion.

## Shared Tooling Findings

Solo and NLG are useful deployment and workload plumbing, but they do not appear to provide raw network evidence
collection out of the box.

Assumptions for script preparation:

- `kubectl exec`, `kubectl cp`, namespace-scoped pod discovery, and report upload are already available in both paths.
- NLG provides workload/client logs and application throughput, not raw link bandwidth.
- NLG/Solo do not provide `iperf3` by default.
- Do not assume `ss` exists in consensus-node or NLG containers.
- Consensus-node containers are more likely to have `netstat`/`net-tools` than `ss`/`iproute2`.
- The sampler must record tool availability so missing tools become explicit calibration gaps, not silent failures.

## Key Source References

Candidate 1:

- workflow inputs include `hederaversion`, NLG options, and `AddSettings`:
  `performance-reconnect-test.yml:23-118`;
- consensus-node checkout and `hederahash` recording:
  `performance-reconnect-test.yml:300-306`;
- settings generation and `AddSettings` append:
  `performance-reconnect-test.yml:460-480`;
- NLG deployment and `version_run.txt` copy:
  `performance-reconnect-test.yml:593-608`;
- reconnect script invocation:
  `performance-reconnect-test.yml:893-944`;
- final log collection and publishing:
  `performance-reconnect-test.yml:953-1010`;
- reconnect stop/restart/poll lifecycle:
  `reconnect_test/profileReconnectLoopK8s.sh:47-105`;
- full node log/stats/config collection:
  `performance_analysis_scripts/getClusterErrors.sh:24-40`.

Candidate 2:

- workflow inputs include `ref`, NLG settings, `add-settings`, and `reconnect-uptime`:
  `zxc-single-day-longevity-test.yaml:5-52`;
- settings generation and `add-settings` append:
  `zxc-single-day-longevity-test.yaml:317-338`;
- pod placement and Grafana-link summary:
  `zxc-single-day-longevity-test.yaml:443-451`;
- longevity wait/finish happens before reconnect:
  `zxc-single-day-longevity-test.yaml:480-792`;
- reconnect script invocation and `report/reconnect/reconnect.log` capture:
  `zxc-single-day-longevity-test.yaml:877-890`;
- reconnect artifact collection and publishing:
  `zxc-single-day-longevity-test.yaml:931-954`;
- reconnect stop/restart/poll lifecycle:
  `.github/workflows/support/citr/profileReconnectLoopK8s.sh:29-87`;
- full node log/stats/config collection:
  `.github/workflows/support/citr/getClusterErrors.sh:25-41`.

## Proposed Evidence Placement

### Workflow Layer

Put these in the workflow or a workflow-invoked wrapper:

- traversal mode input or matrix;
- `virtualMap.reconnectMode=<mode>` injection through settings;
- `NofLoops=1` for calibration;
- run context prints: commit, image/tag if available, namespace, baseline identifier, mode, learner candidate, workload,
  stop duration, NLG args, job URL;
- report/network output directory creation;
- active throughput checks before reconnect or after reconnect, if a dedicated tool/image is available;
- artifact upload of the network evidence directory.

### Reconnect Loop Script Layer

Put timing-bound sampling in `profileReconnectLoopK8s.sh` or a small helper called from it:

- before stopping learner: collect pod placement, pod IPs, service IPs, and a short RTT probe if pod-local tools exist;
- just before learner restart: start passive TCP sampling on learner and all plausible teacher peers;
- during wait-for-`BEHIND|CHECKING` and wait-for-`ACTIVE`: continue passive samples at low frequency;
- immediately after first `ACTIVE`: stop samplers and copy outputs to the report path or node output path;
- after reconnect: collect another short RTT probe and optional passive interface counters.

Do not run active bandwidth generators during the reconnect window.

## Suggested First Script Strategy

For candidate 1, prepare changes in this shape once we are ready to edit the performance-automation repository:

1. Add a calibration mode to the workflow or dispatch inputs:

   ```text
   traversalMode=<pullTopToBottom|pullParallelSync|pullTwoPhasePessimistic>
   reconnectLoops=1
   networkEvidence=true
   ```

2. Inject the traversal mode through settings:

   ```text
   virtualMap.reconnectMode, <mode>
   ```

3. Pass a report directory into the reconnect loop script.

4. Add a helper script, for example:

   ```text
   reconnect_test/collectReconnectNetworkEvidence.sh
   ```

   The helper should be script-only and low risk: discover pods, collect RTT if available, start/stop passive socket
   samples, and copy evidence into the report directory.

5. Use guarded commands inside pods:

   ```text
   command -v ss && ss -tinp
   command -v netstat && netstat -antp
   command -v ping && ping -c 10 <peer>
   ```

   The actual implementation should record command availability and timestamps for every sample.

6. Keep active bandwidth checks optional and outside the reconnect window. If `iperf3` is required, treat it as a
   separate debug-pod/tooling addition rather than assuming it exists in NLG or consensus-node images.

## Follow-Up Resolution

The first round of follow-ups is resolved in
`25083-improve-reconnectbench/network-evidence-and-state-shape-strategy.md`.

- `network-node1-0` is the intended learner for this calibration run.
- Keep baseline/matrix handling simple: run the whole job once per traversal mode instead of building a three-mode
  matrix into the workflow for the first pass.
- Do not require active throughput evidence for calibration pass 1. Use existing cluster/node metrics plus passive
  socket/interface samples.
- If active throughput becomes necessary later, prefer a temporary debug pod with `iperf3` before or after reconnect.
  Do not run active bandwidth tests during reconnect.
- NLG config and logs are sufficient to infer intended state shape and to bound divergence, once cross-checked with
  consensus-node logs and metrics.

## Bottom Line

For the first ReconnectBench calibration pass, use candidate 1 and place the core network evidence collection around
`performance-analysis-automation/reconnect_test/profileReconnectLoopK8s.sh`.

Keep candidate 2 as a later, higher-cost confirmation path for the formal single-day longevity environment.
