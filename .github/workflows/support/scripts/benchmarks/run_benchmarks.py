#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Runs JMH benchmarks from a benchmark jar on a node shared with other workloads, and keeps a result only if it was
measured in a quiet slot.

Usage: run_benchmarks.py --jar JAR --benchmark PATTERN --out DIR [options] [-- JMH arguments]

Every parameter set of every matching benchmark runs in its own JMH invocation, with "-foe true", so that a loaded
parameter set can be repeated alone. While the benchmarks run, record-benchmark-environment.sh --monitor records the
host's CPU counters. After each invocation, the mean CPU use of other workloads over its measurement iterations decides
whether the slot was quiet; a loaded parameter set is repeated, up to --attempts times. Before each invocation, the
driver waits up to --wait-quiet seconds for a quiet minute, so that a repeat does not start into the same load.

Writes to DIR:
- runs.json: one entry per invocation (benchmark, parameters, attempt, files, times, other workloads' CPUs, quiet);
  rewritten after every invocation, so that a timeout leaves the attempts so far;
- <id>.json and <id>.txt: JMH's JSON results and its output, every line prefixed with its time;
- monitor.csv: the monitor's samples.

Exits with 1 if a JMH invocation failed. A loaded result is not a failure: it is kept and marked as not quiet.
"""
import argparse
import itertools
import json
import os
import re
import signal
import subprocess
import sys
import time

from benchmark_runs import RUN_INDEX, measurement_windows, monitor_mean, read_monitor

MONITOR_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "record-benchmark-environment.sh")


def list_parameter_sets(java, jar, pattern):
    """[(benchmark, {parameter: value})] for every benchmark matching the pattern and every combination of the values
    of its parameters, from JMH's -lp listing"""
    listing = subprocess.run([java, "-jar", jar, "-lp", pattern], capture_output=True, text=True, check=True).stdout
    benchmarks = []
    for line in listing.splitlines():
        m = re.match(r'\s+param "([^"]+)" = \{(.*)}$', line)
        if m and benchmarks:
            benchmarks[-1][1][m.group(1)] = [v.strip() for v in m.group(2).split(",")]
        elif line and not line.startswith((" ", "Benchmarks:")):
            benchmarks.append((line.strip(), {}))
    sets = []
    for benchmark, params in benchmarks:
        names = sorted(params)
        for values in itertools.product(*(params[n] for n in names)):
            sets.append((benchmark, dict(zip(names, values))))
    return sets


def wait_for_quiet(monitor_path, max_load, timeout, interval):
    """waits until the other workloads used less than max_load CPUs over a whole minute, at most timeout seconds;
    returns their CPUs over the last minute, or None on a machine without monitor data"""
    start = time.time()
    while True:
        now = time.time()
        intervals = read_monitor(monitor_path)
        if not intervals and now - start > 3 * interval + 5:
            return None
        load = monitor_mean(intervals, "others", [(now - 60, now)])
        covered = bool(intervals) and intervals[0]["start"] <= now - 60
        if (covered and load < max_load) or now - start >= timeout:
            return load
        time.sleep(min(15.0, interval))


def run_jmh(command, output_path):
    """runs JMH, copies its output to stdout and to a file with each line's time, and returns its exit code"""
    with open(output_path, "w") as out:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        for line in process.stdout:
            out.write(f"{time.time():.6f} {line}")
            out.flush()
            sys.stdout.write(line)
            sys.stdout.flush()
        return process.wait()


def file_id(index, benchmark, params, attempt):
    text = f"{index:02d}-{benchmark.split('.')[-2]}.{benchmark.split('.')[-1]}-" \
           + "-".join(f"{k}{v}" for k, v in sorted(params.items())) + f"-{attempt}"
    return re.sub(r"[^A-Za-z0-9._-]", "_", text)


def summary(attempts):
    lines = ["| Benchmark | Parameters | Attempt | Exit | Other workloads (CPUs) | Quiet |",
             "|---|---|---|---|---|---|"]
    for a in attempts:
        others = "-" if a["others_cpus"] is None else f"{a['others_cpus']:.2f}"
        quiet = {True: "yes", False: "no", None: "unknown"}[a["quiet"]]
        params = ", ".join(f"{k}={v}" for k, v in sorted(a["params"].items()))
        lines.append(f"| {a['benchmark'].split('.')[-2]}.{a['benchmark'].split('.')[-1]} | {params} | {a['attempt']}"
                     f" | {a['exit_code']} | {others} | {quiet} |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", required=True, help="the benchmark jar")
    parser.add_argument("--benchmark", required=True, help="JMH include pattern of the benchmarks to run")
    parser.add_argument("--out", required=True, help="results directory")
    parser.add_argument("--profiler", action="append", default=[], help="JMH profiler, repeatable")
    parser.add_argument("--param", action="append", default=[],
                        help="only the parameter sets with this name=value, repeatable")
    parser.add_argument("--max-load", type=float, default=0.5,
                        help="most CPUs of other workloads for a quiet slot (default 0.5; on the benchmark runner,"
                             " latency rises about 1%% per CPU of other workloads)")
    parser.add_argument("--attempts", type=int, default=3, help="most invocations per parameter set (default 3)")
    parser.add_argument("--wait-quiet", type=float, default=600,
                        help="seconds to wait for a quiet minute before each invocation (default 600)")
    parser.add_argument("--monitor-interval", type=float, default=5, help="seconds between monitor samples")
    parser.add_argument("--java", default=os.path.join(os.environ["JAVA_HOME"], "bin", "java")
                        if "JAVA_HOME" in os.environ else "java", help="the java command (default: JAVA_HOME's)")
    parser.add_argument("jmh_args", nargs=argparse.REMAINDER, help="further JMH arguments, after --")
    args = parser.parse_args()
    jmh_args = args.jmh_args[1:] if args.jmh_args[:1] == ["--"] else args.jmh_args
    only = dict(p.split("=", 1) for p in args.param)

    os.makedirs(args.out, exist_ok=True)
    sets = [(b, p) for b, p in list_parameter_sets(args.java, args.jar, args.benchmark)
            if all(p.get(k) == v for k, v in only.items())]
    if not sets:
        sys.exit(f"no benchmark matches {args.benchmark} {only}")
    monitor_path = os.path.join(args.out, "monitor.csv")
    monitor = subprocess.Popen(["bash", MONITOR_SCRIPT, "--monitor", str(args.monitor_interval)],
                               stdout=open(monitor_path, "w"), stderr=subprocess.STDOUT, start_new_session=True)
    attempts = []
    failed = False
    try:
        for index, (benchmark, params) in enumerate(sets, 1):
            for attempt in range(1, args.attempts + 1):
                waited_load = wait_for_quiet(monitor_path, args.max_load, args.wait_quiet, args.monitor_interval)
                name = file_id(index, benchmark, params, attempt)
                command = [args.java, "-jar", args.jar, "^" + re.escape(benchmark) + "$", "-foe", "true"]
                for k, v in sorted(params.items()):
                    command += ["-p", f"{k}={v}"]
                for p in args.profiler:
                    command += ["-prof", p]
                command += jmh_args + ["-rf", "json", "-rff", os.path.join(args.out, name + ".json")]
                print(f"== {benchmark} {params}, attempt {attempt}: {' '.join(command)}", flush=True)
                started = time.time()
                exit_code = run_jmh(command, os.path.join(args.out, name + ".txt"))
                finished = time.time()
                # one more monitor sample, so that the last iteration is covered
                time.sleep(args.monitor_interval + 1)
                windows = measurement_windows(os.path.join(args.out, name + ".txt"))
                others = monitor_mean(read_monitor(monitor_path), "others", windows)
                quiet = None if others is None or exit_code != 0 else others < args.max_load
                attempts.append({"id": name, "benchmark": benchmark, "params": params, "attempt": attempt,
                                 "json": name + ".json", "output": name + ".txt", "started": started,
                                 "finished": finished, "exit_code": exit_code, "others_before_cpus": waited_load,
                                 "others_cpus": others, "max_load": args.max_load, "quiet": quiet})
                with open(os.path.join(args.out, RUN_INDEX), "w") as f:
                    json.dump({"jmh_args": jmh_args, "profilers": args.profiler, "attempts": attempts}, f, indent=2)
                print(f"== {benchmark} {params}, attempt {attempt}: exit {exit_code}, other workloads "
                      f"{'-' if others is None else f'{others:.2f}'} CPUs, quiet: {quiet}", flush=True)
                if exit_code != 0:
                    failed = True
                    break
                if quiet is not False:
                    break
    finally:
        os.killpg(monitor.pid, signal.SIGTERM)
        monitor.wait()
    text = summary(attempts)
    print(text)
    if "GITHUB_STEP_SUMMARY" in os.environ:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write("### Benchmark runs\n\n" + text)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
