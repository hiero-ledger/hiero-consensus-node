#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""GitHub Actions API helpers for run_metrics.py: an authenticated GET with bounded retries on transient errors, and the
run and job shapes the metrics code uses. Fails loudly on anything unexpected, including a rate limit: the workflow's
GITHUB_TOKEN gets about 1,000 calls an hour per repository and a run needs about 30, so waiting for a reset is not an option.
"""
import http.client
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

REPO = "hiero-ledger/hiero-consensus-node"
API = f"https://api.github.com/repos/{REPO}"

# Workflow file name -> (name, suite). A run is identified by its workflow file, not the numeric workflow id, which
# changes when a file is renamed. Add an entry before publishing run metrics from another workflow.
WORKFLOWS = {
    "000-user-dry-run-mats-suite.yaml": ("000: MATS Dry Run", "MATS"),
    "300-flow-build-application.yaml": ("300: Build Application", "MATS"),
    "600-flow-pull-request-checks.yaml": ("600: PR Checks", "MATS"),
    "001-user-dry-run-extended-test-suite.yaml": ("001: XTS Dry Run", "XTS"),
    "106-disp-xts-optional-tests.yaml": ("106: XTS Optional Tests", "XTS"),
    "226-disp-citr-xts-controller.yaml": ("226: XTS Controller", "XTS"),
}

ATTEMPTS = 4
_token = None


def token():
    global _token
    if _token is None:
        _token = os.environ.get("GH_TOKEN") or subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    return _token


def get(url):
    """GET json. Transient failures (5xx, dropped or truncated connections) are retried a few times, anything else raises."""
    for attempt in range(1, ATTEMPTS + 1):
        req = urllib.request.Request(url, headers={
            "Authorization": f"Bearer {token()}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            if e.code in (403, 429) and e.headers.get("X-RateLimit-Remaining") == "0":
                raise RuntimeError(f"GitHub API rate limit reached (resets at epoch {e.headers['X-RateLimit-Reset']}) on {url}") from e
            if e.code in (500, 502, 503, 504) and attempt < ATTEMPTS:
                print(f"[retry {attempt}/{ATTEMPTS - 1}] HTTP {e.code} on {url}", flush=True)
                time.sleep(2 * attempt)
                continue
            raise RuntimeError(f"GET {url} -> {e.code} {e.read()[:300]!r}") from e
        except (http.client.IncompleteRead, ConnectionError, TimeoutError, urllib.error.URLError) as e:
            if attempt == ATTEMPTS:
                raise
            print(f"[retry {attempt}/{ATTEMPTS - 1}] {type(e).__name__} on {url}", flush=True)
            time.sleep(2 * attempt)


def workflow_file(raw_run):
    """File name of the run's workflow. The run's path can carry an @ref suffix (for example for pull_request_target)."""
    return raw_run["path"].split("@")[0].rsplit("/", 1)[-1]


def slim_run(r):
    file = workflow_file(r)
    if file not in WORKFLOWS:
        raise KeyError(f"workflow file {file} is not in collect.WORKFLOWS; add it before publishing run metrics from it")
    name, suite = WORKFLOWS[file]
    return {k: r[k] for k in ("id", "name", "event", "head_branch", "head_sha", "status", "conclusion",
                              "run_attempt", "created_at", "run_started_at", "updated_at", "html_url",
                              "display_title") if k in r} | {
        "workflow_id": r["workflow_id"], "workflow": name, "suite": suite, "workflow_file": file,
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
