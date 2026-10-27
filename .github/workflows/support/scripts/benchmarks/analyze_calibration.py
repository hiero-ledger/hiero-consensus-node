#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Calibration analysis of a JMH benchmark in two parts. The probe finds where warmup ends and how many calls an
iteration records, and from that the configuration to measure. The configuration run measures that configuration with
many forks: the noise of one run's result, the fork count, and the Javadoc calibration block.

Usage: analyze_calibration.py [options] <results>...

A result is a directory written by run_benchmarks.py (a runs.json with one JMH invocation per parameter set and
attempt), a directory with a single results.json somewhere below it (an older evaluation run or a laptop run), or a
results.json file. Next to the JSON, the files of the benchmark workflows are used if present: run-info.properties,
environment-before.txt and environment-after.txt from record-benchmark-environment.sh, monitor.csv from its --monitor
mode, and JMH's output with a time stamp before every line. All results given in one call are analyzed as one
population; analyze results measured in different ways (nightly through Gradle, calibration runs, laptop) in separate
calls.

A result without warmup iterations and with at least 4 measurement iterations is a probe; any other result measures a
configuration. One call takes at most one configuration of each kind. With only a probe, the analysis prints the
configuration run to start; with both, it prints the calibration block.

Method:
- A unit is one measurement of one parameter set: one JMH invocation of run_benchmarks.py, or one parameter set of an
  older results.json. Only quiet units count: the other workloads on the node used fewer than --max-load CPUs on
  average over the unit's measurement iterations. Units without a monitor (a laptop) count as quiet.
- Scores are analyzed as logarithms, so variances are relative and a standard deviation of 0.01 is about 1%.
- A metric counts only in forks that report it in every iteration. LatencyProfiler skips a percentile in an iteration
  with too few calls, and JMH's rawData leaves such iterations out without saying which ones.
- Probe:
  - warmup: each iteration's score divided by the mean of the last half of its fork, then the median over all forks
    per iteration index. Warmup ends at the first index after which this median stays within --tolerance of 1. JMH
    writes only measurement iterations to the JSON, so the probe uses -wi 0 to see the whole trend.
  - iteration length of the configuration: the shortest multiple of 5 s that collects --samples calls per iteration at
    the probe's rate, so that the highest percentile can be estimated from one iteration;
  - warmup of the configuration: one iteration of the detected warmup time times --warmup-buffer, rounded up to 5 s;
  - measurement iterations: --iterations, at least 2, so that the noise within forks stays measurable.
- Configuration run: the configuration of the nightly run with more forks, so that the noise of a fork's result is
  measured at the length and iteration count the nightly run uses, not predicted from others. The noise within a fork
  is correlated over tens of seconds, so it does not shrink in proportion to a longer iteration.
  - variance components (method of moments, nested): within a fork (iterations), between the forks of a unit, and
    between units, each as a coefficient of variation (CV). A fork's result varies as much as the fork means within a
    unit; the component between units excludes what that contributes to the unit means. It is the floor that no fork
    count lowers.
  - prediction: the CV of one run's result with f forks is sqrt(unit^2 + fork result^2 / f). Only the fork count
    changes, and forks are independent JVMs.
  - forks: the fewest that reach a predicted CV of --target, or after which doubling them would lower the predicted CV
    by less than --gain, whichever is fewer; times --fork-buffer, at least 2. In quiet slots the unit component is
    small, so the target usually decides: it is the trade between noise and CI time.
  - warmup check: the first measured iteration of every fork against its second.
  - alloc.norm modes, over the forks of both parts: forks whose allocation per operation differs by more than 3% fall
    into separate modes, usually because the JIT compiled the code differently. The mode effect is the latency
    difference between modes within units.
"""
import argparse
import glob
import json
import math
import os
import re
import statistics as st
import sys
from collections import defaultdict

from benchmark_runs import (bench_key, monitor_mean, read_environment, read_iteration_times, read_monitor,
                            read_properties, read_run_index)

DEFAULT_METRICS = ["latency.mean", "latency.p50", "latency.p90", "latency.p99", "latency.p99.9", "latency.p99.99",
                   "latency.max"]
ALLOC_MODE_GAP = 0.03
# a result without warmup iterations and with at least this many measurement iterations is a probe
PROBE_ITERATIONS = 4
CONFIGURATION_FORKS = 10
# LatencyProfiler reports a percentile only from iterations with at least this many calls above it
MIN_CALLS_ABOVE = 100
PERCENTILES = [("p50", 50), ("p90", 90), ("p99", 99), ("p99.9", 99.9), ("p99.99", 99.99)]


def pct(x):
    """a standard deviation of logarithms as a percentage"""
    return "-" if x is None or math.isnan(x) else f"{100 * (math.exp(x) - 1):.2f}%"


def var(xs):
    return st.variance(xs) if len(xs) > 1 else float("nan")


def seconds(text):
    """JMH's "5 s", "500 ms" as seconds"""
    value, unit = str(text).split()
    return float(value) * {"ns": 1e-9, "us": 1e-6, "ms": 1e-3, "s": 1.0, "min": 60.0}[unit]


def calls_needed(percentile):
    """the fewest calls an iteration must record for LatencyProfiler to report the percentile"""
    return round(MIN_CALLS_ABOVE * 100 / (100 - percentile))


class Unit:
    """one measurement of one parameter set, with the environment of the run it belongs to"""

    def __init__(self, label, key, benchmark, base, output, monitor, max_load):
        self.label = label
        self.key = key
        self.b = benchmark
        self.info = read_properties(os.path.join(base, "run-info.properties"))
        self.before = read_environment(os.path.join(base, "environment-before.txt"))
        self.after = read_environment(os.path.join(base, "environment-after.txt"))
        self.monitor = monitor
        self.output = output
        self.times, _ = read_iteration_times(output) if output else ({}, {})
        self.iteration_s = seconds(benchmark.get("measurementTime", "5 s"))
        warmups = benchmark.get("warmupIterations", 0)
        # warmup iterations, their length, measurement iterations, their length
        self.config = (warmups, seconds(benchmark.get("warmupTime", "0 s")) if warmups else 0.0,
                       benchmark.get("measurementIterations", 0), self.iteration_s)
        self.probe = warmups == 0 and self.config[2] >= PROBE_ITERATIONS
        # the first iteration of the steady state; for a probe, the analysis sets it after finding the warmup
        self.first = warmups + 1
        windows = [(end - self.iteration_s, end) for (k, _, _), end in self.times.items() if k == key]
        self.others = monitor_mean(monitor, "others", windows) if monitor and windows else None
        self.quiet = self.others is None or self.others < max_load

    def env(self, key):
        return self.before.get(key) or self.after.get(key) or ""

    def node(self):
        boot = self.env("boot_id")
        return boot[:8] if boot else ""

    def counter_delta(self, key):
        try:
            return int(self.after[key]) - int(self.before[key])
        except (KeyError, ValueError):
            return None

    def monitor_mean(self, field, start, end):
        return monitor_mean(self.monitor, field, [(start, end)])

    def fork_timing(self):
        """(seconds per fork outside its measured iterations, seconds per iteration beyond its nominal length), from
        the time stamps of the JMH output"""
        if not self.output or not os.path.exists(self.output):
            return None, None
        # a fork runs from its "# Fork:" line to the next fork's "# Run progress:" line, or to "# Run complete"
        spans = {}
        fork = None
        bench = params = None
        with open(self.output, errors="replace") as f:
            for line in f:
                parts = line.split(" ", 1)
                if len(parts) < 2:
                    continue
                try:
                    ts = float(parts[0])
                except ValueError:
                    continue
                text = parts[1]
                if text.startswith(("# Run progress:", "# Run complete")) and fork is not None:
                    spans[fork] = (spans[fork][0], ts)
                    fork = None
                if text.startswith("# Benchmark: "):
                    bench = text[len("# Benchmark: "):].strip()
                elif text.startswith("# Parameters: "):
                    inner = text[len("# Parameters: "):].strip().strip("()")
                    params = dict(p.split(" = ", 1) for p in inner.split(", ") if " = " in p)
                elif text.startswith("# Fork: ") and bench and bench_key(bench, params or {}) == self.key:
                    fork = int(text.split()[2])
                    spans[fork] = (ts, None)
        overheads = []
        overshoots = []
        for fork, (start, end) in spans.items():
            ends = sorted(t for (k, f, _), t in self.times.items() if k == self.key and f == fork)
            if len(ends) < 2 or end is None:
                continue
            per_iteration = (ends[-1] - ends[0]) / (len(ends) - 1)
            overshoots.append(per_iteration - self.iteration_s)
            overheads.append(end - start - len(ends) * per_iteration)
        if not overheads:
            return None, None
        return st.median(overheads), st.median(overshoots)


def load_units(path, max_load):
    """the units of a result directory or file"""
    units = []
    if os.path.isdir(path) and read_run_index(path):
        monitor = read_monitor(os.path.join(path, "monitor.csv"))
        info = read_properties(os.path.join(path, "run-info.properties"))
        run = f"{info.get('run_id', os.path.basename(os.path.abspath(path)))}-{info.get('run_attempt', '1')}"
        for attempt in read_run_index(path):
            json_path = os.path.join(path, attempt["json"])
            if attempt["exit_code"] != 0 or not os.path.exists(json_path):
                continue
            with open(json_path) as f:
                for b in json.load(f):
                    key = bench_key(b["benchmark"], b.get("params", {}))
                    units.append(Unit(f"{run}/{attempt['attempt']}", key, b, path,
                                      os.path.join(path, attempt["output"]), monitor, max_load))
        return units
    if os.path.isfile(path):
        results = path
    else:
        found = sorted(glob.glob(os.path.join(path, "**", "results.json"), recursive=True))
        if not found:
            raise SystemExit(f"no runs.json or results.json below {path}")
        results = found[0]
    base = os.path.dirname(results)
    info = read_properties(os.path.join(base, "run-info.properties"))
    if "run_id" in info:
        label = f"{info['run_id']}-{info.get('run_attempt', '1')}"
    else:
        # a CI run id in the path, as in <run id>/jmh-results/..., or the last directory
        ids = [p for p in os.path.abspath(results).split(os.sep) if re.fullmatch(r"\d{8,}", p)]
        label = ids[-1] if ids else os.path.basename(os.path.abspath(path).rstrip(os.sep))
    monitor = read_monitor(os.path.join(base, "monitor.csv"))
    output = os.path.join(base, "jmh-output.txt")
    with open(results) as f:
        for b in json.load(f):
            key = bench_key(b["benchmark"], b.get("params", {}))
            units.append(Unit(label, key, b, base, output if os.path.exists(output) else None, monitor, max_load))
    return units


def iterations(unit, metric):
    """{fork: [(iteration index, score)]} with iteration indices counted from the first warmup iteration, without the
    forks that lack the metric in some iterations: rawData leaves those out without saying which ones"""
    b = unit.b
    raw = b["primaryMetric"]["rawData"] if metric == "primary" else b["secondaryMetrics"].get(metric, {}).get("rawData")
    if not raw:
        return {}
    counts = [len(scores) for scores in b["primaryMetric"]["rawData"]]
    offset = b.get("warmupIterations", 0)
    return {fork + 1: [(offset + i + 1, s) for i, s in enumerate(scores)] for fork, scores in enumerate(raw)
            if fork < len(counts) and len(scores) == counts[fork]}


def coverage(units, metric):
    """(forks that report the metric in every iteration, all forks)"""
    return (sum(len(iterations(u, metric)) for u in units),
            sum(len(u.b["primaryMetric"]["rawData"]) for u in units))


def warmup_trend(units, metric):
    """{iteration index: [score / steady-state score of its fork]}"""
    ratios = defaultdict(list)
    for unit in units:
        for scores in iterations(unit, metric).values():
            tail = [s for _, s in scores[len(scores) // 2:]]
            ref = st.mean(tail) if tail else 0
            if ref > 0:
                for i, s in scores:
                    ratios[i].append(s / ref)
    return ratios


def warmup_end(ratios, tolerance):
    medians = {i: st.median(r) for i, r in ratios.items()}
    end = min(medians)
    for i in sorted(medians):
        if abs(medians[i] - 1) > tolerance:
            end = i + 1
    return end


def alloc_modes(units):
    """{(unit label, fork): mode index}, mode centers"""
    per_fork = {}
    for unit in units:
        for fork, scores in iterations(unit, "alloc.norm").items():
            steady = [s for i, s in scores if i >= unit.first]
            if steady:
                per_fork[(unit.label, fork)] = st.mean(steady)
    if not per_fork:
        return {}, []
    values = sorted(per_fork.values())
    bounds = [b for a, b in zip(values, values[1:]) if b > a * (1 + ALLOC_MODE_GAP)]
    mode_of = {k: sum(v >= b for b in bounds) for k, v in per_fork.items()}
    centers = [st.median([v for k, v in per_fork.items() if mode_of[k] == m]) for m in range(len(bounds) + 1)]
    return mode_of, centers


def fork_logs(unit, metric, mode_of=None, mode_effect=None):
    """{fork: [log score of each steady-state iteration]}, optionally adjusted by the effect of the fork's mode"""
    logs = {}
    for fork, scores in iterations(unit, metric).items():
        steady = [math.log(s) for i, s in scores if i >= unit.first and s > 0]
        if steady:
            adjust = mode_effect.get(mode_of.get((unit.label, fork)), 0.0) if mode_effect else 0.0
            logs[fork] = [x - adjust for x in steady]
    return logs


def components(units, metric, mode_of=None, mode_effect=None):
    """variance components of log scores; returns per-unit rows and the pooled components"""
    rows = []
    for unit in units:
        logs = fork_logs(unit, metric, mode_of, mode_effect)
        if not logs:
            continue
        n_iter = st.mean(len(v) for v in logs.values())
        within = st.mean(var(v) for v in logs.values()) if n_iter > 1 else float("nan")
        means = [st.mean(v) for v in logs.values()]
        v_forks = var(means)
        between = max(0.0, v_forks - within / n_iter) if not math.isnan(v_forks) else float("nan")
        rows.append({"unit": unit, "mean": st.mean(means), "within": within, "between": between, "v_forks": v_forks,
                     "forks": len(means), "iterations": n_iter})
    if not rows:
        return rows, None
    within = st.mean(r["within"] for r in rows)
    between = st.mean(r["between"] for r in rows)
    # how much the result of one fork varies: the variance of the fork means, iterations' noise included
    fork_result = st.mean(r["v_forks"] for r in rows)
    n_forks = st.mean(r["forks"] for r in rows)
    v_units = var([r["mean"] for r in rows])
    unit = max(0.0, v_units - fork_result / n_forks) if not math.isnan(v_units) else float("nan")
    return rows, {"within": within, "between": between, "fork_result": fork_result, "unit": unit, "v_units": v_units,
                  "forks": n_forks, "iterations": st.mean(r["iterations"] for r in rows),
                  "mean": st.mean(r["mean"] for r in rows), "units": len(rows)}


def mode_effects(units, metric, mode_of):
    """log latency difference of every mode from the unit's mean, estimated from units that have several modes"""
    deviations = defaultdict(list)
    for unit in units:
        fork_means = {fork: (mode_of[(unit.label, fork)], st.mean(v))
                      for fork, v in fork_logs(unit, metric).items() if (unit.label, fork) in mode_of}
        modes = {m for m, _ in fork_means.values()}
        if len(modes) < 2:
            continue
        # each mode weighs equally, so the effects do not depend on the mix of modes in the unit
        per_mode = {m: st.mean([x for mm, x in fork_means.values() if mm == m]) for m in modes}
        center = st.mean(per_mode.values())
        for m, x in per_mode.items():
            deviations[m].append(x - center)
    return {m: st.mean(d) for m, d in deviations.items()}


def predicted_cv(c, forks):
    """CV of one run's result with the given number of forks, as a standard deviation of logarithms"""
    unit = 0.0 if math.isnan(c["unit"]) else c["unit"]
    return math.sqrt(unit + c["fork_result"] / forks)


FEATURES = [
    # name, description, unit, function of the iteration's monitor means
    ("others", "CPUs used by other workloads on the host", "CPU", lambda m: m["others"]),
    ("hot_mhz", "clock of the benchmark thread's CPU", "100 MHz",
     lambda m: m["hot_mhz"] / 100 if m.get("hot_mhz") is not None else None),
    ("sibling", "busy fraction of its hyperthread sibling", "sibling", lambda m: m.get("sibling")),
    ("l3_rest", "busy CPUs sharing its L3 cache, without the sibling", "CPU",
     lambda m: m["l3"] - m["sibling"] if m.get("l3") is not None else None),
    ("outside", "CPUs used by other workloads outside its L3 cache", "CPU",
     lambda m: m["others"] - m["l3"] if m.get("l3") is not None else None),
    ("migrated", "share of samples in which the thread had changed CPU", "share", lambda m: m.get("migrated")),
]


def iteration_features(units, metric):
    """per steady-state iteration: (fork id, log score, {feature: value}) for units with a monitor and time stamps"""
    points = []
    for unit in units:
        if not unit.monitor or not unit.times:
            continue
        for fork, scores in iterations(unit, metric).items():
            for i, s in scores:
                end_ts = unit.times.get((unit.key, fork, i))
                if i < unit.first or not end_ts or s <= 0:
                    continue
                means = {f: unit.monitor_mean(f, end_ts - unit.iteration_s, end_ts)
                         for f in ("others", "hot_mhz", "sibling", "l3", "migrated")}
                if means["others"] is None:
                    continue
                values = {name: fn(means) for name, _, _, fn in FEATURES}
                points.append(((unit.label, fork), math.log(s), values))
    return points


def solve(a, b):
    """solves a x = b by Gaussian elimination with partial pivoting; None if singular"""
    n = len(b)
    m = [row[:] + [b[i]] for i, row in enumerate(a)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        if abs(m[p][c]) < 1e-12:
            return None
        m[c], m[p] = m[p], m[c]
        for r in range(n):
            if r != c:
                f = m[r][c] / m[c][c]
                m[r] = [x - f * y for x, y in zip(m[r], m[c])]
    return [m[i][n] / m[i][i] for i in range(n)]


def print_mechanisms(units, metric):
    """relates each iteration's score to the load around the benchmark thread, within forks"""
    points = iteration_features(units, metric)
    if len(points) < 10:
        return
    names = [name for name, _, _, _ in FEATURES if sum(p[2][name] is not None for p in points) > len(points) / 2]
    by_fork = defaultdict(list)
    for fork, y, values in points:
        by_fork[fork].append((y, values))

    def centered(features):
        # every variable centered within its fork, so that differences between forks and units drop out; only
        # iterations that have all the features
        result = []
        for members in by_fork.values():
            members = [(y, v) for y, v in members if all(v[n] is not None for n in features)]
            if len(members) < 3:
                continue
            my = st.mean(y for y, _ in members)
            mx = {n: st.mean(v[n] for _, v in members) for n in features}
            result += [(y - my, [v[n] - mx[n] for n in features]) for y, v in members]
        return result

    print("### Other workloads\n")
    print(f"Within forks, {metric} of each iteration against the load during it. The slope is the latency change per"
          " unit; the range is the feature's spread (p10 to p90) within forks.\n")
    table = []
    usable = []
    for name in names:
        rows = centered([name])
        x = [r[1][0] for r in rows]
        if len(rows) < 10 or st.pstdev(x) == 0:
            continue
        usable.append(name)
        y = [r[0] for r in rows]
        q = st.quantiles(x, n=10)
        _, description, unit, _ = next(f for f in FEATURES if f[0] == name)
        table.append([name, description, len(rows), f"{st.correlation(x, y):.2f}",
                      f"{100 * st.linear_regression(x, y).slope:+.2f}% per {unit}", f"{q[-1] - q[0]:.2f}"])
    print_table(["feature", "meaning", "iterations", "r", "slope", "range"], table)
    # all features at once, except "others", which is the sum of l3_rest, outside, and part of sibling
    joint = [n for n in usable if n != "others"]
    rows = centered(joint)
    if len(joint) > 1 and len(rows) > 2 * len(joint):
        a = [[sum(r[1][i] * r[1][k] for r in rows) for k in range(len(joint))] for i in range(len(joint))]
        b = [sum(r[1][i] * r[0] for r in rows) for i in range(len(joint))]
        coefficients = solve(a, b)
        if coefficients:
            y = [r[0] for r in rows]
            fitted = [sum(c * r[1][j] for j, c in enumerate(coefficients)) for r in rows]
            ss_res = sum((u - v) ** 2 for u, v in zip(y, fitted))
            ss_tot = sum(u * u for u in y)
            print("Jointly (least squares, within forks): "
                  + ", ".join(f"{joint[j]} {100 * c:+.2f}%" for j, c in enumerate(coefficients))
                  + f"; R² = {1 - ss_res / ss_tot:.2f}.\n")
    unit_points = [(r["unit"].others, r["mean"]) for r in components(units, metric)[0]
                   if r["unit"].others is not None]
    if len(unit_points) > 2 and len({p[0] for p in unit_points}) > 1:
        x = [p[0] for p in unit_points]
        y = [p[1] for p in unit_points]
        print(f"Between units, unit mean against other workloads' CPUs: r = {st.correlation(x, y):.2f},"
              f" {100 * st.linear_regression(x, y).slope:+.2f}% per CPU ({len(unit_points)} units).\n")


def print_table(header, rows):
    widths = [max(len(str(x)) for x in col) for col in zip(header, *rows)]
    print("| " + " | ".join(str(h).ljust(w) for h, w in zip(header, widths)) + " |")
    print("|" + "|".join("-" * (w + 2) for w in widths) + "|")
    for row in rows:
        print("| " + " | ".join(str(x).rjust(w) for x, w in zip(row, widths)) + " |")
    print()


def fmt(x, digits=0):
    return "-" if x is None or (isinstance(x, float) and math.isnan(x)) else f"{x:,.{digits}f}"


def fmt_count(x):
    """a number of calls: 1.3 M, 13 k, 1,825"""
    if x is None:
        return "-"
    return f"{x / 1e6:.1f} M" if x >= 1e6 else f"{x / 1e3:.0f} k" if x >= 1e4 else fmt(x)


def print_units(all_units, mode_of, centers):
    print("### Units\n")
    rows = []
    for unit in all_units:
        samples = [s for f in iterations(unit, "latency.samples").values() for i, s in f if i >= unit.first]
        discarded = sum(s for f in iterations(unit, "measured.gc.discardedPasses").values() for _, s in f)
        modes = defaultdict(int)
        for (label, _), m in mode_of.items():
            if label == unit.label:
                modes[m] += 1
        throttled_s = unit.counter_delta("cgroup_throttled_usec")
        rows.append([
            unit.label, unit.info.get("started", "")[5:16], unit.node() or "-", unit.env("cpu_model")[:28] or "-",
            fmt(unit.counter_delta("cgroup_nr_throttled")),
            fmt(throttled_s / 1e6, 1) if throttled_s is not None else "-",
            fmt(unit.others, 2), "yes" if unit.quiet else "no",
            fmt(st.mean(samples) / 1e3 if samples else None) + "k", fmt(discarded),
            "/".join(str(modes[m]) for m in range(len(centers))) if unit.quiet else "-",
            fmt(unit.b["primaryMetric"]["score"], 3),
        ])
    print_table(["unit", "started", "node", "CPU", "throttled", "throttled s", "others' CPUs", "quiet",
                 "samples/iter", "discarded", "alloc modes", "primary"], rows)
    if centers:
        print("alloc.norm modes (B/op): " + ", ".join(f"{m}: {c:,.0f}" for m, c in enumerate(centers)) + "\n")


def fork_costs(units):
    """median seconds per fork outside its measured iterations, and per iteration beyond its length"""
    overheads = [o for u in units for o in [u.fork_timing()[0]] if o is not None]
    overshoots = [o for u in units for o in [u.fork_timing()[1]] if o is not None]
    return (st.median(overheads) if overheads else None), (st.median(overshoots) if overshoots else 0.0)


def reported(calls):
    """the percentiles that LatencyProfiler reports from iterations with this many calls, and the first one it skips"""
    labels = [label for label, p in PERCENTILES if calls >= calls_needed(p)]
    skipped = next(((label, p) for label, p in PERCENTILES if calls < calls_needed(p)), None)
    return labels, skipped


def probe(units, key, args):
    """prints the probe of one parameter set: its warmup and its calls per second, and the configuration they ask
    for; returns its summary"""
    print(f"## {key}: probe\n")
    all_units = units
    units = [u for u in units if u.quiet]
    print(f"{len(units)} quiet units of {len(all_units)} (other workloads below {args.max_load} CPUs during the"
          " measurement)\n")
    if not units:
        return None

    print("### Warmup trend\n")
    print(f"Median score per iteration relative to the steady state of its fork ({args.metric}, and"
          f" latency.p99.9). Warmup ends where the median stays within {100 * args.tolerance:.1f}%.\n")
    trend = warmup_trend(units, args.metric)
    trend_tail = warmup_trend(units, "latency.p99.9")
    if not trend:
        print("no data\n")
        return None
    rows = []
    for i in sorted(trend):
        r = trend[i]
        q = st.quantiles(r, n=10) if len(r) > 1 else [r[0]] * 9
        tail = trend_tail.get(i)
        rows.append([i, len(r), f"{100 * (st.median(r) - 1):+.2f}%", f"{100 * (q[0] - 1):+.2f}%",
                     f"{100 * (q[-1] - 1):+.2f}%", f"{100 * (st.median(tail) - 1):+.2f}%" if tail else "-"])
    print_table(["iteration", "forks", "median", "p10", "p90", "p99.9 median"], rows)
    end = warmup_end(trend, args.tolerance)
    first = args.warmup + 1 if args.warmup is not None else end
    for unit in all_units:
        unit.first = first
    print(f"Warmup ends after iteration {end - 1}. Steady-state analysis uses iterations from {first} on.\n")
    if args.warmup is None and end > (min(trend) + max(trend)) / 2:
        print("WARNING: the detected warmup covers more than half of the iterations. The warmup may be longer than the"
              " probe's forks, or the forks too noisy; run a probe with more iterations, or pass --warmup.\n")
    tail_off = {i: st.median(r) - 1 for i, r in trend_tail.items() if i >= first}
    later = [abs(d) for i, d in tail_off.items() if i > first]
    if first in tail_off and later and abs(tail_off[first]) > 2 * max(later):
        print(f"WARNING: latency.p99.9 of iteration {first}, the first one analyzed, is"
              f" {100 * tail_off[first]:+.1f}% off its steady state, more than twice as far as any later iteration."
              f" The warmup detection looks at {args.metric} only; a longer warmup (--warmup-length) keeps these"
              " slow calls out of the measurement.\n")

    mode_of, centers = alloc_modes(units)
    print_units(all_units, mode_of, centers)
    print_mechanisms(all_units, args.metric)

    probe_length = units[0].iteration_s
    overhead, overshoot = fork_costs(units)
    # an iteration ends after whole passes, so it records the calls of its length plus the overshoot
    samples = [s / (probe_length + overshoot) for u in units for f in iterations(u, "latency.samples").values()
               for i, s in f if i >= first]
    rate = st.median(samples) if samples else None
    length = max(5, 5 * math.ceil((args.samples / rate - overshoot) / 5)) if rate else probe_length
    warmup_s = (first - 1) * probe_length
    warmup_length = 5 * math.ceil(warmup_s * args.warmup_buffer / 5) if warmup_s > 0 else 0
    print("### Configuration for this parameter set\n")
    print(f"- Iteration length: {length:g} s, for {args.samples / 1e6:g} M calls per iteration at"
          f" {fmt(rate)} calls/s.")
    print(f"- Warmup: {warmup_length:g} s, for {warmup_s:g} s of measured warmup times {args.warmup_buffer:g} as a"
          " buffer." if warmup_s > 0 else "- Warmup: none; the first iteration is already steady.")
    if overhead is not None:
        print(f"- Each fork costs {overhead:.1f} s besides its iterations, and each iteration runs"
              f" {overshoot:.2f} s beyond its length (whole passes).")
    print()
    return {"key": key, "units": units, "all_units": all_units, "warmup_s": warmup_s, "length": length,
            "warmup_length": warmup_length, "rate": rate, "overhead": overhead, "overshoot": overshoot,
            "modes": len(centers)}


def print_configuration_run(probes, args):
    """the configuration that the probes ask for, and the arguments of the run that measures it"""
    length = args.length if args.length is not None else max(s["length"] for s in probes)
    warmup_length = args.warmup_length if args.warmup_length is not None else max(s["warmup_length"] for s in probes)
    warmups = 1 if warmup_length > 0 else 0
    count = args.iterations
    print("## Configuration run\n")
    print((f"@Warmup(iterations = 1, time = {warmup_length:g})" if warmups else "@Warmup(iterations = 0)")
          + f", @Measurement(iterations = {count}, time = {length:g}), in seconds, for all parameter sets. The"
          f" configuration run measures it with {CONFIGURATION_FORKS} forks; @Fork follows from that run.\n")
    rows = []
    total = 0.0
    for s in probes:
        calls = s["rate"] * (length + s["overshoot"]) if s["rate"] else None
        labels, skipped = reported(calls) if calls else ([], None)
        minutes = None
        if s["overhead"] is not None:
            per_fork = (s["overhead"] + warmups * (warmup_length + s["overshoot"])
                        + count * (length + s["overshoot"]))
            minutes = CONFIGURATION_FORKS * per_fork / 60
            total += minutes
        missing = f"{skipped[0]} needs {fmt(calls_needed(skipped[1]) / s['rate'] - s['overshoot'])} s" \
            if skipped and calls else "-"
        rows.append([s["key"], fmt_count(calls), ", ".join(labels) or "-", missing, fmt(minutes, 1)])
    print_table(["parameter set", "calls/iter", "percentiles reported", "first skipped", "run (min)"], rows)
    jmh = f"-f {CONFIGURATION_FORKS} -wi {warmups}" + (f" -w {warmup_length:g}s" if warmups else "") \
        + f" -i {count} -r {length:g}s"
    print(f"jmh-args: `{jmh}`, about {total:.0f} min for all parameter sets, from the probe's fork costs.\n")


def check_warmup(units, metrics, tolerance):
    """the first measured iteration of every fork against its second; a slower first one means the warmup is short"""
    print("### Warmup check\n")
    print("Each fork's first measured iteration against its second, with the 95% half-width of the mean.\n")
    rows = []
    warnings = []
    for metric in metrics:
        d = [math.log(scores[0][1] / scores[1][1]) for u in units for scores in iterations(u, metric).values()
             if len(scores) > 1 and scores[0][1] > 0 and scores[1][1] > 0]
        if len(d) < 2:
            continue
        mean = st.mean(d)
        half = 1.96 * st.stdev(d) / math.sqrt(len(d))
        rows.append([metric, len(d), pct(mean), pct(half)])
        if mean - half > math.log(1 + tolerance):
            warnings.append(f"WARNING: {metric} of the first measured iteration is {pct(mean)} above the second, more"
                            f" than the {100 * tolerance:.1f}% tolerance: the warmup is too short. Lengthen it"
                            " (--warmup-length on the probe) and measure the configuration again.\n")
    print_table(["metric", "forks", "first vs. second", "95% half-width"], rows)
    for w in warnings:
        print(w)


def recommend_forks(c, args):
    """(forks, the fork count before the buffer, why) for the components of --metric"""
    per_fork = c["fork_result"]
    if math.isnan(per_fork):
        return None, None, "one fork per unit: the noise between forks is unknown"
    unit_var = 0.0 if math.isnan(c["unit"]) else c["unit"]
    target = math.log(1 + args.target) ** 2
    # where doubling the forks lowers the CV by less than the gain: forks >= per_fork * (1/2 - g) / (g * unit), with
    # g = 1 - (1 - gain)^2; without a unit component there is no such point
    g = 1 - (1 - args.gain) ** 2
    gain_forks = per_fork * (0.5 - g) / (g * unit_var) if unit_var > 0 and g < 0.5 else math.inf
    # where the predicted CV reaches the target; unreachable if the unit component alone exceeds it
    target_forks = per_fork / (target - unit_var) if target > unit_var else math.inf
    if gain_forks <= target_forks:
        floor_forks = gain_forks
        reason = f"doubling them would lower the predicted CV by less than {100 * args.gain:.0f}%"
    else:
        floor_forks = target_forks
        reason = f"they reach the target CV of {100 * args.target:.2f}%"
    if math.isinf(floor_forks):
        floor_forks = 1
        reason = "the target CV is below the floor between runs, which no fork count lowers"
    return max(2, math.ceil(math.ceil(floor_forks) * args.fork_buffer)), floor_forks, reason


def configuration(units, key, args, probe_summary):
    """prints the analysis of one parameter set's configuration run and returns its summary"""
    print(f"## {key}: configuration\n")
    all_units = units
    units = [u for u in units if u.quiet]
    print(f"{len(units)} quiet units of {len(all_units)} (other workloads below {args.max_load} CPUs during the"
          " measurement)\n")
    if not units:
        return None
    warmups, warmup_length, count, length = units[0].config
    print((f"Warmup 1 × {warmup_length:g} s" if warmups == 1 else f"Warmup {warmups} × {warmup_length:g} s")
          + f", {count} × {length:g} s measured, {fmt(st.mean(len(u.b['primaryMetric']['rawData']) for u in units))}"
          " forks per unit.\n")
    if count < 2:
        print("WARNING: one measurement iteration per fork: the noise within forks is unknown.\n")

    # the probe's forks count for the modes too: the allocation per call does not depend on the iteration length
    mode_of, centers = alloc_modes(units + (probe_summary["units"] if probe_summary else []))
    print_units(all_units, mode_of, centers)
    check_warmup(units, [args.metric, "latency.p99.9"], args.tolerance)

    measured = []
    unmeasured = []
    for metric in args.metrics:
        full, forks = coverage(units, metric)
        (measured if full == forks and forks > 0 else unmeasured).append(metric)
    if unmeasured:
        print("Not reported in every iteration of every fork, so unmeasured: " + ", ".join(unmeasured)
              + " (latency.skipped in the JMH output names the calls they need).\n")

    print("### Noise\n")
    print(f"CV of each component at {length:g} s iterations. \"fork result\" is how much the result of one fork"
          " varies, its iterations' noise included; the unit component is what no fork count lowers. \"mode effect\""
          " is the latency difference between alloc.norm modes.\n")
    rows = []
    pooled = {}
    for metric in measured:
        _, c = components(units, metric)
        if not c:
            continue
        pooled[metric] = c
        effects = mode_effects(units, metric, mode_of) if len(centers) > 1 else {}
        effect = (max(effects.values()) - min(effects.values())) if len(effects) > 1 else None
        rows.append([metric, fmt(math.exp(c["mean"])), pct(math.sqrt(c["within"])), pct(math.sqrt(c["between"])),
                     pct(math.sqrt(c["fork_result"])), pct(effect), pct(math.sqrt(c["v_units"])),
                     pct(math.sqrt(c["unit"]))])
    print_table(["metric", "geo. mean", "iteration", "fork", "fork result", "mode effect", "unit means", "unit"], rows)

    shown = [m for m in measured[:3]]
    for env_key, title in (("cpu_model", "CPU model"), ("boot_id", "node")):
        groups = defaultdict(list)
        for unit in units:
            groups[unit.env(env_key)[:28] if env_key != "boot_id" else unit.node()].append(unit)
        if len(groups) < 2:
            continue
        print(f"### By {title}\n")
        rows = []
        for name, members in sorted(groups.items()):
            row = [name or "-", len(members)]
            for m in shown:
                means = [r["mean"] for r in components(members, m)[0]]
                row += [f"{100 * (math.exp(st.mean(means) - pooled[m]['mean']) - 1):+.2f}%",
                        pct(math.sqrt(var(means))) if len(means) > 1 else "-"]
            rows.append(row)
        print_table([title, "units"] + [f"{m.replace('latency.', '')} {h}" for m in shown
                                        for h in ("vs. all", "CV of units")], rows)

    if args.metric not in pooled:
        return None
    forks, floor_forks, reason = recommend_forks(pooled[args.metric], args)
    print("### Predicted noise of one run's result\n")
    print("CV for f forks of this configuration, including the unit component.\n")
    fork_counts = sorted({1, 2, 3, 5, 10, 20} | ({forks} if forks else set()))
    print_table(["metric"] + [f"f={f}" for f in fork_counts],
                [[metric] + [pct(predicted_cv(c, f)) for f in fork_counts] for metric, c in pooled.items()])
    overhead, overshoot = fork_costs(units)
    print("### Recommendation for this parameter set\n")
    if forks:
        print(f"- Forks: {forks}, {math.ceil(floor_forks)} because {reason}, times {args.fork_buffer:g} as a buffer.")
    else:
        print(f"- Forks: unknown, {reason}.")
    if overhead is not None:
        print(f"- Each fork costs {overhead:.1f} s besides its measured iterations, warmup included, and each"
              f" iteration runs {overshoot:.2f} s beyond its length (whole passes).")
    print()
    samples = [s for u in units for f in iterations(u, "latency.samples").values() for _, s in f]
    return {"key": key, "units": units, "all_units": all_units, "pooled": pooled, "forks": forks,
            "config": units[0].config, "unmeasured": unmeasured, "samples": st.mean(samples) if samples else None,
            "overhead": overhead, "overshoot": overshoot, "modes": len(centers)}


def evaluate(s, forks, args):
    """what one parameter set is expected to give with the chosen fork count"""
    c = s["pooled"][args.metric]
    _, _, count, length = s["config"]
    stable = [m.replace("latency.", "") for m, cm in s["pooled"].items()
              if predicted_cv(cm, forks) <= math.log(1 + args.stable_cv)]
    return {
        "cv": predicted_cv(c, forks), "stable": stable,
        "unmeasured": [m.replace("latency.", "") for m in s["unmeasured"]],
        "runtime": forks * (s["overhead"] + count * (length + s["overshoot"])) if s["overhead"] is not None else None,
        "samples": s["samples"], "cv_iteration": math.sqrt(c["within"]), "cv_fork": math.sqrt(c["between"]),
        "cv_unit": math.sqrt(c["unit"]) if not math.isnan(c["unit"]) else None,
    }


def calibration_block(summaries, probes, forks, args):
    """the calibration record as a Javadoc block (platform-sdk/docs/consensus-layer/benchmarks/calibration.md)"""
    expected = [evaluate(s, forks, args) for s in summaries]
    last = summaries[-1]["units"][-1]
    b = last.b
    # the heap from the annotations and the processor count that the driver pins
    heap = " ".join(a for a in b.get("jvmArgs", [])
                    if a.startswith(("-Xm", "-XX:+AlwaysPreTouch", "-XX:ActiveProcessorCount")))
    node = last.env("dmi_product") or last.node()
    machine = ", ".join(x for x in (last.info.get("runner_label"), node, last.env("cpu_model")) if x)
    date = (last.info.get("started") or "")[:10]
    commit = (last.info.get("commit") or "")[:10]
    units = [u for s in summaries for u in s["units"]] + [u for p in probes.values() for u in p["units"]]
    runs = sorted({u.info["run_id"] for u in units if "run_id" in u.info})
    raw_data = ", ".join(f"https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/{r}" for r in runs) or "?"

    def each(fn):
        return " / ".join(fn(e) for e in expected)

    names = " / ".join(s["key"].split(" ", 1)[1] if " " in s["key"] else s["key"] for s in summaries)
    warmups = " / ".join(f"{probes[s['key']]['warmup_s']:g} s" if s["key"] in probes else "?" for s in summaries)
    quiet = " / ".join(f"{len(s['units'])} of {len(s['all_units'])}" for s in summaries)
    unmeasured = each(lambda e: ", ".join(e["unmeasured"]) or "-")
    lines = [
        "<h2>Calibration</h2>",
        "<ul>",
        f"  <li>Date / commit: {date or '?'}, {commit or '?'}</li>",
        f"  <li>Machine: {machine or '?'}, JDK {b.get('jdkVersion', '?')}, {heap}</li>",
        f"  <li>Parameter sets: {names}</li>",
        f"  <li>Steady state after: {warmups}</li>",
        f"  <li>Noise ({args.metric}, CV): {each(lambda e: pct(e['cv_iteration']))} per {summaries[0]['config'][3]:g} s"
        f" iteration, {each(lambda e: pct(e['cv_fork']))} between forks,"
        f" {each(lambda e: pct(e['cv_unit']) if e['cv_unit'] is not None else '-')} between runs"
        f" ({quiet} runs quiet)</li>",
        f"  <li>Expected detectable regression: {each(lambda e: pct(1.96 * e['cv']))} (95 % CI half-width of one"
        " run)</li>",
        f"  <li>Samples per iteration: {each(lambda e: fmt_count(e['samples']))};"
        f" expected stable: {each(lambda e: ', '.join(e['stable']) or '-')}"
        + (f"; unmeasured: {unmeasured}" if any(e["unmeasured"] for e in expected) else "") + "</li>",
        f"  <li>Runtime: {each(lambda e: fmt(e['runtime'] / 60, 1) if e['runtime'] else '-')} min per parameter"
        " set</li>",
        f"  <li>Raw data: {raw_data}</li>",
        "</ul>",
    ]
    return "\n".join(" * " + line for line in lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", nargs="+", help="result directories or results.json files")
    parser.add_argument("--metrics", default=",".join(DEFAULT_METRICS), help="comma-separated secondary metrics")
    parser.add_argument("--metric", default="latency.mean", help="metric that the recommendation is based on")
    parser.add_argument("--max-load", type=float, default=0.5,
                        help="most CPUs of other workloads for a quiet unit (default 0.5)")
    parser.add_argument("--tolerance", type=float, default=0.01,
                        help="warmup tolerance, in the probe and in the warmup check (default 0.01)")
    parser.add_argument("--warmup", type=int, help="probe: warmup iterations to drop, instead of the detected value")
    parser.add_argument("--benchmark", help="only parameter sets whose key contains this text")
    # p99.99 needs 10^6 calls per iteration; the buffer keeps a night that is 20% slower above that
    parser.add_argument("--samples", type=float, default=1.25e6,
                        help="probe: calls per iteration the iteration length aims for (default 1.25 M)")
    parser.add_argument("--warmup-buffer", type=float, default=2.0,
                        help="probe: factor on the measured warmup time (default 2)")
    parser.add_argument("--length", type=float, help="probe: chosen iteration length in seconds")
    parser.add_argument("--warmup-length", type=float,
                        help="probe: chosen warmup length in seconds, as one iteration; 0 for none")
    parser.add_argument("--iterations", type=int, default=2,
                        help="probe: chosen measurement iterations (default 2)")
    parser.add_argument("--gain", type=float, default=0.10,
                        help="forks stop where doubling them lowers the CV by less than this (default 0.10)")
    parser.add_argument("--target", type=float, default=0.005,
                        help="forks stop where the predicted CV reaches this (default 0.005)")
    parser.add_argument("--fork-buffer", type=float, default=1.5, help="factor on the recommended forks (default 1.5)")
    parser.add_argument("--stable-cv", type=float, default=0.02,
                        help="most predicted CV of a percentile expected to be stable (default 0.02)")
    parser.add_argument("--forks", type=int, help="chosen forks, instead of the recommendation")
    parser.add_argument("--dump", help="write every iteration's scores to this CSV file")
    args = parser.parse_args()
    args.metrics = args.metrics.split(",")

    units = [u for p in args.results for u in load_units(p, args.max_load)]
    units = [u for u in units if not args.benchmark or args.benchmark in u.key]
    probe_units = [u for u in units if u.probe]
    config_units = [u for u in units if not u.probe]
    for kind, group in (("probe", probe_units), ("configuration", config_units)):
        configs = sorted({u.config for u in group})
        if len(configs) > 1:
            raise SystemExit(f"{kind} results measured in {len(configs)} ways (warmup iterations, warmup length,"
                             f" iterations, iteration length): {configs}; analyze them in separate calls")
    keys = sorted({u.key for u in units}, key=lambda k: [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", k)])

    probes = {}
    for key in keys:
        members = [u for u in probe_units if u.key == key]
        s = probe(members, key, args) if members else None
        if s:
            probes[key] = s
    summaries = []
    for key in keys:
        members = [u for u in config_units if u.key == key]
        s = configuration(members, key, args, probes.get(key)) if members else None
        if s:
            summaries.append(s)

    if probes and not config_units:
        print_configuration_run(list(probes.values()), args)
    if summaries:
        recommended = [s["forks"] for s in summaries if s["forks"]]
        forks = args.forks if args.forks is not None else max(recommended) if recommended else None
        if forks is None:
            print("No fork count: pass --forks.\n")
            return 1
        warmups, warmup_length, count, length = summaries[0]["config"]
        print("## Configuration for all parameter sets\n")
        print(f"@Fork({forks}), " + (f"@Warmup(iterations = {warmups}, time = {warmup_length:g})" if warmups
                                     else "@Warmup(iterations = 0)")
              + f", @Measurement(iterations = {count}, time = {length:g}), in seconds.\n")
        rows = []
        for s in summaries:
            e = evaluate(s, forks, args)
            rows.append([s["key"], pct(e["cv"]), pct(1.96 * e["cv"]),
                         fmt_count(e["samples"]), ", ".join(e["stable"]) or "-",
                         ", ".join(e["unmeasured"]) or "-", fmt(e["runtime"] / 60, 1) if e["runtime"] else "-"])
        print_table(["parameter set", f"CV of {args.metric}", "95% half-width", "samples/iter",
                     f"stable (CV ≤ {100 * args.stable_cv:g}%)", "unmeasured", "runtime (min)"], rows)
        commits = {u.info.get("commit") for u in units if u.info.get("commit")}
        if len(commits) > 1:
            print(f"WARNING: the runs measured {len(commits)} commits: {', '.join(sorted(c[:10] for c in commits))}.\n")
        if probes:
            print("### Calibration block\n")
            print("```")
            print(calibration_block(summaries, probes, forks, args))
            print("```\n")
        else:
            print("The calibration block also needs the probe's runs.\n")

    if args.dump:
        import csv
        with open(args.dump, "w", newline="") as f:
            out = csv.writer(f)
            monitored = ["others", "hot_mhz", "sibling", "l3", "migrated"]
            extra = ["alloc.norm", "latency.samples"]
            out.writerow(["unit", "node", "benchmark", "quiet", "fork", "iteration", "end_epoch"] + monitored
                         + args.metrics + extra)
            for unit in units:
                series = {m: iterations(unit, m) for m in args.metrics + extra}
                for fork, scores in series[args.metrics[0]].items():
                    for n, (i, _) in enumerate(scores):
                        end_ts = unit.times.get((unit.key, fork, i))
                        loads = [unit.monitor_mean(f, end_ts - unit.iteration_s, end_ts) if end_ts else None
                                 for f in monitored]
                        out.writerow([unit.label, unit.node(), unit.key, unit.quiet, fork, i, end_ts or ""]
                                     + ["" if v is None else f"{v:.3f}" for v in loads]
                                     + [series[m][fork][n][1] if fork in series[m] and n < len(series[m][fork])
                                        else "" for m in args.metrics + extra])


if __name__ == "__main__":
    sys.exit(main())
