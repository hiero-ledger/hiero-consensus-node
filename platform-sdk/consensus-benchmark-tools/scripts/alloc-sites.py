#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Shows which code allocates how much memory per benchmark call, side by side for several JMH runs.

What it is for: while a program runs, the JVM's JIT compiler turns frequently called methods into optimized machine
code. Some of its decisions depend on timing, so two runs of the same benchmark can end up with differently optimized
code and run at different speeds. Memory allocation often shows the difference. One optimization, escape analysis,
removes the allocation of an object that never leaves the compiled code, such as an iterator in a loop, so a run with
the optimization allocates fewer bytes per call than a run without it. JMH reports the bytes allocated per call as
alloc.norm. When the runs of a benchmark fall into groups with different alloc.norm values, this script finds the
code whose allocation differs between the groups. inline-tree.py then shows why the JIT compiler treated that code
differently. See docs/consensus-layer/benchmarks/calibration.md, section "Fork modes".

Usage: alloc-sites.py [--depth N] [--width N] [--min B] <benchmark method> <run>...

Each run is the output directory of one JMH run with a single fork (-f 1: one JVM, so one set of JIT decisions) and
one parameter set. It holds the results.json that JMH wrote and, below it, the collapsed-alloc.csv that async-profiler
(https://github.com/async-profiler/async-profiler) wrote. In allocation mode, async-profiler records the call stack of
sampled allocations, weighted by their size. Add these arguments to the JMH command line:

    -f 1 -rf json -rff <run>/results.json
    -prof "async:libPath=<lib>;output=collapsed;dir=<run>;event=alloc;interval=16384;rawCommand=total"

The output has one column per run, in the order of the command line, and one row per allocation site: the allocated
class and the methods that allocated it. Each value is in bytes per benchmark call; the first row is each run's
alloc.norm. List the runs grouped by their
alloc.norm value; the row whose values jump between the groups is the code that differs. Only allocations under the
benchmark method (for example ConsensusImplBenchmark.calculateConsensus) count, so the benchmark's setup is left out.

A site is the allocated class plus the N methods closest to the allocation (--depth, default 1). A class allocated in
several places, such as an iterator, shares one row at depth 1; --depth 3 separates its callers. Each site's share of
the profiled bytes is multiplied by the run's last alloc.norm value, which turns it into bytes per call.
"""
import argparse
import glob
import json
import os
from collections import defaultdict

parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
parser.add_argument("--depth", type=int, default=1, help="frames above the allocated class (default 1)")
parser.add_argument("--width", type=int, default=220, help="maximum width of a site (default 220)")
parser.add_argument("--min", type=float, default=5.0, help="hide sites below this many B/op in every run")
parser.add_argument("method", help="the benchmark method, as Class.method")
parser.add_argument("runs", nargs="+")
args = parser.parse_args()

columns = []
for run in args.runs:
    collapsed = glob.glob(os.path.join(run, "**", "collapsed-alloc.csv"), recursive=True)[0]
    norm = json.load(open(os.path.join(run, "results.json")))[0]["secondaryMetrics"]["alloc.norm"]["rawData"][0][-1]
    sites = defaultdict(int)
    for line in open(collapsed):
        stack, count = line.rstrip().rsplit(" ", 1)
        frames = stack.split(";")
        if not any(frame.replace("/", ".").endswith(args.method) for frame in frames):
            continue
        sites[" <- ".join(reversed(frames[-1 - args.depth:]))] += int(count)
    total = sum(sites.values())
    columns.append((os.path.basename(run.rstrip("/")), norm, {k: v / total * norm for k, v in sites.items()}))

keys = sorted({k for _, _, s in columns for k in s}, key=lambda k: -max(s.get(k, 0) for _, _, s in columns))
print("".join(f"{name[-10:]:>11}" for name, _, _ in columns))
print("".join(f"{norm:>11.1f}" for _, norm, _ in columns) + "   alloc.norm (B/op)")
for key in keys:
    values = [s.get(key, 0.0) for _, _, s in columns]
    if max(values) >= args.min:
        print("".join(f"{v:>11.1f}" for v in values), " ", key[:args.width])
