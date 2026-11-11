#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Per-test timings from the JUnit report artifacts of sampled MATS runs.

Each HAPI/Otter shard uploads its TEST-*.xml files as an artifact kept for 7 days. This script samples a few
successful PR runs per day, downloads those artifacts, and writes per-class and slowest-test timings to a jsonl
file that is merged by (run, shard), so re-running is safe and older rows are kept.

Rows (kind): "shard" (totals per shard and run), "class" (classes taking >= 2 s), "case" (slowest 30 tests per shard).
Class times are wall-clock per class. HAPI classes run concurrently on a shared network, so they overlap and the sum
exceeds the shard's duration: read them as relative weight, not as additive minutes.

Usage: test_timings.py --out FILE [--days 6] [--per-day 2]
"""
import argparse
import importlib.util
import io
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
PR_CHECKS_WORKFLOW_ID = 291484377
ARTIFACT_RE = re.compile(r"^(HAPI Test .+ Reports|.+ Otter Tests Report|Falcon Tests Report)$")
MAX_ZIP_BYTES = 40 * 1024 * 1024
MAX_MEMBER_BYTES = 300 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
MIN_CLASS_SECONDS = 2.0
SLOW_CASES_PER_SHARD = 30


def _load_collect():
    # python -I does not put the script directory on sys.path, so load the sibling module by path
    spec = importlib.util.spec_from_file_location("collect", os.path.join(HERE, "collect.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


collect = _load_collect()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def download_zip(artifact_id):
    """Artifact zips redirect to blob storage. Follow the redirect by hand so the API token is never sent there."""
    url = f"{collect.API}/actions/artifacts/{artifact_id}/zip"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {collect.token()}",
                                               "Accept": "application/vnd.github+json",
                                               "X-GitHub-Api-Version": "2022-11-28"})
    try:
        urllib.request.build_opener(_NoRedirect).open(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code != 302:
            raise RuntimeError(f"GET {url} -> {e.code}") from e
        location = e.headers["Location"]
    else:
        raise RuntimeError(f"GET {url}: expected a redirect")
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(location, timeout=180) as resp:
                data = resp.read(MAX_ZIP_BYTES + 1)
            break
        except (OSError, urllib.error.URLError) as e:  # transient network failure: bounded retry
            if attempt == 3:
                raise
            print(f"[retry {attempt}/3] artifact {artifact_id}: {type(e).__name__}", flush=True)
            time.sleep(2 * attempt)
    if len(data) > MAX_ZIP_BYTES:
        raise RuntimeError(f"artifact {artifact_id} larger than {MAX_ZIP_BYTES} bytes")
    return data


def parse_junit_zip(data):
    """-> (suites, cases). suites: [(class, tests, failed, skipped, suite_seconds, timestamp)],
    cases: [(class, name, seconds, outcome, suite_timestamp)].

    The zip comes from a CI run, so treat it as untrusted: size caps, no DOCTYPE or entity declarations, never extracted.
    """
    suites, cases, total = [], [], 0
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            if not re.search(r"(^|/)TEST-[^/]+\.xml$", info.filename):
                continue
            total += info.file_size
            if info.file_size > MAX_MEMBER_BYTES or total > MAX_TOTAL_BYTES:
                raise ValueError(f"{info.filename}: report archive too large to parse")
            with z.open(info) as f:
                head = f.read(4096)
            if b"<!DOCTYPE" in head or b"<!ENTITY" in head:
                raise ValueError(f"{info.filename}: DOCTYPE/ENTITY declarations are not accepted")
            pending = []  # cases of the file being read; their suite closes after them
            with z.open(info) as f:
                for _, el in ET.iterparse(f, events=("end",)):
                    if el.tag == "testcase":
                        outcome = ("failed" if el.find("failure") is not None or el.find("error") is not None
                                   else "skipped" if el.find("skipped") is not None else "passed")
                        pending.append((el.attrib["classname"], el.attrib["name"], float(el.attrib["time"]), outcome))
                        el.clear()
                    elif el.tag == "testsuite":
                        a = el.attrib
                        suites.append((a["name"], int(a["tests"]), int(a["failures"]) + int(a["errors"]),
                                       int(a["skipped"]), float(a["time"]), a["timestamp"]))
                        cases += [c + (a["timestamp"],) for c in pending]
                        pending = []
                        el.clear()
    return suites, cases


def is_cached(timestamp, run_created_at):
    """Gradle restores test results from its build cache when nothing relevant changed. The XML then carries the
    timestamp of the original execution, which is older than the run that restored it."""
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00")) < datetime.fromisoformat(run_created_at.replace("Z", "+00:00"))


def rows_for_artifact(run_id, day, sha, shard, data, run_created_at, top_classes=None):
    """Rows for one shard artifact, executed classes only. Results restored from the Gradle build cache are counted in the
    shard row (cached_classes, cached_seconds) but get no class or case rows, because they were not run in this run.
    top_classes=N keeps only the N slowest classes (compact, for every run); the default keeps every class taking at
    least MIN_CLASS_SECONDS (for sampling)."""
    suites, cases = parse_junit_zip(data)
    base = {"run_id": run_id, "day": day, "sha": sha, "shard": shard}
    live = [s for s in suites if not is_cached(s[5], run_created_at)]
    cached = [s for s in suites if is_cached(s[5], run_created_at)]
    rows = [{**base, "kind": "shard", "classes": len(suites), "tests": sum(s[1] for s in suites),
             "failed": sum(s[2] for s in suites), "skipped": sum(s[3] for s in suites),
             "suite_seconds": round(sum(s[4] for s in live), 3), "cached_classes": len(cached),
             "cached_seconds": round(sum(s[4] for s in cached), 3)}]
    ranked = sorted(live, key=lambda s: -s[4])
    for name, tests, failed, skipped, seconds, _ in (ranked[:top_classes] if top_classes else
                                                     [s for s in ranked if s[4] >= MIN_CLASS_SECONDS]):
        rows.append({**base, "kind": "class", "class": name, "tests": tests, "failed": failed, "skipped": skipped,
                     "seconds": round(seconds, 3)})
    live_cases = [c for c in cases if not is_cached(c[4], run_created_at)]
    for cls, name, seconds, outcome, _ in sorted(live_cases, key=lambda c: -c[2])[:SLOW_CASES_PER_SHARD]:
        rows.append({**base, "kind": "case", "class": cls, "case": name, "seconds": round(seconds, 3), "outcome": outcome})
    return rows


def sample_runs(raw_runs, repo, per_day):
    """Successful first-attempt PR runs from branches in this repo (not forks), spread across each day."""
    ok = [r for r in raw_runs if r["status"] == "completed" and r["conclusion"] == "success" and r["run_attempt"] == 1
          and r["event"] == "pull_request" and r["head_repository"]["full_name"] == repo]
    by_day = defaultdict(list)
    for r in ok:
        by_day[r["created_at"][:10]].append(r)
    picked = []
    for day, rs in sorted(by_day.items()):
        rs.sort(key=lambda r: r["id"])
        n = min(per_day, len(rs))
        picked += [rs[round(i * (len(rs) - 1) / max(n - 1, 1))] for i in range(n)] if n > 1 else [rs[len(rs) // 2]]
    return list({r["id"]: r for r in picked}.values())


def shard_artifacts(run_id):
    """Non-expired shard report artifacts of a run; if a re-run attempt uploaded a name twice, the newest wins."""
    arts = collect.get(f"{collect.API}/actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    newest = {}
    for a in arts:
        if not a["expired"] and ARTIFACT_RE.match(a["name"]) and a["id"] > newest.get(a["name"], {"id": 0})["id"]:
            newest[a["name"]] = a
    return list(newest.values())


def test_rows_for_run(run, top_classes=None, workers=1):
    def one(a):
        return rows_for_artifact(run["id"], run["created_at"][:10], run["head_sha"], a["name"], download_zip(a["id"]), run["created_at"], top_classes)
    with ThreadPoolExecutor(workers) as pool:
        return [row for rows in pool.map(one, shard_artifacts(run["id"])) for row in rows]


def process_run(run):
    return run["id"], test_rows_for_run(run)


def merge(path, new_rows):
    """Replace rows for every (run, shard) present in new_rows; keep everything else."""
    replaced = {(r["run_id"], r["shard"]) for r in new_rows}
    old = []
    if os.path.exists(path):
        with open(path) as f:
            old = [r for r in map(json.loads, f) if (r["run_id"], r["shard"]) not in replaced]
    rows = sorted(old + new_rows, key=lambda r: (r["day"], r["run_id"], r["shard"], r["kind"], -r.get("seconds", 0)))
    with open(path, "w") as f:
        for r in rows:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")
    return len(rows)


def summarize(rows, top_classes=15, top_cases=25):
    """Aggregate sampled rows for the report: per shard, the classes with the largest mean time across the runs that
    actually executed that shard (runs served entirely from the build cache are counted separately)."""
    shards = defaultdict(lambda: {"runs": set(), "cached_runs": 0, "suite_seconds": [], "tests": []})
    executed = defaultdict(set)
    for r in rows:
        if r["kind"] == "shard":
            s = shards[r["shard"]]
            if r["classes"] - r["cached_classes"] == 0:
                s["cached_runs"] += 1
                continue
            s["runs"].add(r["run_id"])
            s["suite_seconds"].append(r["suite_seconds"])
            s["tests"].append(r["tests"])
            executed[r["shard"]].add(r["run_id"])
    classes = defaultdict(lambda: defaultdict(list))
    cases = defaultdict(list)
    for r in rows:
        if r["kind"] == "class":
            classes[r["shard"]][r["class"]].append(r["seconds"])
        elif r["kind"] == "case":
            cases[(r["shard"], r["class"], r["case"])].append(r["seconds"])
    out = {"days": [min(r["day"] for r in rows), max(r["day"] for r in rows)] if rows else None, "shards": [], "cases": []}
    for shard, s in sorted(((k, v) for k, v in shards.items() if v["runs"]), key=lambda kv: -statistics.fmean(kv[1]["suite_seconds"])):
        n = len(s["runs"])
        mean_total = statistics.fmean(s["suite_seconds"])
        top = sorted(classes[shard].items(), key=lambda cv: -sum(cv[1]) / n)[:top_classes]
        out["shards"].append({
            "shard": shard, "runs": n, "cached_runs": s["cached_runs"], "tests": round(statistics.fmean(s["tests"])),
            "suite_minutes": mean_total / 60,
            "classes": [{"class": c, "mean_s": sum(v) / n, "max_s": max(v), "share": (sum(v) / n) / mean_total}
                        for c, v in top]})
    for (shard, cls, case), v in sorted(cases.items(), key=lambda kv: -statistics.fmean(kv[1]))[:top_cases]:
        out["cases"].append({"shard": shard, "class": cls, "case": case, "mean_s": statistics.fmean(v), "n": len(v)})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--days", type=int, default=6, help="artifacts expire after 7 days; more than 6 loses the oldest")
    ap.add_argument("--per-day", type=int, default=2)
    args = ap.parse_args()
    t0 = time.time()
    start = datetime.now(timezone.utc) - timedelta(days=args.days)
    runs = collect._list_window(PR_CHECKS_WORKFLOW_ID, start, datetime.now(timezone.utc) + timedelta(seconds=2))
    picked = sample_runs(runs, collect.REPO, args.per_day)
    print(f"[tests] {len(runs)} PR runs in window, sampling {len(picked)} ({args.per_day}/day)", flush=True)
    new_rows, done = [], 0
    with ThreadPoolExecutor(4) as pool:
        for run_id, rows in pool.map(process_run, picked):
            new_rows += rows
            done += 1
            print(f"[tests] {done}/{len(picked)} run {run_id}: {sum(r['kind'] == 'shard' for r in rows)} shards "
                  f"({time.time() - t0:.0f}s)", flush=True)
    total = merge(args.out, new_rows)
    print(f"[done] {len(new_rows)} new rows, {total} in {args.out}")


if __name__ == "__main__":
    sys.exit(main())
