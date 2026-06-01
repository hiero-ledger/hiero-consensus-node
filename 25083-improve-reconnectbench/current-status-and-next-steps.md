# Current Status And Next Steps

Date: `2026-05-22`

## Status

Production reconnect telemetry is not needed for the first calibration pass.

The local subprocess artifacts show that existing node logs and metrics already provide the non-network reconnect
evidence required by `cluster-reconnectbench-run-protocol.md`:

- learner and teacher node IDs;
- first learner reconnect start and end;
- learner reconnect duration;
- reconnect transfer counters;
- clean and dirty reconnect work shape;
- learner and teacher state-size ranges;
- workload and learner-behind context;
- whether later reconnects happened after the first window.

So the cluster run can use a latest-`main` commit without production reconnect code changes. We also do not need a
separate latest-`main` telemetry worktree just to add Java metrics or logs.

## Remaining Work

The next important task is network evidence strategy and script preparation.

We need to understand where the cluster shell/script changes should live, then prepare a small script-oriented plan that
captures:

- one run per traversal mode from the same baseline;
- ordinary script output, node logs, node metrics, and config/settings artifacts;
- commit, image, baseline identifier, traversal mode, learner/teacher candidates, workload, stop duration, and
  transaction mix/rate when available;
- RTT evidence between learner and teacher;
- bandwidth or throughput evidence outside the reconnect window;
- TCP/window/backpressure samples during the reconnect window, using `ss -ti` or an equivalent if available.

Active bandwidth generators should not run during reconnect. Passive sampling should run during reconnect; active
throughput checks, if used, should run before or after the reconnect window.

## DevOps Question

The only required DevOps question right now is:

```text
Where should the cluster run script changes be placed?
```

Everything else should be prepared on our side first, either as shell snippets or as task-local documentation under
`25083-improve-reconnectbench`.
