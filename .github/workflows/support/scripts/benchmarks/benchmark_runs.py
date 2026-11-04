# SPDX-License-Identifier: Apache-2.0
"""Reads what run_benchmarks.py writes: the run index, the time-stamped JMH output, and the monitor of
record-benchmark-environment.sh. Shared by the driver, the converter, and the calibration analysis."""
import csv
import json
import os
import re

# jiffies per second in /proc/stat
USER_HZ = 100
RUN_INDEX = "runs.json"


def read_properties(path):
    """key=value lines, as written by the workflows' run-info.properties"""
    props = {}
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                if "=" in line:
                    key, value = line.rstrip("\n").split("=", 1)
                    props[key] = value
    return props


def read_environment(path):
    """the key=value lines of the summary section of record-benchmark-environment.sh"""
    env = {}
    if os.path.exists(path):
        inside = False
        with open(path) as f:
            for line in f:
                line = line.rstrip("\n")
                if line == "== summary":
                    inside = True
                elif line == "== end summary":
                    break
                elif inside and "=" in line:
                    key, value = line.split("=", 1)
                    env[key] = value
    return env


def read_monitor(path):
    """Per-interval values between consecutive monitor samples, as dicts with "start", "end" and:
    others: CPUs used by other workloads on the host (host CPU time minus the container's own); steal: CPUs stolen by a
    hypervisor; throttled: throttled periods; and, from monitors that record them, the CPU of the container's busiest
    thread (hot_cpu), whether it changed (migrated), its clock (hot_mhz), the busy fraction of its hyperthread sibling
    (sibling), and the busy CPUs among the others sharing its L3 cache (l3)."""
    if not os.path.exists(path):
        return []
    rows = []
    with open(path) as f:
        for row in csv.DictReader(f):
            try:
                rows.append({k: float(v) if v not in ("", None) else None for k, v in row.items()})
            except ValueError:
                # a line cut off when the monitor was stopped
                pass
    intervals = []
    for a, b in zip(rows, rows[1:]):
        if a.get("epoch") is None or b.get("epoch") is None or a.get("cpu_user") is None or b.get("cpu_user") is None:
            continue
        dt = b["epoch"] - a["epoch"]
        if dt <= 0:
            continue
        busy = sum(b[k] - a[k] for k in ("cpu_user", "cpu_nice", "cpu_system", "cpu_irq", "cpu_softirq"))
        own = (b["cg_usage_usec"] - a["cg_usage_usec"]) / 1e6 if b.get("cg_usage_usec") is not None else 0.0
        interval = {
            "start": a["epoch"],
            "end": b["epoch"],
            "others": busy / USER_HZ / dt - own / dt,
            "steal": (b["cpu_steal"] - a["cpu_steal"]) / USER_HZ / dt,
            "throttled": (b["cg_nr_throttled"] - a["cg_nr_throttled"]) if b.get("cg_nr_throttled") is not None else 0,
        }
        if b.get("hot_cpu") is not None and b.get("hot_cpu_mhz"):
            interval.update(hot_cpu=b["hot_cpu"], migrated=float(a.get("hot_cpu") != b["hot_cpu"]),
                            hot_mhz=b["hot_cpu_mhz"], sibling=b["sibling_busy"], l3=b["l3_others_busy"])
        intervals.append(interval)
    # The kernel reports a CPU's clock only while it runs; after a short pause, such as a GC, it reports the governor's
    # idle value (1.5-2.9 GHz on the EPYC runner, against 4.15 GHz while running). Keep the readings of a running CPU.
    clocks = [i["hot_mhz"] for i in intervals if i.get("hot_mhz")]
    for i in intervals:
        if i.get("hot_mhz") and i["hot_mhz"] < 0.75 * max(clocks):
            i["hot_mhz"] = None
    return intervals


def monitor_mean(intervals, field, windows):
    """Mean of a monitor field over the given (start, end) windows, weighted by overlap; None without data."""
    total = weight = 0.0
    for i in intervals:
        value = i.get(field)
        if value is None:
            continue
        for start, end in windows:
            overlap = min(end, i["end"]) - max(start, i["start"])
            if overlap > 0:
                total += value * overlap
                weight += overlap
    return total / weight if weight > 0 else None


def bench_key(benchmark, params):
    """short name of a benchmark and its parameters, such as "ConsensusImplBenchmark.calculateConsensus numNodes=4\""""
    short = ".".join(benchmark.split(".")[-2:])
    text = ",".join(f"{k}={v}" for k, v in sorted(params.items()))
    return f"{short} {text}" if text else short


def read_iteration_times(path):
    """When every measurement iteration ended, from JMH output with a time stamp before every line:
    {(benchmark key, fork, iteration): epoch}, and the iteration length in seconds per benchmark key."""
    times = {}
    lengths = {}
    if not os.path.exists(path):
        return times, lengths
    bench = None
    params = {}
    fork = 0
    # JMH prints the iteration length before the benchmark's name
    length = None
    with open(path, errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split(" ", 1)
            if len(parts) < 2:
                continue
            try:
                ts = float(parts[0])
            except ValueError:
                continue
            text = parts[1]
            if text.startswith("# Benchmark: "):
                bench = text[len("# Benchmark: "):].strip()
                params = {}
            elif text.startswith("# Parameters: "):
                inner = text[len("# Parameters: "):].strip().strip("()")
                params = dict(p.split(" = ", 1) for p in inner.split(", ") if " = " in p)
            elif text.startswith("# Measurement: "):
                m = re.search(r"(\d+(?:\.\d+)?) (ns|us|ms|s|min) each", text)
                if m:
                    scale = {"ns": 1e-9, "us": 1e-6, "ms": 1e-3, "s": 1.0, "min": 60.0}[m.group(2)]
                    length = float(m.group(1)) * scale
            elif text.startswith("# Fork: "):
                fork = int(text.split()[2])
            else:
                m = re.match(r"Iteration\s+(\d+):", text)
                if m and bench:
                    key = bench_key(bench, params)
                    times[(key, fork, int(m.group(1)))] = ts
                    if length is not None:
                        lengths[key] = length
    return times, lengths


def measurement_windows(output_path, key=None):
    """(start, end) of every measurement iteration in a time-stamped JMH output, optionally of one benchmark key"""
    times, lengths = read_iteration_times(output_path)
    return [(end - lengths.get(k, 0.0), end) for (k, _, _), end in sorted(times.items(), key=lambda t: t[1])
            if key is None or k == key]


def read_run_index(directory):
    """the attempts that run_benchmarks.py recorded in a results directory, or an empty list"""
    path = os.path.join(directory, RUN_INDEX)
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return json.load(f)["attempts"]
