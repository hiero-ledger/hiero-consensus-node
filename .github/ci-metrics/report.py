#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Render metrics.json + plan.md + gaps.md into one self-contained HTML file.

Usage: report.py --metrics metrics.json --out report.html
"""
import argparse
import html
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def md_inline(t):
    t = html.escape(t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    return t


def md_to_html(md):
    """Tiny markdown subset: #/##/### headings, paragraphs, - bullets, 1. lists, | tables |, **bold**, `code`."""
    out, lines, i = [], md.splitlines(), 0
    while i < len(lines):
        l = lines[i]
        if not l.strip():
            i += 1
        elif l.startswith("#"):
            n = len(l) - len(l.lstrip("#"))
            out.append(f"<h{n}>{md_inline(l[n:].strip())}</h{n}>")
            i += 1
        elif l.lstrip().startswith("|"):
            rows = []
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            rows = [r for r in rows if not all(re.fullmatch(r":?-+:?", c) for c in r)]
            out.append("<table><thead><tr>" + "".join(f"<th>{md_inline(c)}</th>" for c in rows[0]) + "</tr></thead><tbody>"
                       + "".join("<tr>" + "".join(f"<td>{md_inline(c)}</td>" for c in r) + "</tr>" for r in rows[1:])
                       + "</tbody></table>")
        elif re.match(r"\s*([-*]|\d+\.)\s", l):
            tag = "ol" if re.match(r"\s*\d+\.", l) else "ul"
            items = []
            while i < len(lines) and re.match(r"\s*([-*]|\d+\.)\s", lines[i]):
                items.append(re.sub(r"^\s*([-*]|\d+\.)\s+", "", lines[i]))
                i += 1
            out.append(f"<{tag}>" + "".join(f"<li>{md_inline(x)}</li>" for x in items) + f"</{tag}>")
        else:
            para = []
            while i < len(lines) and lines[i].strip() and not lines[i].startswith("#") \
                    and not lines[i].lstrip().startswith("|") and not re.match(r"\s*([-*]|\d+\.)\s", lines[i]):
                para.append(lines[i].strip())
                i += 1
            out.append(f"<p>{md_inline(' '.join(para))}</p>")
    return "\n".join(out)


def headlines(m):
    h = []
    for name, s in m["suites"].items():
        tot = s["waste"]["total_min"]
        top = s["stages"][0]
        h.append(f"{name}: the biggest stage is {top['stage']}, {top['total_min'] / tot:.0%} of runner time "
                 f"({top['total_min'] / 60:,.0f} h).")
        w = s["waste"]
        h.append(f"{name}: {w['wasted_min'] / tot:.1%} of runner time ({w['wasted_min'] / 60:,.0f} h) went to jobs that "
                 f"failed, were cancelled or were redone; cancelled jobs alone are {w['cancelled_min'] / tot:.1%}.")
        h.append(f"{name}: jobs waited {s['summary']['queue_min'] / 60:,.0f} h in queue in total "
                 f"({s['summary']['queue_min'] / s['summary']['runner_min']:.1%} of runner time).")
        if w["never_started_jobs"]:
            labels = ", ".join(f"{l} ({n})" for l, n in sorted(w["never_started_labels"].items(), key=lambda kv: -kv[1])[:3])
            h.append(f"{name}: {w['never_started_jobs']} jobs in {w['never_started_runs']} runs waited 1+ hour for a runner and were cancelled "
                     f"before starting, {w['never_started_queue_h']:,.0f} queue hours; most by label: {labels}. Not counted as runner time.")
        oh = s["overhead"]
        h.append(f"{name}: {oh['small_jobs']:,} of {oh['jobs']:,} jobs ran under 3 minutes ({oh['small_jobs_min'] / 60:,.0f} h of run time plus "
                 f"{oh['small_jobs_queue_min'] / 60:,.0f} h of queue wait).")
        if oh["gate_failed_runs"]:
            h.append(f"{name}: in {oh['gate_failed_runs']} runs a compile, unit or integration job failed while HAPI/Otter shards kept running, "
                     f"spending {oh['heavy_min_in_gate_failed_runs'] / 60:,.0f} h ({oh['heavy_min_in_gate_failed_runs'] / w['total_min']:.1%} of {name} runner time).")
        cp = s["critical_path"][0]
        h.append(f"{name}: the stage that most often sets wall-clock time is {cp['stage']} "
                 f"(on the critical path in {cp['share_of_runs']:.0%} of runs, {cp['mean_path_min']:.0f} min when present).")
    return h


def load_test_summary(rows):
    spec = importlib.util.spec_from_file_location("test_timings", os.path.join(HERE, "test_timings.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.summarize(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--metrics", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tests", help="test-timings.jsonl from test_timings.py; adds the per-test timing section")
    args = ap.parse_args()
    with open(args.metrics) as f:
        m = json.load(f)
    m["headlines"] = headlines(m)
    if args.tests:
        with open(args.tests) as f:
            m["tests"] = load_test_summary([json.loads(l) for l in f])
    with open(os.path.join(HERE, "report_template.html")) as f:
        tpl = f.read()
    sections = {}
    for key in ("plan", "gaps"):
        with open(os.path.join(HERE, f"{key}.md")) as f:
            sections[key.upper()] = md_to_html(f.read())
    blob = json.dumps(m, separators=(",", ":")).replace("</", "<\\/")
    page = tpl.replace("%%PLAN%%", sections["PLAN"]).replace("%%GAPS%%", sections["GAPS"]).replace("%%METRICS%%", blob)
    with open(args.out, "w") as f:
        f.write(page)
    print(f"wrote {args.out} ({len(page) / 1024:.0f} KB)")


if __name__ == "__main__":
    sys.exit(main())
