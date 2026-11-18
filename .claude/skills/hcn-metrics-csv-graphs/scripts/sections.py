#!/usr/bin/env python3
"""Split a run into load sections using changes in transaction throughput.

Signal: trans_per_sec (fallback transH_per_sec), median across the selected nodes,
binned and smoothed. Everything is RELATIVE to the run's own peak - no absolute
throughput thresholds exist here, by design:
  - idle   : unsmoothed signal below IDLE_FRAC of the run's peak for at least ~1 minute
             (the short drops to zero between loads are what separate most phases)
  - levels : within busy stretches, binary segmentation on the smoothed signal; a split is
             kept if both sides last at least the minimum section length (default
             max(5 min, 1% of the run); --min-section-min) and the side medians differ by
             LEVEL_CHANGE (relative), or by MIN_STEP and NOISE_SIGMAS x the local noise.
NaN-only bins produce numpy RuntimeWarnings ("All-NaN slice", "Mean of empty slice"); they are
expected and silenced for the duration of main().

Usage: sections.py OUT_DIR [--nodes 0,3] [--level-change 0.2] [--min-section-min M]
Writes OUT_DIR/sections.json and OUT_DIR/sections-overview.png, prints a table.
"""

import argparse
import json
import os
import sys
import warnings

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statscsv as sc  # noqa: E402

IDLE_FRAC = 0.02      # below 2% of the run's own peak = idle
LEVEL_CHANGE = 0.20   # always split on a relative level change this large
MIN_STEP = 0.08       # smaller steps split only if they also stand out from the noise...
NOISE_SIGMAS = 4.0    # ...by this many noise standard deviations
SIGNALS = ("trans_per_sec", "transH_per_sec")


def binned(nodes, metric, edges):
    """Per-bin mean per node, then median across nodes."""
    per_node = []
    for nd in nodes:
        v = nd.column(metric)
        if v is None:
            continue
        t = nd.time
        ok = np.isfinite(t) & np.isfinite(v)
        idx = np.searchsorted(edges, t[ok], side="right") - 1
        good = (idx >= 0) & (idx < len(edges) - 1)
        sums = np.bincount(idx[good], weights=v[ok][good], minlength=len(edges) - 1)
        cnt = np.bincount(idx[good], minlength=len(edges) - 1)
        with np.errstate(invalid="ignore", divide="ignore"):
            per_node.append(np.where(cnt > 0, sums / cnt, np.nan))
    if not per_node:
        return None
    return np.nanmedian(np.vstack(per_node), axis=0)


def rolling_median(x, w):
    if w <= 1:
        return x.copy()
    h = w // 2
    pad = np.concatenate([np.full(h, np.nan), x, np.full(w - 1 - h, np.nan)])
    win = np.lib.stride_tricks.sliding_window_view(pad, w)
    return np.nanmedian(win, axis=1)


def runs(mask):
    """[start, end) index pairs of consecutive True values."""
    out, i, n = [], 0, len(mask)
    while i < n:
        if mask[i]:
            j = i
            while j < n and mask[j]:
                j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out


def noise(raw):
    """Robust std of the bin-to-bin noise (MAD of first differences)."""
    d = np.diff(raw[np.isfinite(raw)])
    if d.size < 3:
        return 0.0
    return float(1.4826 * np.median(np.abs(d - np.median(d))) / np.sqrt(2))


def split_levels(x, raw, a, b, min_len, rel, out):
    """Recursive binary segmentation of x[a:b] into piecewise-constant levels."""
    seg = x[a:b]
    n = b - a
    if n < 2 * min_len:
        out.append((a, b))
        return
    filled = np.where(np.isfinite(seg), seg, np.nanmedian(seg))
    cs = np.cumsum(filled)
    cs2 = np.cumsum(filled * filled)
    tot, tot2 = cs[-1], cs2[-1]
    ks = np.arange(min_len, n - min_len + 1)
    left_n = ks.astype(float)
    right_n = n - left_n
    lsum = cs[ks - 1]
    lsum2 = cs2[ks - 1]
    sse = (lsum2 - lsum * lsum / left_n) + ((tot2 - lsum2) - (tot - lsum) ** 2 / right_n)
    k = int(ks[np.argmin(sse)])
    lm, rm = np.nanmedian(seg[:k]), np.nanmedian(seg[k:])
    top = max(abs(lm), abs(rm))
    step = abs(lm - rm)
    sigma = noise(raw[a:b])
    if top > 0 and (step / top >= rel or (step / top >= MIN_STEP and sigma > 0 and step >= NOISE_SIGMAS * sigma)):
        split_levels(x, raw, a, a + k, min_len, rel, out)
        split_levels(x, raw, a + k, b, min_len, rel, out)
    else:
        out.append((a, b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--nodes", default="all")
    ap.add_argument("--level-change", type=float, default=LEVEL_CHANGE,
                    help="relative level difference needed to split (default %.2f)" % LEVEL_CHANGE)
    ap.add_argument("--min-section-min", type=float, default=None,
                    help="shortest section in minutes (default: max(5, 1%% of run))")
    ap.add_argument("--no-plot", action="store_true")
    args = ap.parse_args()
    out = os.path.abspath(os.path.expanduser(args.out))
    nodes = sc.open_nodes(out, args.nodes)

    t0 = min(np.nanmin(n.time) for n in nodes if n.time.size)
    t1 = max(np.nanmax(n.time) for n in nodes if n.time.size)
    dur = t1 - t0
    dts = [np.nanmedian(np.diff(n.time)) for n in nodes if n.time.size > 1]
    dt = float(np.nanmedian(dts)) if dts else 1.0
    nbins = int(max(1, min(dur / dt, 2000)))
    edges = np.linspace(t0, t1 + 1e-6, nbins + 1)
    bw = (edges[1] - edges[0])

    signal = None
    for name in SIGNALS:
        s = binned(nodes, name, edges)
        if s is not None and np.isfinite(s).any():
            signal = name
            break
    if signal is None:
        raise SystemExit("No throughput metric (%s) found - ask the user for explicit time ranges." % ", ".join(SIGNALS))
    other = [binned(nodes, m, edges) for m in SIGNALS if m != signal]
    other = other[0] if other else None

    min_section_s = (args.min_section_min * 60) if args.min_section_min else max(300.0, 0.01 * dur)
    min_len = max(2, int(round(min_section_s / bw)))
    smooth = rolling_median(s, max(1, min_len // 2) | 1)
    peak = np.nanpercentile(smooth, 99) if np.isfinite(smooth).any() else 0
    sections = []
    if not peak or peak <= 0:
        spans = [(0, nbins, "idle")]
    else:
        x = smooth / peak
        raw = s / peak
        busy = ~(raw < IDLE_FRAC)  # NaN bins count as busy so gaps in data do not split
        min_gap = max(2, int(np.ceil(60.0 / bw)))
        idle_runs = [r for r in runs(~busy) if r[1] - r[0] >= min_gap]
        spans, pos = [], 0
        for a, b in idle_runs:
            if a > pos:
                spans.append((pos, a, "load"))
            spans.append((a, b, "idle"))
            pos = b
        if pos < nbins:
            spans.append((pos, nbins, "load"))
        refined = []
        for a, b, kind in spans:
            if kind == "idle":
                refined.append((a, b, kind))
                continue
            parts = []
            split_levels(x, raw, a, b, min_len, args.level_change, parts)
            refined.extend((pa, pb, "load") for pa, pb in parts)
        spans = refined

    for i, (a, b, kind) in enumerate(spans, 1):
        seg = s[a:b]
        med = float(np.nanmedian(seg)) if np.isfinite(seg).any() else float("nan")
        p10, p90 = (np.nanpercentile(seg, [10, 90]) if np.isfinite(seg).any() else (np.nan, np.nan))
        sections.append({
            "id": i,
            "kind": kind,
            "start": float(edges[a]),
            "end": float(edges[b]),  # b <= nbins, so edges[b] always exists
            "start_utc": sc.fmt_time(edges[a]),
            "end_utc": sc.fmt_time(edges[b]),
            "minutes": round((edges[b] - edges[a]) / 60, 1),
            "level_pct_of_peak": round(100 * med / peak, 1) if peak else 0.0,
            "median_" + signal: round(med, 2),
            "median_" + (SIGNALS[1] if signal == SIGNALS[0] else SIGNALS[0]):
                (round(float(np.nanmedian(other[a:b])), 2) if other is not None and np.isfinite(other[a:b]).any() else None),
            "spread_pct": round(100 * (p90 - p10) / med, 1) if med and med == med else None,
        })

    doc = {"signal": signal, "nodes": [n.node for n in nodes], "run_start": float(t0), "run_end": float(t1),
           "run_start_utc": sc.fmt_time(t0), "run_end_utc": sc.fmt_time(t1), "sections": sections}
    with open(os.path.join(out, "sections.json"), "w") as f:
        json.dump(doc, f, indent=1)

    loads = [x for x in sections if x["kind"] == "load"]
    print("run: %s -> %s UTC  (%.1f h, sample every ~%.0fs, nodes %s)" % (
        doc["run_start_utc"], doc["run_end_utc"], dur / 3600, dt, ",".join(str(n.node) for n in nodes)))
    print("signal: %s (median across nodes); levels are %% of this run's peak\n" % signal)
    oname = "median_" + (SIGNALS[1] if signal == SIGNALS[0] else SIGNALS[0])
    print("%3s %-5s %-19s %-8s %8s %7s %14s %14s %8s" % ("id", "kind", "start (UTC)", "end", "minutes",
                                                       "level%", signal, oname.replace("median_", ""), "spread%"))
    for x in sections:
        print("%3d %-5s %-19s %-8s %8.1f %7.1f %14s %14s %8s" % (
            x["id"], x["kind"], x["start_utc"], x["end_utc"][11:], x["minutes"], x["level_pct_of_peak"],
            x["median_" + signal], x[oname], x["spread_pct"]))
    print("\n%d load section(s), %d idle section(s)." % (len(loads), len(sections) - len(loads)))
    if len(loads) >= 2:
        print("RECOMMEND: multiple load phases - ask the user which section(s) to graph.")
    else:
        print("Single load phase - graphing the whole run is likely readable.")

    if not args.no_plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        mids = (edges[:-1] - t0) / 60
        fig, ax = plt.subplots(figsize=(13, 4.5))
        ax.plot(mids, s, lw=0.8, color="0.25", label=signal + " (median of nodes)")
        if other is not None:
            ax.plot(mids, other, lw=0.6, color="tab:blue", alpha=0.6,
                    label=(SIGNALS[1] if signal == SIGNALS[0] else SIGNALS[0]))
        for x in sections:
            a, b = (x["start"] - t0) / 60, (x["end"] - t0) / 60
            ax.axvspan(a, b, color=("0.9" if x["kind"] == "idle" else "tab:orange"),
                       alpha=(0.6 if x["kind"] == "idle" else 0.12 + 0.1 * (x["id"] % 2)), lw=0)
            ax.text((a + b) / 2, 1.01, str(x["id"]), transform=ax.get_xaxis_transform(), ha="center", fontsize=8)
        ax.set_xlabel("minutes since %s UTC" % doc["run_start_utc"])
        ax.set_title("Sections (numbers above) - orange = load, grey = idle", fontsize=10, pad=14)
        ax.set_xlim(0, dur / 60)
        ax.grid(alpha=0.3)
        ax.legend(fontsize=8, loc="upper right")
        fig.tight_layout()
        p = os.path.join(out, "sections-overview.png")
        fig.savefig(p, dpi=110)
        print("overview: %s" % p)


if __name__ == "__main__":
    with warnings.catch_warnings(), np.errstate(invalid="ignore", divide="ignore"):
        warnings.simplefilter("ignore", RuntimeWarning)
        main()
