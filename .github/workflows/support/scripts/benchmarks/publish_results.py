#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Converts the results of run_benchmarks.py into labeled metrics and pushes them to a Prometheus remote-write endpoint
such as Mimir.

Usage: publish_results.py --results DIR [--url URL] [--dry-run]

Publishes one result per parameter set: its quiet attempt, or, if no attempt was quiet, its least loaded one. Every
series carries the labels benchmark, params, commit, jdk, runner, node, and run_type.

Metrics (one sample each, at the time of publishing). Only a quiet result publishes the metrics that depend on the
load of other workloads on the node, so every latency sample can give a verdict; a result is quiet if the other
workloads used fewer than run_benchmarks.py's --max-load CPUs during the measurement:
- jmh_latency_seconds{statistic=mean|min|p50|p90|p99|p99.9|p99.99|max}, quiet only: LatencyProfiler's latency.*
  metrics. The mean over the kept passes is tracked rather than JMH's primary score, which also contains discarded
  passes;
- jmh_latency_samples, quiet only: latency samples of the whole run;
- jmh_allocation_bytes_per_op: alloc.norm;
- jmh_gc_discarded_passes: measured.gc.discardedPasses, passes dropped because a collection hit them;
- jmh_other_load_cpus: CPUs used by other workloads during the measurement;
- jmh_attempts: how many attempts the parameter set needed.

With --loki-url, it also pushes one log line per published result to Loki, labeled job="jmh-benchmarks", benchmark,
params, and run_type. The line is a JSON object with the commit, the run's URL, the slot ("quiet", "loaded", or
"unknown" without monitor data), and the headline values, so that dashboards can show which commit each point belongs
to, and why a night has no latency.

JMH writes a missing error as the string "NaN"; scores are read from "score" only, so errors never matter here.

The user name and password for basic authentication come from the environment variables BENCHMARK_METRICS_USER and
BENCHMARK_METRICS_PASSWORD.
"""
import argparse
import base64
import json
import math
import os
import struct
import sys
import time
import urllib.request

from benchmark_runs import read_environment, read_properties, read_run_index

LATENCY = {"latency.mean": "mean", "latency.min": "min", "latency.p50": "p50", "latency.p90": "p90",
           "latency.p99": "p99", "latency.p99.9": "p99.9", "latency.p99.99": "p99.99", "latency.max": "max"}
LOAD_DEPENDENT = {"latency.samples": "jmh_latency_samples"}
LOAD_INDEPENDENT = {"alloc.norm": "jmh_allocation_bytes_per_op",
                    "measured.gc.discardedPasses": "jmh_gc_discarded_passes"}
SLOTS = {True: "quiet", False: "loaded", None: "unknown"}
SECONDS_PER_UNIT = {"ns": 1e-9, "us": 1e-6, "ms": 1e-3, "s": 1.0}


def select_attempts(attempts):
    """per parameter set, the attempt to publish: the quiet one, or the least loaded one; failed attempts never"""
    chosen = {}
    counts = {}
    for a in attempts:
        key = (a["benchmark"], tuple(sorted(a["params"].items())))
        counts[key] = counts.get(key, 0) + 1
        if a["exit_code"] != 0:
            continue
        best = chosen.get(key)
        if best is None or rank(a) < rank(best):
            chosen[key] = a
    return [(a, counts[key]) for key, a in chosen.items()]


def rank(attempt):
    """quiet first, then by the other workloads' load"""
    quiet = {True: 0, None: 1, False: 2}[attempt["quiet"]]
    return quiet, attempt["others_cpus"] if attempt["others_cpus"] is not None else math.inf


def series(directory, run_type):
    """([(name, {label: value}, value)], {(benchmark, params): slot}) for the results in a directory written by
    run_benchmarks.py"""
    info = read_properties(os.path.join(directory, "run-info.properties"))
    env = read_environment(os.path.join(directory, "environment-before.txt"))
    result = []
    slots = {}
    for attempt, count in select_attempts(read_run_index(directory)):
        quiet = attempt["quiet"] is True
        with open(os.path.join(directory, attempt["json"])) as f:
            benchmarks = json.load(f)
        for b in benchmarks:
            labels = {
                "benchmark": ".".join(b["benchmark"].split(".")[-2:]),
                "params": ",".join(f"{k}={v}" for k, v in sorted(b.get("params", {}).items())),
                "commit": info.get("commit", ""),
                "jdk": b.get("jdkVersion", ""),
                "runner": info.get("runner_label", ""),
                "node": env.get("boot_id", "")[:8],
                "run_type": run_type,
            }
            slots[(labels["benchmark"], labels["params"])] = SLOTS[attempt["quiet"]]
            metrics = b.get("secondaryMetrics", {})
            for metric, statistic in LATENCY.items():
                if quiet and metric in metrics:
                    unit = metrics[metric]["scoreUnit"]
                    if unit not in SECONDS_PER_UNIT:
                        raise SystemExit(f"unexpected unit {unit} of {metric} in {attempt['json']}")
                    result.append(("jmh_latency_seconds", {**labels, "statistic": statistic},
                                   metrics[metric]["score"] * SECONDS_PER_UNIT[unit]))
            published = {**LOAD_INDEPENDENT, **(LOAD_DEPENDENT if quiet else {})}
            for metric, name in published.items():
                if metric in metrics:
                    result.append((name, labels, float(metrics[metric]["score"])))
            if attempt["others_cpus"] is not None:
                result.append(("jmh_other_load_cpus", labels, attempt["others_cpus"]))
            result.append(("jmh_attempts", labels, float(count)))
    return [(name, labels, value) for name, labels, value in result if not math.isnan(value)], slots


# Prometheus remote write 1.0: a snappy-compressed protobuf WriteRequest.
#   message WriteRequest { repeated TimeSeries timeseries = 1; }
#   message TimeSeries { repeated Label labels = 1; repeated Sample samples = 2; }
#   message Label { string name = 1; string value = 2; }
#   message Sample { double value = 1; int64 timestamp = 2; }

def varint(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number, wire_type, payload):
    """a length-delimited (2), 64-bit (1) or varint (0) field"""
    tag = varint(number << 3 | wire_type)
    return tag + (varint(len(payload)) + payload if wire_type == 2 else payload)


def write_request(samples, timestamp_ms):
    body = bytearray()
    for name, labels, value in samples:
        all_labels = sorted({"__name__": name, **labels}.items())
        ts = b"".join(field(1, 2, field(1, 2, k.encode()) + field(2, 2, v.encode())) for k, v in all_labels)
        sample = field(1, 1, struct.pack("<d", value)) + field(2, 0, varint(timestamp_ms))
        ts += field(2, 2, sample)
        body += field(1, 2, ts)
    return bytes(body)


def snappy_literal(data):
    """snappy block format with a single literal element; valid, uncompressed"""
    n = len(data) - 1
    if n < 60:
        tag = bytes([n << 2])
    else:
        length = n.to_bytes((n.bit_length() + 7) // 8, "little")
        tag = bytes([(59 + len(length)) << 2]) + length
    return varint(len(data)) + tag + data


def post(url, data, headers, user, password):
    request = urllib.request.Request(url, data=data, method="POST")
    for name, value in headers.items():
        request.add_header(name, value)
    request.add_header("User-Agent", "hiero-benchmark-publisher")
    if user:
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.status


def push(url, samples, timestamp_ms, user, password):
    return post(url, snappy_literal(write_request(samples, timestamp_ms)),
                {"Content-Encoding": "snappy", "Content-Type": "application/x-protobuf",
                 "X-Prometheus-Remote-Write-Version": "0.1.0"}, user, password)


def run_records(samples, slots, run_url):
    """one Loki stream per published result: (labels, JSON line)"""
    results = {}
    for name, labels, value in samples:
        key = (labels["benchmark"], labels["params"])
        record = results.setdefault(key, {"labels": labels, "values": {}})
        statistic = labels.get("statistic")
        record["values"][f"{name}{'_' + statistic if statistic else ''}"] = value
    records = []
    for record in results.values():
        labels = record["labels"]
        values = record["values"]
        line = {
            "commit": labels["commit"], "jdk": labels["jdk"], "runner": labels["runner"], "node": labels["node"],
            "run_url": run_url, "slot": slots[(labels["benchmark"], labels["params"])],
            "latency_mean_ns": round(values.get("jmh_latency_seconds_mean", math.nan) * 1e9, 1),
            "latency_p99_ns": round(values.get("jmh_latency_seconds_p99", math.nan) * 1e9, 1),
            "allocation_bytes_per_op": round(values.get("jmh_allocation_bytes_per_op", math.nan), 1),
            "other_load_cpus": round(values.get("jmh_other_load_cpus", math.nan), 2),
            "attempts": int(values.get("jmh_attempts", 0)),
        }
        stream = {"job": "jmh-benchmarks", "benchmark": labels["benchmark"], "params": labels["params"],
                  "run_type": labels["run_type"]}
        records.append((stream, json.dumps({k: (None if isinstance(v, float) and math.isnan(v) else v)
                                            for k, v in line.items()})))
    return records


def push_loki(url, records, timestamp_ms, user, password):
    body = {"streams": [{"stream": stream, "values": [[str(timestamp_ms * 1_000_000), line]]}
                        for stream, line in records]}
    return post(url, json.dumps(body).encode(), {"Content-Type": "application/json"}, user, password)


def exposition(samples, timestamp_ms):
    """Prometheus text format, for --dry-run"""
    lines = []
    for name, labels, value in samples:
        text = ",".join(f'{k}="{v}"' for k, v in sorted(labels.items()))
        lines.append(f"{name}{{{text}}} {value!r} {timestamp_ms}")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", required=True, help="results directory of run_benchmarks.py")
    parser.add_argument("--run-type", default="nightly", help="value of the run_type label (default nightly)")
    parser.add_argument("--url", help="remote-write URL, for example https://mimir.example/api/v1/push")
    parser.add_argument("--loki-url", help="Loki push URL for the run records, for example"
                                           " https://loki.example/loki/api/v1/push")
    parser.add_argument("--time", type=float, help="sample time in seconds since the epoch (default: now); the"
                                                   " receiver rejects times outside its out-of-order window")
    parser.add_argument("--dry-run", action="store_true", help="print the samples instead of pushing them")
    args = parser.parse_args()

    samples, slots = series(args.results, args.run_type)
    if not samples:
        sys.exit(f"no results to publish in {args.results}")
    timestamp_ms = int((args.time if args.time is not None else time.time()) * 1000)
    run_url = read_properties(os.path.join(args.results, "run-info.properties")).get("run_url", "")
    records = run_records(samples, slots, run_url)
    print(exposition(samples, timestamp_ms))
    for stream, line in records:
        print(f"{json.dumps(stream, sort_keys=True)} {line}")
    if args.dry_run:
        return 0
    if not args.url:
        sys.exit("--url is required unless --dry-run is given")
    user = os.environ.get("BENCHMARK_METRICS_USER")
    password = os.environ.get("BENCHMARK_METRICS_PASSWORD", "")
    status = push(args.url, samples, timestamp_ms, user, password)
    print(f"pushed {len(samples)} samples: HTTP {status}")
    if args.loki_url:
        status = push_loki(args.loki_url, records, timestamp_ms, user, password)
        print(f"pushed {len(records)} run records: HTTP {status}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
