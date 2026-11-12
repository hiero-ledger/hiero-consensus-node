#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""GitHub Actions API helpers for run_metrics.py: authenticated GET with rate-limit handling and bounded retries on
transient errors, plus the run and job shapes the rest of the metrics code uses. Fails loudly on anything unexpected.
"""
import http.client
import json
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request

REPO = "hiero-ledger/hiero-consensus-node"
API = f"https://api.github.com/repos/{REPO}"

# workflow id -> (name, suite). Suite is what the consumers group by. Add an entry before publishing run metrics from a new workflow.
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
