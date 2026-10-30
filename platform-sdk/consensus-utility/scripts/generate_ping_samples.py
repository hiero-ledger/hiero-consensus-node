#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Regenerates the RealisticPingSamples.MAINNET field declaration from a CSV export of ping times (in microseconds).

Expected CSV columns: Time, dest_node, node_id, Value
The result is indexed by [sender][receiver]: sender = node_id, receiver = dest_node.

Only the field declaration is printed to stdout; warnings are printed to stdout as well, as '// WARNING: ...' comment
lines placed before the declaration, so the output can be pasted as is.

Usage: generate_ping_samples.py export.csv
"""
import argparse
import csv
import math
import sys
import textwrap

BROKEN = "BROKEN"
MAX_LINE = 120
ASYMMETRY_LIMIT = 0.20


def load(path):
    """Returns {(src, dst): microseconds}. If a pair appears several times (several timestamps), the last one wins."""
    pings = {}
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            src, dst = int(row["node_id"]), int(row["dest_node"])
            pings[(src, dst)] = float(row["Value"])
    return pings


def ms(micros):
    return math.ceil(micros / 1000)


def build(pings, warnings):
    ids = sorted({n for pair in pings for n in pair})
    index = {node: i for i, node in enumerate(ids)}
    size = len(ids)
    matrix = [[None] * size for _ in range(size)]

    for (src, dst), micros in pings.items():
        if src == dst:
            if micros != 0:
                warnings.append(f"node {src} pings itself with {micros} us, forced to 0")
            continue
        matrix[index[src]][index[dst]] = micros

    for x in range(size):
        matrix[x][x] = 0

    # fill completely missing reverse values
    for x in range(size):
        for y in range(size):
            if x != y and matrix[x][y] is None and matrix[y][x] is not None:
                matrix[x][y] = matrix[y][x]
                warnings.append(f"ping {ids[x]} -> {ids[y]} missing, copied from {ids[y]} -> {ids[x]}")

    # asymmetry check
    for x in range(size):
        for y in range(x + 1, size):
            a, b = matrix[x][y], matrix[y][x]
            if a is None or b is None or a == 0 or b == 0:
                continue
            if abs(a - b) > ASYMMETRY_LIMIT * max(a, b):
                warnings.append(
                    f"ping {ids[x]} -> {ids[y]} ({ms(a)} ms) and {ids[y]} -> {ids[x]} ({ms(b)} ms) differ by more than "
                    f"{int(ASYMMETRY_LIMIT * 100)}%"
                )

    result = []
    for x in range(size):
        row = []
        for y in range(size):
            micros = matrix[x][y]
            if x == y:
                row.append("0")
            elif micros is None:
                warnings.append(f"ping {ids[x]} -> {ids[y]} missing in both directions, using {BROKEN}")
                row.append(BROKEN)
            elif micros == 0:
                row.append(BROKEN)
            else:
                row.append(str(ms(micros)))
        result.append(row)
    return ids, result


def render(rows):
    out = ["    public static final int[][] MAINNET = new int[][] {"]
    for i, row in enumerate(rows):
        body = textwrap.fill(
            ", ".join(row), width=MAX_LINE, initial_indent=" " * 12, subsequent_indent=" " * 12, break_on_hyphens=False
        )
        out.append("        {")
        out.append(body)
        out.append("        }" + ("," if i < len(rows) - 1 else ""))
    out.append("    };")
    return "\n".join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv_file")
    args = parser.parse_args()

    warnings = []
    ids, rows = build(load(args.csv_file), warnings)
    for w in warnings:
        print(f"// WARNING: {w}")
    print(f"// node ids (row/column order): {ids}")
    print(render(rows))


if __name__ == "__main__":
    sys.exit(main())
