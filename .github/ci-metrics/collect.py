#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Collect run + job timing data for the MATS and XTS entry-point workflows.

Writes <out>/runs.jsonl and <out>/jobs.jsonl. Resumable: runs whose jobs are already in
jobs.jsonl are skipped. Fails loudly on any unexpected API response.

Usage: collect.py --out DIR [--days 90] [--workers 32]
"""
import argparse
import http.client
import json
import math
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone

REPO = "hiero-ledger/hiero-consensus-node"
API = f"https://api.github.com/repos/{REPO}"

# workflow id -> (name, suite). Suite is what the report groups by.
WORKFLOWS = {
    291484377: ("600: PR Checks", "MATS"),
    297070668: ("000: MATS Dry Run", "MATS"),
    382618421: ("226: XTS Controller", "XTS"),
    297070710: ("001: XTS Dry Run", "XTS"),
    343942254: ("106: XTS Optional Tests", "XTS"),
    297167115: ("900: Ext Test Suite cron", "XTS"),
    297167116: ("901: Promote Build cron", "XTS"),
    382618422: ("227: Daily Controllers", "XTS"),
}

_token = None
_lock = threading.Lock()
retries = {}
_rate = {"remaining": 5000, "reset": 0}


def token():
    global _token
    if _token is None:
        _token = os.environ.get("GH_TOKEN") or subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    return _token


def get(url):
    """GET json, honouring the primary rate limit. Raises on any other failure."""
    while True:
        with _lock:
            wait = _rate["reset"] - time.time() + 5 if _rate["remaining"] < 25 else 0
        if wait > 0:
            print(f"[rate-limit] remaining<25, sleeping {wait:.0f}s until reset", flush=True)
            time.sleep(wait)
            with _lock:
                _rate["remaining"] = 5000
        req = urllib.request.Request(url, headers={
            "Authorization": f"Bearer {token()}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                with _lock:
                    _rate["remaining"] = int(resp.headers["X-RateLimit-Remaining"])
                    _rate["reset"] = int(resp.headers["X-RateLimit-Reset"])
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (403, 429) and e.headers.get("X-RateLimit-Remaining") == "0":
                with _lock:
                    _rate["remaining"] = 0
                    _rate["reset"] = int(e.headers["X-RateLimit-Reset"])
                continue
            if e.code in (500, 502, 503, 504):
                attempt = retries.get(url, 0) + 1
                if attempt <= 5:  # transient server error: bounded retry, then raise below
                    retries[url] = attempt
                    print(f"[retry {attempt}/5] HTTP {e.code} on {url}", flush=True)
                    time.sleep(2 * attempt)
                    continue
            raise RuntimeError(f"GET {url} -> {e.code} {e.read()[:300]!r}") from e
        except (http.client.IncompleteRead, ConnectionError, TimeoutError, urllib.error.URLError) as e:
            # transient transport failure (truncated body, reset, timeout): bounded retry, then raise
            attempt = retries.get(url, 0) + 1
            if attempt > 5:
                raise
            retries[url] = attempt
            print(f"[retry {attempt}/5] {type(e).__name__} on {url}", flush=True)
            time.sleep(2 * attempt)


def _list_window(wf_id, start, end):
    """All runs created in [start, end). The API caps any query at 1000 results, so split windows that hit the cap."""
    q = f"created={start.strftime('%Y-%m-%dT%H:%M:%SZ')}..{(end - timedelta(seconds=1)).strftime('%Y-%m-%dT%H:%M:%SZ')}"
    first = get(f"{API}/actions/workflows/{wf_id}/runs?{q}&per_page=100&page=1")
    total = first["total_count"]
    if total > 1000:
        mid = start + (end - start) / 2
        return _list_window(wf_id, start, mid) + _list_window(wf_id, mid, end)
    runs, page, data = list(first["workflow_runs"]), 1, first
    while len(runs) < total:
        page += 1
        data = get(f"{API}/actions/workflows/{wf_id}/runs?{q}&per_page=100&page={page}")
        if not data["workflow_runs"]:
            raise RuntimeError(f"workflow {wf_id}: got {len(runs)} of {total} runs for {q}")
        runs += data["workflow_runs"]
    return runs


def list_runs(wf_id, since):
    start = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    end = datetime.now(timezone.utc) + timedelta(seconds=2)
    runs = _list_window(wf_id, start, end)
    ids = {r["id"] for r in runs}
    if len(ids) != len(runs):
        raise RuntimeError(f"workflow {wf_id}: duplicate runs across windows")
    return runs


def slim_run(r, wf_id):
    return {k: r[k] for k in ("id", "name", "event", "head_branch", "head_sha", "status", "conclusion",
                              "run_attempt", "created_at", "run_started_at", "updated_at", "html_url",
                              "display_title") if k in r} | {
        "workflow_id": wf_id, "workflow": WORKFLOWS[wf_id][0], "suite": WORKFLOWS[wf_id][1],
        "actor_login": r["actor"]["login"], "pr_numbers": [p["number"] for p in r["pull_requests"]],
    }


JOBS_PER_PAGE = 30  # per_page=100 returns HTTP 502 for runs with many reruns (large step payloads)


def fetch_jobs(run):
    jobs, page = [], 1
    while True:
        data = get(f"{API}/actions/runs/{run['id']}/jobs?filter=all&per_page={JOBS_PER_PAGE}&page={page}")
        for j in data["jobs"]:
            jobs.append({
                "run_id": run["id"], "id": j["id"], "name": j["name"], "status": j["status"],
                "conclusion": j["conclusion"], "run_attempt": j["run_attempt"],
                "created_at": j["created_at"], "started_at": j["started_at"], "completed_at": j["completed_at"],
                "runner_name": j["runner_name"], "runner_group_name": j["runner_group_name"], "labels": j["labels"],
                "steps": [{"name": s["name"], "conclusion": s["conclusion"],
                           "started_at": s["started_at"], "completed_at": s["completed_at"]} for s in j["steps"]],
            })
        if len(jobs) >= data["total_count"] or not data["jobs"]:
            return jobs
        page += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--days", type=int, default=90)
    ap.add_argument("--workers", type=int, default=32)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    since = (datetime.now(timezone.utc) - timedelta(days=args.days)).strftime("%Y-%m-%d")
    t0 = time.time()

    runs = []
    for wf_id, (name, _) in WORKFLOWS.items():
        rs = list_runs(wf_id, since)
        print(f"[runs] {name}: {len(rs)} runs since {since} ({time.time() - t0:.0f}s)", flush=True)
        runs += [slim_run(r, wf_id) for r in rs]
    with open(os.path.join(args.out, "runs.jsonl"), "w") as f:
        for r in runs:
            f.write(json.dumps(r) + "\n")

    jobs_path = os.path.join(args.out, "jobs.jsonl")
    done = set()
    if os.path.exists(jobs_path):
        with open(jobs_path) as f:
            done = {json.loads(l)["run_id"] for l in f if l.strip()}
        # a run with zero jobs leaves no trace; tracked separately below
    marker = os.path.join(args.out, "empty_runs.txt")
    if os.path.exists(marker):
        with open(marker) as f:
            done |= {int(x) for x in f.read().split()}
    todo = [r for r in runs if r["id"] not in done and r["status"] == "completed"]
    print(f"[jobs] {len(todo)} completed runs to fetch ({len(done)} already cached)", flush=True)

    n = 0
    with open(jobs_path, "a") as out, open(marker, "a") as empty, ThreadPoolExecutor(args.workers) as pool:
        futs = {pool.submit(fetch_jobs, r): r for r in todo}
        for fut in as_completed(futs):
            r = futs[fut]
            jobs = fut.result()  # raises -> loud failure
            if not jobs:
                empty.write(f"{r['id']}\n")
            for j in jobs:
                out.write(json.dumps(j) + "\n")
            n += 1
            if n % 50 == 0 or n == len(todo):
                out.flush(); empty.flush()
                print(f"[jobs] {n}/{len(todo)} runs ({time.time() - t0:.0f}s, api remaining {_rate['remaining']})", flush=True)
    print(f"[done] {len(runs)} runs in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    sys.exit(main())
