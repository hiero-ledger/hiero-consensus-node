#!/usr/bin/env python3
"""Parse each node's CSV once into OUT_DIR/.cache (column-major .npy + metadata).

Re-running is cheap: a node is re-parsed only if its source file changed. Cache files that no longer
match any file in nodes.json (e.g. after a source file was replaced) are removed.

Usage: prepare.py OUT_DIR [--nodes 0,3] [--jobs N]
"""

import argparse
import os
import sys
import time
from concurrent.futures import ProcessPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statscsv as sc  # noqa: E402


def _build(args):
    source, cdir, node = args
    t = time.time()
    meta = sc.build_cache(source, cdir, node)
    return node, meta["rows"], meta["skipped_rows"], time.time() - t


def remove_stale(cdir, doc):
    if not os.path.isdir(cdir):
        return
    keep = set()
    for e in doc["nodes"]:
        try:
            keep.add(sc.cache_key(e["source"]))
        except OSError:
            pass  # source gone; its cache files are stale too
    removed = 0
    for fn in os.listdir(cdir):
        key = fn.split(".", 1)[0]
        if key not in keep and (fn.endswith(".meta.json") or fn.endswith(".npy")):
            os.remove(os.path.join(cdir, fn))
            removed += 1
    if removed:
        print("removed %d stale cache file(s)" % removed)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--nodes", default="all")
    ap.add_argument("--jobs", type=int, default=0)
    args = ap.parse_args()
    out = os.path.abspath(os.path.expanduser(args.out))
    cdir = sc.cache_dir_for(out)
    doc = sc.load_nodes(out)
    entries = sc.selected_nodes(doc, args.nodes)
    remove_stale(cdir, doc)
    todo = [(e["source"], cdir, e["node"]) for e in entries if not sc.is_cached(cdir, e["source"])]
    for e in entries:
        if sc.is_cached(cdir, e["source"]):
            print("node %s: cached" % e["node"])
    if not todo:
        return
    jobs = args.jobs or min(len(todo), max(1, (os.cpu_count() or 2) // 2))
    print("parsing %d file(s) with %d worker(s)..." % (len(todo), jobs), flush=True)
    with ProcessPoolExecutor(max_workers=jobs) as ex:
        for node, rows, skipped, secs in ex.map(_build, todo):
            extra = " (%d non-data rows skipped)" % skipped if skipped else ""
            print("node %s: %d rows in %.0fs%s" % (node, rows, secs, extra), flush=True)


if __name__ == "__main__":
    main()
