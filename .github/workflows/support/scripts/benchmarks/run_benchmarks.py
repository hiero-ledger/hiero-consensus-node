#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Runs JMH benchmarks from a benchmark jar on a node shared with other workloads, and keeps a result only if it was
measured in a quiet slot.

Usage: run_benchmarks.py --manifest FILE... --out DIR [options] [-- JMH arguments]
       run_benchmarks.py --jar JAR --benchmark PATTERN --out DIR [options] [-- JMH arguments]

The benchmarks come either from the manifests that the Gradle task jmhTracked writes for every module that tracks
benchmarks (build/jmh/tracked.properties: module, jar, includes, profilers), which is how the nightly job finds them, or
from one jar and one include pattern, which is how a calibration run names its benchmark. They run in the order given,
and within a manifest in the order of its includes.

Every parameter set of every matching benchmark runs in its own JMH invocation, with "-foe true", so that a loaded
parameter set can be repeated alone. While the benchmarks run, record-benchmark-environment.sh --monitor records the
host's CPU counters. Before each invocation, the driver waits for a quiet minute, one in which other workloads used less
than --max-load CPUs; after it, their mean CPU use over the measurement iterations decides whether the slot was quiet,
and a loaded parameter set is repeated, up to --attempts times. The node is shared, and the node's use decides when a
benchmark measures, not its reservations: the driver waits as long as it takes, up to its deadline (--deadline seconds
after its start; --wait-quiet caps a single wait). A parameter set still open at the deadline runs once without
waiting, loaded or not, so that every set has at least its allocation measured before the job's timeout ends the run.

The forked JVMs assume a fixed number of processors (--active-processors, passed as -XX:ActiveProcessorCount through
-jvmArgsPrepend), so that their GC and compiler thread counts do not follow the size of the runner's pod. A benchmark
must therefore not set jvmArgsPrepend itself; its heap belongs in jvmArgsAppend.

Writes to DIR:
- runs.json: one entry per invocation (module, jar, benchmark, parameters, attempt, files, seconds waited, times, other
  workloads' CPUs, quiet, whether the deadline had passed); rewritten after every invocation, so that a timeout leaves
  the attempts so far;
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

from benchmark_runs import RUN_INDEX, measurement_windows, monitor_mean, read_monitor, read_properties

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


def read_manifest(path):
    """(module, jar, [include pattern], [profiler]) from the tracked.properties a module's jmhTracked task writes"""
    props = read_properties(path)
    missing = [key for key in ("module", "jar", "includes") if not props.get(key)]
    if missing:
        sys.exit(f"{path}: not a manifest of jmhTracked, missing {', '.join(missing)}")
    return (props["module"], props["jar"], [p for p in props["includes"].split(",") if p],
            [p for p in props.get("profilers", "").split(",") if p])


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


def summary(attempts, deadline):
    lines = ["| Benchmark | Parameters | Attempt | Exit | Waited (min) | Run (min) | Other workloads (CPUs) | Quiet |",
             "|---|---|---|---|---|---|---|---|"]
    for a in attempts:
        others = "-" if a["others_cpus"] is None else f"{a['others_cpus']:.2f}"
        quiet = {True: "yes", False: "no", None: "unknown"}[a["quiet"]]
        params = ", ".join(f"{k}={v}" for k, v in sorted(a["params"].items()))
        lines.append(f"| {a['benchmark'].split('.')[-2]}.{a['benchmark'].split('.')[-1]} | {params} | {a['attempt']}"
                     f" | {a['exit_code']} | {a['waited'] / 60:.1f} | {(a['finished'] - a['started']) / 60:.1f}"
                     f" | {others} | {quiet} |")
    total = sum(a["finished"] - a["started"] for a in attempts) / 60
    waited = sum(a["waited"] for a in attempts) / 60
    sets = len({(a["benchmark"], tuple(sorted(a["params"].items()))) for a in attempts})
    lines.append(f"\n{len(attempts)} invocation(s) for {sets} parameter set(s), {total:.1f} minutes of benchmark time"
                 f" after {waited:.1f} minutes of waiting for quiet.")
    late = sum(1 for a in attempts if a["after_deadline"])
    if late:
        lines.append(f"The deadline of {deadline / 60:.0f} minutes passed: {late} invocation(s) ran without waiting and"
                     f" without repeats.")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", nargs="+", action="extend", default=[],
                        help="tracked.properties files of the modules, written by their jmhTracked task; instead of"
                             " --jar and --benchmark")
    parser.add_argument("--jar", help="the benchmark jar")
    parser.add_argument("--benchmark", help="JMH include pattern of the benchmarks to run")
    parser.add_argument("--out", required=True, help="results directory")
    parser.add_argument("--profiler", action="append", default=[],
                        help="JMH profiler, repeatable; added to the profilers of every manifest")
    parser.add_argument("--active-processors", type=int, default=12,
                        help="processors the forked JVMs assume, as -XX:ActiveProcessorCount (default 12; 0 leaves"
                             " it to the JVM, which then sizes its GC and compiler threads by the runner's pod)")
    parser.add_argument("--param", action="append", default=[],
                        help="only the parameter sets with this name=value, repeatable")
    parser.add_argument("--max-load", type=float, default=0.5,
                        help="most CPUs of other workloads for a quiet slot (default 0.5; on the benchmark runner,"
                             " latency rises about 1%% per CPU of other workloads)")
    parser.add_argument("--attempts", type=int, default=3, help="most invocations per parameter set (default 3)")
    parser.add_argument("--deadline", type=float, default=3 * 3600,
                        help="seconds after the start until which the driver waits for quiet minutes; a parameter set"
                             " still open at the deadline runs once without waiting (default 10800)")
    parser.add_argument("--wait-quiet", type=float, default=None,
                        help="longest single wait for a quiet minute, in seconds (default: until the deadline)")
    parser.add_argument("--monitor-interval", type=float, default=5, help="seconds between monitor samples")
    parser.add_argument("--java", default=os.path.join(os.environ["JAVA_HOME"], "bin", "java")
                        if "JAVA_HOME" in os.environ else "java", help="the java command (default: JAVA_HOME's)")
    parser.add_argument("jmh_args", nargs=argparse.REMAINDER, help="further JMH arguments, after --")
    args = parser.parse_args()
    jmh_args = args.jmh_args[1:] if args.jmh_args[:1] == ["--"] else args.jmh_args
    only = dict(p.split("=", 1) for p in args.param)

    if bool(args.manifest) == bool(args.jar or args.benchmark) or (args.jar is None) != (args.benchmark is None):
        parser.error("give either --manifest or both --jar and --benchmark")
    if args.manifest:
        jobs = [(module, jar, pattern, profilers + args.profiler) for path in args.manifest
                for module, jar, patterns, profilers in [read_manifest(path)] for pattern in patterns]
    else:
        jobs = [(None, args.jar, args.benchmark, args.profiler)]

    os.makedirs(args.out, exist_ok=True)
    sets = []
    for module, jar, pattern, profilers in jobs:
        matched = [(module, jar, profilers, b, p) for b, p in list_parameter_sets(args.java, jar, pattern)
                   if all(p.get(k) == v for k, v in only.items())]
        if not matched:
            sys.exit(f"no benchmark in {jar} matches {pattern} {only}")
        sets += matched
    monitor_path = os.path.join(args.out, "monitor.csv")
    monitor = subprocess.Popen(["bash", MONITOR_SCRIPT, "--monitor", str(args.monitor_interval)],
                               stdout=open(monitor_path, "w"), stderr=subprocess.STDOUT, start_new_session=True)
    deadline_at = time.time() + args.deadline
    attempts = []
    failed = False
    try:
        for index, (module, jar, profilers, benchmark, params) in enumerate(sets, 1):
            for attempt in range(1, args.attempts + 1):
                budget = max(0.0, deadline_at - time.time())
                if args.wait_quiet is not None:
                    budget = min(budget, args.wait_quiet)
                print(f"== {benchmark} {params}, attempt {attempt}: "
                      + (f"waiting up to {budget / 60:.1f} min for a quiet minute" if budget
                         else "the deadline has passed, no waiting"), flush=True)
                wait_started = time.time()
                waited_load = wait_for_quiet(monitor_path, args.max_load, budget, args.monitor_interval)
                waited = time.time() - wait_started
                after_deadline = time.time() >= deadline_at
                name = file_id(index, benchmark, params, attempt)
                command = [args.java, "-jar", jar, "^" + re.escape(benchmark) + "$", "-foe", "true"]
                if args.active_processors:
                    command += ["-jvmArgsPrepend", f"-XX:ActiveProcessorCount={args.active_processors}"]
                for k, v in sorted(params.items()):
                    command += ["-p", f"{k}={v}"]
                for p in profilers:
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
                attempts.append({"id": name, "module": module, "jar": jar, "benchmark": benchmark, "params": params,
                                 "attempt": attempt, "profilers": profilers, "json": name + ".json",
                                 "output": name + ".txt", "waited": waited, "started": started, "finished": finished,
                                 "exit_code": exit_code, "others_before_cpus": waited_load, "others_cpus": others,
                                 "max_load": args.max_load, "quiet": quiet, "after_deadline": after_deadline})
                with open(os.path.join(args.out, RUN_INDEX), "w") as f:
                    json.dump({"jmh_args": jmh_args, "active_processors": args.active_processors,
                               "deadline": args.deadline, "wait_quiet": args.wait_quiet, "attempts": attempts},
                              f, indent=2)
                print(f"== {benchmark} {params}, attempt {attempt}: exit {exit_code}, waited {waited / 60:.1f} min,"
                      f" other workloads {'-' if others is None else f'{others:.2f}'} CPUs, quiet: {quiet}", flush=True)
                if exit_code != 0:
                    failed = True
                    break
                # a loaded set is repeated, unless the deadline has passed: then one measurement per set, loaded or not
                if quiet is not False or after_deadline:
                    break
    finally:
        os.killpg(monitor.pid, signal.SIGTERM)
        monitor.wait()
    text = summary(attempts, args.deadline)
    print(text)
    if "GITHUB_STEP_SUMMARY" in os.environ:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write("### Benchmark runs\n\n" + text)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
