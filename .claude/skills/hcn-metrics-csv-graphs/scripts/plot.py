#!/usr/bin/env python3
"""Render selected metrics for selected nodes and time window(s) as interactive HTML reports.

One self-contained file per window, charts drawn with the vendored uPlot (scripts/vendor/uplot).
One line per node, title 'category: name' plus description. Key metrics (catalog.KEY_METRICS) come
first and are always charted. Metrics that are constant (or have no data) in the window are not
charted; they are listed by name at the end so a search still finds them. Data is full resolution,
so the size grows with charts x nodes x samples; above --max-mb nothing is written and the exit
code is 3.

Usage:
  plot.py OUT_DIR [--nodes 0,3] [--select FILE] [--name STEM] [--title TEXT]
          [--section 2,4 | --start 'YYYY-MM-DD HH:MM' --end 'HH:MM' | (whole run)]
          [--keep-flat] [--limit N] [--max-mb 100] [--force]
One file per window; written to OUT_DIR.
"""

import argparse
import json
import os
import sys
import warnings

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statscsv as sc  # noqa: E402
import catalog as cg  # noqa: E402
import report_html as rh  # noqa: E402


def windows_from_args(args, out, run_start):
    if args.section:
        p = os.path.join(out, "sections.json")
        if not os.path.exists(p):
            raise SystemExit("No sections.json - run sections.py first.")
        with open(p) as f:
            secs = {s["id"]: s for s in json.load(f)["sections"]}
        res = []
        for sid in [int(x) for x in args.section.split(",") if x.strip()]:
            if sid not in secs:
                raise SystemExit("No section %d in sections.json" % sid)
            s = secs[sid]
            res.append(("Section %d (%s)" % (sid, s["kind"]), s["start"], s["end"], "section%d" % sid))
        return res
    if args.start or args.end:
        try:
            a = sc.parse_user_time(args.start, run_start) if args.start else None
            b = sc.parse_user_time(args.end, a if a is not None else run_start) if args.end else None
        except ValueError as e:
            raise SystemExit("%s - use 'YYYY-MM-DD HH:MM[:SS]' or 'HH:MM[:SS]', in UTC" % e)
        if a is not None and b is not None and b <= a and ":" in args.end and "-" not in args.end:
            b += 86400  # time-only end earlier than the start: the window crosses midnight
        tag = "%s-%s" % (sc.fmt_time(a)[11:16].replace(":", "") if a else "start",
                         sc.fmt_time(b)[11:16].replace(":", "") if b else "end")
        return [("Custom window", a, b, tag)]
    return [("Whole run", None, None, "whole-run")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--nodes", default="all")
    ap.add_argument("--select", help="selection file (see catalog.py); default: all metrics")
    ap.add_argument("--section")
    ap.add_argument("--start")
    ap.add_argument("--end")
    ap.add_argument("--name", default="metrics")
    ap.add_argument("--title")
    ap.add_argument("--keep-flat", action="store_true", help="chart constant metrics too")
    ap.add_argument("--limit", type=int, help="only the first N selected metrics (for quick checks)")
    ap.add_argument("--max-mb", type=float, default=100.0,
                    help="refuse to write a report whose estimated size exceeds this (default 100)")
    ap.add_argument("--force", action="store_true", help="write even if over --max-mb")
    args = ap.parse_args()

    out = os.path.abspath(os.path.expanduser(args.out))
    nodes = sc.open_nodes(out, args.nodes)
    doc = sc.load_nodes(out)
    title = args.title or os.path.basename(doc["root"].rstrip("/"))
    metrics = cg.load_metrics(out, ",".join(str(n.node) for n in nodes))
    selectors = cg.read_selectors(args.select) if args.select else ["all"]
    chosen = cg.selected_metrics(selectors, metrics, out_dir=out)
    if args.limit:
        chosen = chosen[:args.limit]
    if not chosen:
        raise SystemExit("Selection matched no metrics.")
    key_metrics, key_missing = cg.key_metrics(metrics)
    if key_missing:
        print("key metric(s) not in these files: %s" % ", ".join(key_missing))
    key_ids = set(m.key for m in key_metrics)
    chosen_ids = set(m.key for m in chosen)
    chosen_fams = set((m.cat, m.topic, m.family) for m in chosen if m.family != m.name)
    # key metrics also sit with their family when the selection includes that family
    regular = [m for m in metrics if m.key in chosen_ids or
               (m.key in key_ids and (m.cat, m.topic, m.family) in chosen_fams)]
    run_start = min(np.nanmin(n.time) for n in nodes if n.time.size)
    run_end = max(np.nanmax(n.time) for n in nodes if n.time.size)

    too_large = []
    for label, ws, we, tag in windows_from_args(args, out, run_start):
        ws = run_start if ws is None else ws
        we = run_end if we is None else we
        if we <= ws:
            raise SystemExit("Empty window: %s -> %s" % (sc.fmt_time(ws), sc.fmt_time(we)))
        if we < run_start or ws > run_end:
            raise SystemExit("Window %s -> %s is outside the run (%s -> %s UTC)." % (
                sc.fmt_time(ws), sc.fmt_time(we), sc.fmt_time(run_start), sc.fmt_time(run_end)))
        masks = []
        node_times = []
        for n in nodes:
            m = np.isfinite(n.time) & (n.time >= ws) & (n.time <= we)
            masks.append(m)
            node_times.append(n.time[m])

        def series_for(met):
            series = []
            for n, m in zip(nodes, masks):
                v = n.column(met.key)
                if v is None:
                    continue
                y = v[m]
                if np.isfinite(y).any():
                    series.append((n.node, y))
            return series

        plotted, flat, nodata = [], [], []
        key_plotted = []  # always charted, even when constant
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            for met in key_metrics:
                series = series_for(met)
                if series:
                    key_plotted.append((met, series))
            for met in regular:
                series = series_for(met)
                if not series:
                    nodata.append(met)
                    continue
                lo = min(np.nanmin(y) for _, y in series)
                hi = max(np.nanmax(y) for _, y in series)
                if lo == hi and not args.keep_flat and met.key not in key_ids:
                    flat.append((met, lo))
                    continue
                plotted.append((met, series))

        in_regular = set(m.key for m, _ in plotted)
        est = rh.estimate_bytes(plotted + [kp for kp in key_plotted if kp[0].key not in in_regular], node_times)
        final = os.path.join(out, "%s-%s.html" % (args.name, tag))
        if est > args.max_mb * 1e6 and not args.force:
            samples = sum(len(y) for _, ser in plotted + key_plotted for _, y in ser)
            print("%s: TOO LARGE - estimated %.0f MB (%d charts, %d nodes, %d samples) > --max-mb %.0f. "
                  "Not written. Narrow the families, nodes or window, or pass --force."
                  % (label, est / 1e6, len(plotted), len(nodes), samples, args.max_mb))
            too_large.append(label)
            continue
        size = rh.render(final, title, label, ws, we, nodes, node_times, plotted, key_plotted, flat, nodata,
                         selectors, doc["root"])
        if abs(size - est) > 0.25 * size:  # surfaces estimator drift so it can be corrected
            print("  (size estimate was %.1f MB)" % (est / 1e6))
        print("%s: %d charts, %d constant, %d no data, %.1f MB -> %s" % (
            label, len(plotted), len(flat), len(nodata), size / 1e6, final))
    if too_large:
        sys.exit(3)


if __name__ == "__main__":
    main()
