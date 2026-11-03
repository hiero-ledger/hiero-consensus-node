#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Calibration analysis of a JMH benchmark: where warmup ends, how the noise splits into iterations, forks, and runs,
which run configuration reaches the noise floor, and the Javadoc calibration block for it.

Usage: analyze_calibration.py [options] <results>...

A result is a directory written by run_benchmarks.py (a runs.json with one JMH invocation per parameter set and
attempt), a directory with a single results.json somewhere below it (an older evaluation run or a laptop run), or a
results.json file. Next to the JSON, the files of the benchmark workflows are used if present: run-info.properties,
environment-before.txt and environment-after.txt from record-benchmark-environment.sh, monitor.csv from its --monitor
mode, and JMH's output with a time stamp before every line. All results given in one call are analyzed as one
population; analyze results measured in different ways (nightly through Gradle, calibration runs, laptop) in separate
calls.

Method:
- A unit is one measurement of one parameter set: one JMH invocation of run_benchmarks.py, or one parameter set of an
  older results.json. Only quiet units count: the other workloads on the node used fewer than --max-load CPUs on
  average over the unit's measurement iterations. Units without a monitor (a laptop) count as quiet.
- Scores are analyzed as logarithms, so variances are relative and a standard deviation of 0.01 is about 1%.
- Warmup: each iteration's score divided by the mean of the last half of its fork, then the median over all forks per
  iteration index. Warmup ends at the first index after which this median stays within --tolerance of 1. JMH writes
  only measurement iterations to the JSON, so calibration runs use -wi 0 to see the whole trend.
- Variance components (method of moments, nested): within a fork (iterations), between the forks of a unit, and between
  units. Each component is reported as a coefficient of variation (CV). The component between forks excludes what the
  iterations' noise contributes to the fork means, and likewise for units. The component between units is the floor
  that no fork or iteration count lowers.
- alloc.norm modes: forks whose allocation per operation differs by more than 3% fall into separate modes, usually
  because the JIT compiled the code differently. The mode effect is the latency difference between modes within units.
- Prediction: the CV of one run's result with f forks and i iterations of length r is
  sqrt(unit^2 + fork^2 / f + iteration^2 * r0 / (r * f * i)), where r0 is the measured iteration length; an iteration's
  noise is assumed to average out with its length.
- Recommendation, from --metric (latency.mean by default):
  - iteration length: the shortest multiple of 5 s that collects --samples samples per iteration, so that the highest
    percentile can be estimated from one iteration;
  - warmup: the detected warmup time times --warmup-buffer, rounded up to whole iterations;
  - iterations: the fewest whose noise is at most a tenth of the fork noise in a fork's mean, at least 2, so that the
    noise within forks stays measurable; fewer if one fork already reaches --target, or if doubling the iterations
    would lower a fork's CV by less than --gain;
  - forks: the fewest that reach a predicted CV of --target, or after which doubling them would lower the predicted
    CV by less than --gain, whichever is fewer; times --fork-buffer. In quiet slots the unit component is small, so
    the target usually decides: it is the trade between noise and CI time.
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


def pct(x):
    """a standard deviation of logarithms as a percentage"""
    return "-" if x is None or math.isnan(x) else f"{100 * (math.exp(x) - 1):.2f}%"


def var(xs):
    return st.variance(xs) if len(xs) > 1 else float("nan")


def seconds(text):
    """JMH's "5 s", "500 ms" as seconds"""
    value, unit = str(text).split()
    return float(value) * {"ns": 1e-9, "us": 1e-6, "ms": 1e-3, "s": 1.0, "min": 60.0}[unit]


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
    """{fork: [(iteration index, score)]} with iteration indices counted from the first warmup iteration"""
    b = unit.b
    raw = b["primaryMetric"]["rawData"] if metric == "primary" else b["secondaryMetrics"].get(metric, {}).get("rawData")
    if not raw:
        return {}
    offset = b.get("warmupIterations", 0)
    return {fork + 1: [(offset + i + 1, s) for i, s in enumerate(scores)] for fork, scores in enumerate(raw)}


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


def alloc_modes(units, first):
    """{(unit label, fork): mode index}, mode centers"""
    per_fork = {}
    for unit in units:
        for fork, scores in iterations(unit, "alloc.norm").items():
            steady = [s for i, s in scores if i >= first]
            if steady:
                per_fork[(unit.label, fork)] = st.mean(steady)
    if not per_fork:
        return {}, []
    values = sorted(per_fork.values())
    bounds = [b for a, b in zip(values, values[1:]) if b > a * (1 + ALLOC_MODE_GAP)]
    mode_of = {k: sum(v >= b for b in bounds) for k, v in per_fork.items()}
    centers = [st.median([v for k, v in per_fork.items() if mode_of[k] == m]) for m in range(len(bounds) + 1)]
    return mode_of, centers


def fork_logs(unit, metric, first, mode_of=None, mode_effect=None):
    """{fork: [log score of each steady-state iteration]}, optionally adjusted by the effect of the fork's mode"""
    logs = {}
    for fork, scores in iterations(unit, metric).items():
        steady = [math.log(s) for i, s in scores if i >= first and s > 0]
        if steady:
            adjust = mode_effect.get(mode_of.get((unit.label, fork)), 0.0) if mode_effect else 0.0
            logs[fork] = [x - adjust for x in steady]
    return logs


def components(units, metric, first, mode_of=None, mode_effect=None):
    """variance components of log scores; returns per-unit rows and the pooled components"""
    rows = []
    for unit in units:
        logs = fork_logs(unit, metric, first, mode_of, mode_effect)
        if not logs:
            continue
        n_iter = st.mean(len(v) for v in logs.values())
        within = st.mean(var(v) for v in logs.values()) if n_iter > 1 else float("nan")
        means = [st.mean(v) for v in logs.values()]
        v_forks = var(means)
        between = max(0.0, v_forks - within / n_iter) if not math.isnan(v_forks) else float("nan")
        rows.append({"unit": unit, "mean": st.mean(means), "within": within, "between": between, "forks": len(means),
                     "iterations": n_iter})
    if not rows:
        return rows, None
    within = st.mean(r["within"] for r in rows)
    between = st.mean(r["between"] for r in rows)
    n_forks = st.mean(r["forks"] for r in rows)
    n_iter = st.mean(r["iterations"] for r in rows)
    v_units = var([r["mean"] for r in rows])
    unit = max(0.0, v_units - between / n_forks - within / (n_forks * n_iter)) if not math.isnan(v_units) \
        else float("nan")
    return rows, {"within": within, "between": between, "unit": unit, "v_units": v_units, "forks": n_forks,
                  "iterations": n_iter, "mean": st.mean(r["mean"] for r in rows), "units": len(rows)}


def mode_effects(units, metric, first, mode_of):
    """log latency difference of every mode from the unit's mean, estimated from units that have several modes"""
    deviations = defaultdict(list)
    for unit in units:
        fork_means = {fork: (mode_of[(unit.label, fork)], st.mean(v))
                      for fork, v in fork_logs(unit, metric, first).items() if (unit.label, fork) in mode_of}
        modes = {m for m, _ in fork_means.values()}
        if len(modes) < 2:
            continue
        # each mode weighs equally, so the effects do not depend on the mix of modes in the unit
        per_mode = {m: st.mean([x for mm, x in fork_means.values() if mm == m]) for m in modes}
        center = st.mean(per_mode.values())
        for m, x in per_mode.items():
            deviations[m].append(x - center)
    return {m: st.mean(d) for m, d in deviations.items()}


def predicted_cv(c, forks, iterations_, length, measured_length):
    """CV of one run's result, as a standard deviation of logarithms"""
    unit = 0.0 if math.isnan(c["unit"]) else c["unit"]
    within = 0.0 if math.isnan(c["within"]) else c["within"] * measured_length / length
    return math.sqrt(unit + c["between"] / forks + within / (forks * iterations_))


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


def iteration_features(units, metric, first):
    """per steady-state iteration: (fork id, log score, {feature: value}) for units with a monitor and time stamps"""
    points = []
    for unit in units:
        if not unit.monitor or not unit.times:
            continue
        for fork, scores in iterations(unit, metric).items():
            for i, s in scores:
                end_ts = unit.times.get((unit.key, fork, i))
                if i < first or not end_ts or s <= 0:
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


def print_mechanisms(units, metric, first):
    """relates each iteration's score to the load around the benchmark thread, within forks"""
    points = iteration_features(units, metric, first)
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
    unit_points = [(r["unit"].others, r["mean"]) for r in components(units, metric, first)[0]
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


def recommend(units, c, first, measured_length, args):
    """the recommended configuration and what it is expected to give, from the pooled components of --metric"""
    samples = [s / measured_length for u in units for f in iterations(u, "latency.samples").values()
               for i, s in f if i >= first]
    rate = st.median(samples) if samples else None
    length = max(5, 5 * math.ceil(args.samples / rate / 5)) if rate else measured_length
    warmup_s = (first - 1) * measured_length
    warmup = max(1, math.ceil(warmup_s * args.warmup_buffer / length)) if warmup_s > 0 else 0
    if math.isnan(c["between"]):
        print("WARNING: fewer than two forks per unit, so the noise between forks is unknown and the fork count"
              " below is meaningless.\n")
    fork_var = 0.0 if math.isnan(c["between"]) else c["between"]
    iteration_var = 0.0 if math.isnan(c["within"]) else c["within"] * measured_length / length
    unit_var = 0.0 if math.isnan(c["unit"]) else c["unit"]
    target = math.log(1 + args.target) ** 2
    count = 2
    iteration_reason = "whose noise is at most a tenth of the fork noise"
    while fork_var > 0 and iteration_var / count > fork_var / 10 and count < 50:
        # with forks that barely differ, the tenth above asks for dozens of iterations that lower no noise that matters
        if unit_var + fork_var + iteration_var / count <= target:
            iteration_reason = f"with which one fork reaches the target CV of {100 * args.target:.2f}%"
            break
        if fork_var + iteration_var / (2 * count) > (1 - args.gain) ** 2 * (fork_var + iteration_var / count):
            iteration_reason = f"after which doubling them would lower a fork's CV by less than {100 * args.gain:.0f}%"
            break
        count += 1
    per_fork = fork_var + iteration_var / count
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
    forks = max(2, math.ceil(math.ceil(floor_forks) * args.fork_buffer))
    overheads = [o for u in units for o in [u.fork_timing()[0]] if o is not None]
    overshoots = [o for u in units for o in [u.fork_timing()[1]] if o is not None]
    overhead = st.median(overheads) if overheads else None
    overshoot = st.median(overshoots) if overshoots else 0.0
    runtime = forks * (overhead + (warmup + count) * (length + overshoot)) if overhead is not None else None
    return {"length": length, "rate": rate, "warmup": warmup, "warmup_s": warmup_s, "iterations": count,
            "iteration_reason": iteration_reason, "forks": forks, "floor_forks": floor_forks, "reason": reason,
            "overhead": overhead, "overshoot": overshoot, "runtime": runtime}


def analyze(units, key, args):
    """prints the analysis of one parameter set and returns its summary for the calibration block"""
    print(f"## {key}\n")
    all_units = units
    units = [u for u in units if u.quiet]
    print(f"{len(units)} quiet units of {len(all_units)} (other workloads below {args.max_load} CPUs during the"
          " measurement)\n")
    if not units:
        return None

    # warmup
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
    print(f"Warmup ends after iteration {end - 1}. Steady-state analysis uses iterations from {first} on.\n")
    if args.warmup is None and end > (min(trend) + max(trend)) / 2:
        print("WARNING: the detected warmup covers more than half of the iterations. Changing load or too few units can"
              " hide where warmup ends; pass --warmup with the value from pooled units.\n")
    tail_off = {i: st.median(r) - 1 for i, r in trend_tail.items() if i >= first}
    later = [abs(d) for i, d in tail_off.items() if i > first]
    if first in tail_off and later and abs(tail_off[first]) > 2 * max(later):
        print(f"WARNING: latency.p99.9 of iteration {first}, the first one analyzed, is"
              f" {100 * tail_off[first]:+.1f}% off its steady state, more than twice as far as any later iteration."
              " The warmup detection looks at"
              f" {args.metric} only; a short warmup iteration (--warmup-iterations, --warmup-length) keeps these slow"
              " calls out of the measurement.\n")

    # units
    print("### Units\n")
    mode_of, centers = alloc_modes(units, first)
    rows = []
    for unit in all_units:
        samples = [s for f in iterations(unit, "latency.samples").values() for i, s in f if i >= first]
        discarded = sum(s for f in iterations(unit, "measured.gc.discardedPasses").values() for _, s in f)
        modes = defaultdict(int)
        for (label, _), m in mode_of.items():
            if label == unit.label:
                modes[m] += 1
        throttled_s = unit.counter_delta("cgroup_throttled_usec")
        rows.append([
            unit.label, unit.info.get("started", "")[5:16], unit.node() or "-", unit.env("cpu_model")[:28] or "-",
            fmt(unit.counter_delta("cgroup_nr_throttled")), fmt(throttled_s / 1e6, 1) if throttled_s is not None else "-",
            fmt(unit.others, 2), "yes" if unit.quiet else "no",
            fmt(st.mean(samples) / 1e3 if samples else None) + "k", fmt(discarded),
            "/".join(str(modes[m]) for m in range(len(centers))) if unit.quiet else "-",
            fmt(unit.b["primaryMetric"]["score"], 3),
        ])
    print_table(["unit", "started", "node", "CPU", "throttled", "throttled s", "others' CPUs", "quiet",
                 "samples/iter", "discarded", "alloc modes", "primary"], rows)
    if centers:
        print("alloc.norm modes (B/op): " + ", ".join(f"{m}: {c:,.0f}" for m, c in enumerate(centers)) + "\n")
    within_alloc = []
    for unit in units:
        for scores in iterations(unit, "alloc.norm").values():
            steady = [s for i, s in scores if i >= first]
            if len(steady) > 1 and st.mean(steady) > 0:
                within_alloc.append(st.stdev(steady) / st.mean(steady))
    if within_alloc and max(within_alloc) > 0.01:
        print(f"WARNING: alloc.norm varies within a fork by up to {100 * max(within_alloc):.1f}%\n")

    # variance components
    print("### Noise\n")
    print("CV of each component; the unit component is what calibration cannot reduce. \"fork, mode-adjusted\""
          " removes the latency difference between alloc.norm modes.\n")
    rows = []
    pooled = {}
    for metric in args.metrics:
        _, c = components(units, metric, first)
        if not c:
            continue
        pooled[metric] = c
        effects = mode_effects(units, metric, first, mode_of) if len(centers) > 1 else {}
        _, adjusted = components(units, metric, first, mode_of, effects) if effects else (None, None)
        effect = (max(effects.values()) - min(effects.values())) if len(effects) > 1 else None
        rows.append([metric, fmt(math.exp(c["mean"])), pct(math.sqrt(c["within"])), pct(math.sqrt(c["between"])),
                     pct(math.sqrt(adjusted["between"])) if adjusted else "-", pct(effect),
                     pct(math.sqrt(c["v_units"])), pct(math.sqrt(c["unit"]))])
    print_table(["metric", "geo. mean", "iteration", "fork", "fork, mode-adjusted", "mode effect",
                 "unit means", "unit"], rows)

    print("Per unit, CV within a fork and between forks:\n")
    shown = args.metrics[:3]
    per_metric = {m: {r["unit"].label: r for r in components(units, m, first)[0]} for m in shown}
    rows = []
    for unit in units:
        row = [unit.label]
        for m in shown:
            r = per_metric[m].get(unit.label)
            row += [fmt(math.exp(r["mean"])), pct(math.sqrt(r["within"])), pct(math.sqrt(r["between"]))] if r \
                else ["-", "-", "-"]
        rows.append(row)
    print_table(["unit"] + [f"{m.replace('latency.', '')} {h}" for m in shown for h in ("mean", "iter", "fork")],
                rows)

    # nodes and CPU models
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
                means = [r["mean"] for r in components(members, m, first)[0]]
                row += [f"{100 * (math.exp(st.mean(means) - pooled[m]['mean']) - 1):+.2f}%",
                        pct(math.sqrt(var(means))) if len(means) > 1 else "-"]
            rows.append(row)
        print_table([title, "units"] + [f"{m.replace('latency.', '')} {h}" for m in shown
                                        for h in ("vs. all", "CV of units")], rows)

    # load from other workloads, and how it acts
    print_mechanisms(all_units, args.metric, first)

    if args.metric not in pooled:
        return None
    measured_length = units[0].iteration_s
    rec = recommend(units, pooled[args.metric], first, measured_length, args)
    length = rec["length"] if args.length is None else args.length
    count = rec["iterations"] if args.iterations is None else args.iterations

    # prediction
    print("### Predicted noise of one run's result\n")
    print(f"CV for f forks and i iterations of {length:g} s, including the unit component.\n")
    fork_counts = sorted({1, 2, 3, 5, 10, 20, 40, rec["forks"]})
    rows = []
    for metric, c in pooled.items():
        for i in sorted({3, 5, 10, count}):
            rows.append([metric, i] + [pct(predicted_cv(c, f, i, length, measured_length)) for f in fork_counts])
    print_table(["metric", "iterations"] + [f"f={f}" for f in fork_counts], rows)

    print("### Recommendation for this parameter set\n")
    print(f"- Iteration length: {rec['length']:g} s, for {args.samples / 1e6:g} M samples per iteration at"
          f" {fmt(rec['rate'] / 1e3 if rec['rate'] else None)}k samples/s.")
    print(f"- Warmup: {rec['warmup']} iteration(s), for {rec['warmup_s']:g} s of measured warmup times"
          f" {args.warmup_buffer:g} as a buffer.")
    print(f"- Iterations: {rec['iterations']}, the fewest (at least 2) {rec['iteration_reason']}.")
    print(f"- Forks: {rec['forks']}, {math.ceil(rec['floor_forks'])} because {rec['reason']}, times"
          f" {args.fork_buffer:g} as a buffer.")
    if rec["overhead"] is not None:
        print(f"- Each fork costs {rec['overhead']:.1f} s besides its iterations, and each iteration runs"
              f" {rec['overshoot']:.2f} s beyond its length (whole passes).")
    print()
    return {"key": key, "units": units, "all_units": all_units, "pooled": pooled, "rec": rec,
            "measured_length": measured_length, "modes": len(centers)}


def evaluate(s, config, args):
    """what one parameter set is expected to give with the chosen configuration"""
    c = s["pooled"][args.metric]
    rec = s["rec"]
    ml = s["measured_length"]
    forks, warmup, count, length = config["forks"], config["warmup"], config["iterations"], config["length"]
    warmup_length = config["warmup_length"]
    stable = [m.replace("latency.", "") for m, cm in s["pooled"].items()
              if predicted_cv(cm, forks, count, length, ml) <= math.log(1 + args.stable_cv)]
    return {
        "cv": predicted_cv(c, forks, count, length, ml), "stable": stable,
        "runtime": forks * (rec["overhead"] + warmup * (warmup_length + rec["overshoot"])
                            + count * (length + rec["overshoot"]))
        if rec["overhead"] is not None else None,
        "samples": rec["rate"] * length if rec["rate"] else None,
        "cv_iteration": math.sqrt(c["within"]), "cv_fork": math.sqrt(c["between"]),
        "cv_unit": math.sqrt(c["unit"]) if not math.isnan(c["unit"]) else None,
    }


def choose(summaries, args):
    """one configuration for all parameter sets, since the annotations hold for the whole class: the largest of
    their recommendations, unless given on the command line"""
    def pick(name, given):
        return given if given is not None else max(s["rec"][name] for s in summaries)
    length = pick("length", args.length)
    return {"forks": pick("forks", args.forks), "warmup": pick("warmup", args.warmup_iterations),
            "iterations": pick("iterations", args.iterations), "length": length,
            "warmup_length": args.warmup_length if args.warmup_length is not None else length}


def calibration_block(summaries, config, args):
    """the calibration record as a Javadoc block (platform-sdk/docs/consensus-layer/benchmarks/calibration.md)"""
    expected = [evaluate(s, config, args) for s in summaries]
    last = summaries[-1]["units"][-1]
    b = last.b
    # the heap from the annotations and the processor count that the driver pins
    heap = " ".join(a for a in b.get("jvmArgs", [])
                    if a.startswith(("-Xm", "-XX:+AlwaysPreTouch", "-XX:ActiveProcessorCount")))
    node = last.env("dmi_product") or last.node()
    machine = ", ".join(x for x in (last.info.get("runner_label"), node, last.env("cpu_model")) if x)
    date = (last.info.get("started") or "")[:10]
    commit = (last.info.get("commit") or "")[:10]
    runs = sorted({u.info["run_id"] for s in summaries for u in s["units"] if "run_id" in u.info})
    raw_data = ", ".join(f"https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/{r}" for r in runs) or "?"

    def each(fn):
        return " / ".join(fn(e) for e in expected)

    names = " / ".join(s["key"].split(" ", 1)[1] if " " in s["key"] else s["key"] for s in summaries)
    warmups = " / ".join(f"{s['rec']['warmup_s']:g} s" for s in summaries)
    quiet = " / ".join(f"{len(s['units'])} of {len(s['all_units'])}" for s in summaries)
    lines = [
        "<h2>Calibration</h2>",
        "<ul>",
        f"  <li>Date / commit: {date or '?'}, {commit or '?'}</li>",
        f"  <li>Machine: {machine or '?'}, JDK {b.get('jdkVersion', '?')}, {heap}</li>",
        f"  <li>Parameter sets: {names}</li>",
        f"  <li>Steady state after: {warmups}</li>",
        f"  <li>Noise ({args.metric}, CV): {each(lambda e: pct(e['cv_iteration']))} per {summaries[0]['measured_length']:g} s"
        f" iteration, {each(lambda e: pct(e['cv_fork']))} between forks,"
        f" {each(lambda e: pct(e['cv_unit']) if e['cv_unit'] is not None else '-')} between runs"
        f" ({quiet} runs quiet)</li>",
        f"  <li>Expected detectable regression: {each(lambda e: pct(1.96 * e['cv']))} (95 % CI half-width of one"
        " run)</li>",
        f"  <li>Samples per iteration: {each(lambda e: fmt(e['samples'] / 1e6, 1) + ' M' if e['samples'] else '-')};"
        f" expected stable: {each(lambda e: ', '.join(e['stable']) or '-')}</li>",
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
    parser.add_argument("--tolerance", type=float, default=0.01, help="warmup tolerance (default 0.01)")
    parser.add_argument("--warmup", type=int, help="warmup iterations to drop, instead of the detected value")
    parser.add_argument("--benchmark", help="only parameter sets whose key contains this text")
    # p99.99 needs 10^6 samples per iteration; the buffer keeps a night that is 20% slower above that
    parser.add_argument("--samples", type=float, default=1.25e6,
                        help="samples per iteration the recommendation aims for (default 1.25 M)")
    parser.add_argument("--warmup-buffer", type=float, default=2.0,
                        help="factor on the measured warmup time (default 2)")
    parser.add_argument("--gain", type=float, default=0.10,
                        help="forks stop where doubling them lowers the CV by less than this (default 0.10)")
    parser.add_argument("--target", type=float, default=0.005,
                        help="forks stop where the predicted CV reaches this (default 0.005)")
    parser.add_argument("--fork-buffer", type=float, default=1.5, help="factor on the recommended forks (default 1.5)")
    parser.add_argument("--stable-cv", type=float, default=0.02,
                        help="most predicted CV of a percentile expected to be stable (default 0.02)")
    parser.add_argument("--forks", type=int, help="chosen forks, instead of the recommendation")
    parser.add_argument("--iterations", type=int, help="chosen measurement iterations")
    parser.add_argument("--length", type=float, help="chosen iteration length in seconds")
    parser.add_argument("--warmup-iterations", type=int, help="chosen warmup iterations")
    parser.add_argument("--warmup-length", type=float,
                        help="chosen warmup iteration length in seconds (default: the iteration length)")
    parser.add_argument("--dump", help="write every iteration's scores to this CSV file")
    args = parser.parse_args()
    args.metrics = args.metrics.split(",")

    units = [u for p in args.results for u in load_units(p, args.max_load)]
    keys = sorted({u.key for u in units if not args.benchmark or args.benchmark in u.key},
                  key=lambda k: [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", k)])
    summaries = [s for key in keys for s in [analyze([u for u in units if u.key == key], key, args)] if s]
    if summaries:
        config = choose(summaries, args)
        print("## Configuration for all parameter sets\n")
        print(f"@Fork({config['forks']}), @Warmup(iterations = {config['warmup']}, time = {config['warmup_length']:g}),"
              f" @Measurement(iterations = {config['iterations']}, time = {config['length']:g}), in seconds.\n")
        rows = []
        for s in summaries:
            e = evaluate(s, config, args)
            rows.append([s["key"], pct(e["cv"]), pct(1.96 * e["cv"]),
                         fmt(e["samples"] / 1e6, 1) + " M" if e["samples"] else "-", ", ".join(e["stable"]) or "-",
                         fmt(e["runtime"] / 60, 1) if e["runtime"] else "-"])
        print_table(["parameter set", f"CV of {args.metric}", "95% half-width", "samples/iter",
                     f"stable (CV ≤ {100 * args.stable_cv:g}%)", "runtime (min)"], rows)
        print("### Calibration block\n")
        print("```")
        print(calibration_block(summaries, config, args))
        print("```\n")

    if args.dump:
        import csv
        with open(args.dump, "w", newline="") as f:
            out = csv.writer(f)
            monitored = ["others", "hot_mhz", "sibling", "l3", "migrated"]
            extra = ["alloc.norm", "latency.samples"]
            out.writerow(["unit", "node", "benchmark", "quiet", "fork", "iteration", "end_epoch"] + monitored
                         + args.metrics + extra)
            for unit in units:
                if unit.key not in keys:
                    continue
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
