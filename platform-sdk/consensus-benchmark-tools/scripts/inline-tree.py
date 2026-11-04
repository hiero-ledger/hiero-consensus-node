#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Shows how the JIT compiler compiled a method and which calls it inlined, to compare two JMH runs.

What it is for: while a program runs, the JVM's optimizing JIT compiler, C2, turns frequently called methods into
machine code. Its most important step is inlining: it copies the body of a called method into the caller, so that
both are optimized together. Later optimizations depend on it. Escape analysis, for example, removes the allocation
of an object only if every method the object is passed to is inlined. C2 limits how much it inlines into one method,
and some of its decisions depend on timing, so two runs of the same benchmark can compile the same method differently
and run at different speeds. When alloc-sites.py has found code that allocates in some runs but not in others, this
script shows why: it lists the compilations of a method and prints each one's inlining decisions with the compiler's
reasons. See docs/consensus-layer/benchmarks/calibration.md, section "Fork modes".

Usage:
  inline-tree.py <comp.log> list <method>     the C2 compilations of <method> and of the methods that inline it: when
                                              each started, when it was discarded, and the allocations it removed
  inline-tree.py <comp.log> tree <compile id> the inline tree of one compilation: every call, inlined (OK) or not (NO),
                                              with the callee's size in bytecode bytes and the compiler's reason

Methods are matched by their simple name, for example stronglySeeP.

To write the log, run the benchmark with a single fork (-f 1) and add the JVM arguments
-jvmArgsAppend "<the benchmark's heap settings> -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=<file>".
The log is XML. Prefer it to -XX:+PrintInlining, whose output the compiler threads interleave.

The JVM discards a compilation ("made not entrant") when an assumption the compiler made proves wrong, for example
when the code takes a branch that the compiler had assumed never to be taken (an "uncommon trap"), and compiles the
method again later. The list shows the first three such traps of each discarded compilation. The compilations that
are never discarded are the ones that run during most of the measurement: compare their trees between two runs to
find the first decision that differs.
"""
import re
import sys
from collections import defaultdict

ATTR = re.compile(r"(\w+)='([^']*)'")


def attrs(line):
    return dict(ATTR.findall(line))


def unescape(name):
    return name.replace("&lt;", "<").replace("&gt;", ">")


def tasks(log):
    """yields (task attributes, list of lines) for every compilation task"""
    current = None
    for line in open(log):
        if line.startswith("<task "):
            current = (attrs(line), [])
        elif current is not None:
            if line.startswith("</task>"):
                yield current
                current = None
            else:
                current[1].append(line)


def end_of_life(log):
    not_entrant, traps = {}, defaultdict(list)
    for line in open(log):
        if line.startswith("<make_not_entrant"):
            a = attrs(line)
            not_entrant.setdefault(a.get("compile_id"), a.get("stamp"))
        elif line.startswith("<uncommon_trap") and "thread=" in line:
            a = attrs(line)
            traps[a.get("compile_id")].append(f"{a.get('reason')}/{a.get('action')}@{a.get('stamp')}")
    return not_entrant, traps


def decisions(lines):
    """yields (depth, ok, callee, bytes, reason) and ("eliminate", class) items of one task"""
    klasses, methods, depth, pending = {}, {}, 0, None
    for line in lines:
        a = attrs(line)
        if line.startswith("<klass "):
            klasses[a["id"]] = a["name"].split(".")[-1]
        elif line.startswith("<method "):
            methods[a["id"]] = (klasses.get(a["holder"], "?") + "." + unescape(a["name"]), a.get("bytes"))
        elif line.startswith("<call "):
            pending = a["method"]
        elif line.startswith(("<inline_success", "<inline_fail", "<intrinsic")) and pending:
            name, size = methods.get(pending, (pending, "?"))
            yield depth, not line.startswith("<inline_fail"), name, size, a.get("reason", "intrinsic")
            pending = None
        elif line.startswith("<parse "):
            depth += 1
        elif line.startswith("</parse>"):
            depth -= 1
        elif line.startswith("<eliminate_allocation"):
            yield "eliminate", klasses.get(a["type"], a["type"])


def simple(method_attr):
    return method_attr.split(" (")[0].split(" ")[-1]


def main():
    log, command, arg = sys.argv[1:4]
    if command == "list":
        not_entrant, traps = end_of_life(log)
        for task, lines in tasks(log):
            if task.get("level", "4") != "4":
                continue
            items = list(decisions(lines))
            inlined = [i for i in items if i[0] != "eliminate" and i[1] and i[2].endswith("." + arg)]
            if simple(task["method"]) != arg and not inlined:
                continue
            eliminated = defaultdict(int)
            for i in items:
                if i[0] == "eliminate":
                    eliminated[i[1]] += 1
            cid = task["compile_id"]
            print(f"task {cid} {task['method'].split(' (')[0]}{' (OSR)' if task.get('compile_kind') == 'osr' else ''}"
                  f" start {task.get('stamp')} s, not entrant: {not_entrant.get(cid, 'never')} {traps.get(cid, [])[:3]}")
            print(f"   inlines {arg}: {len(inlined)}x; eliminated: "
                  + (", ".join(f"{k} x{v}" for k, v in sorted(eliminated.items())) or "none"))
    elif command == "tree":
        for task, lines in tasks(log):
            if task["compile_id"] == arg and task.get("level", "4") == "4":
                print(task["method"])
                for item in decisions(lines):
                    if item[0] == "eliminate":
                        print(f"eliminated allocation: {item[1]}")
                    else:
                        depth, ok, name, size, reason = item
                        print(f"{'  ' * depth}{'OK ' if ok else 'NO '}{name} ({size} bytes) [{reason}]")
    else:
        sys.exit(__doc__)


main()
