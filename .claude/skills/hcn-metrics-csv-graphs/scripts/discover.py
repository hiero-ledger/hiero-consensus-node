#!/usr/bin/env python3
"""Find consensus node metric CSVs under a folder tree and map them to node ids.

Files are recognised by content (first line starts with 'filename:'), not by name.
Looks inside .gz, .zip, .tar.gz/.tgz/.tar. The node id comes from the memberID column,
falling back to the last number in the file name.

Usage: discover.py ROOT [--out OUT_DIR]      (OUT_DIR defaults to ROOT/metrics-graphs)
Writes OUT_DIR/nodes.json and prints a node -> file table.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statscsv as sc  # noqa: E402


def candidates(root, out_dir):
    out_abs = os.path.abspath(out_dir)
    for dirpath, dirnames, filenames in os.walk(root):
        here = os.path.abspath(dirpath)
        if here == out_abs or here.startswith(out_abs + os.sep):
            dirnames[:] = []
            continue
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        for fn in sorted(filenames):
            p = os.path.join(dirpath, fn)
            low = fn.lower()
            if low.endswith(".csv") or low.endswith(".csv.gz"):
                yield p
            elif sc.is_archive(p):
                for m in sc.archive_members(p):
                    yield p + sc.ARCHIVE_SEP + m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--out")
    args = ap.parse_args()
    root = os.path.abspath(os.path.expanduser(args.root))
    out = os.path.abspath(os.path.expanduser(args.out or os.path.join(root, "metrics-graphs")))

    found = []
    for src in candidates(root, out):
        if not sc.looks_like_stats(src):
            continue
        member = sc.read_member_id(src)
        guess = sc.guess_node_from_name(src)
        node = member if member is not None else guess
        found.append({"source": src, "node": node, "member_id": member, "name_guess": guess})

    if not found:
        print("No metric CSVs found under %s" % root)
        sys.exit(1)

    by_node = {}
    for e in found:
        by_node.setdefault(e["node"], []).append(e)
    problems = []
    for node, es in by_node.items():
        if node is None:
            problems.append("no node id (ignored until one is set in nodes.json): " + ", ".join(e["source"] for e in es))
            for e in es:
                e["ignored"] = True
        elif len(es) > 1:
            problems.append("node %s has %d files: %s" % (node, len(es), ", ".join(e["source"] for e in es)))
            for e in es[1:]:
                e["ignored"] = True  # keep the first; the user decides which one to use
        for e in es:
            if e["member_id"] is not None and e["name_guess"] is not None and e["member_id"] != e["name_guess"]:
                problems.append("memberID %s differs from file name number %s: %s"
                                % (e["member_id"], e["name_guess"], e["source"]))

    os.makedirs(out, exist_ok=True)
    doc = {"root": root, "out": out, "nodes": sorted(found, key=lambda e: (e["node"] is None, e["node"] or 0))}
    with open(os.path.join(out, "nodes.json"), "w") as f:
        json.dump(doc, f, indent=1)

    print("root: %s\nout:  %s\n" % (root, out))
    print("%-5s %-8s %s" % ("node", "id-from", "file"))
    for e in doc["nodes"]:
        how = "memberID" if e["member_id"] is not None else ("filename" if e["node"] is not None else "-")
        flag = ""
        if e.get("ignored"):
            flag = "  (ignored - no node id)" if e["node"] is None else "  (ignored - duplicate)"
        print("%-5s %-8s %s%s" % (e["node"], how, os.path.relpath(e["source"], root), flag))
    if problems:
        print("\nCHECK WITH USER:")
        for p in problems:
            print("  - " + p)
        print("Edit 'ignored' (and 'node' where missing) in nodes.json to choose which file is used for a node.")


if __name__ == "__main__":
    main()
