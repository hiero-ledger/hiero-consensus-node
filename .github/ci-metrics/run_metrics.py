#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""End-of-run metrics: write run-metrics.json for the run this job belongs to.

Runs as the last job of a workflow (see 600). At that moment the run's jobs, step detail and shard JUnit reports all
still exist, so nothing is lost to the API's retention limits (steps ~18 days, reports 7 days). The file is uploaded as
an artifact; ingest.py later pulls it into a local SQLite database.

The run's conclusion is not known yet (this job is part of it), so it is derived from the "CI Complete" job when there
is one, otherwise from the other jobs.

Usage: run_metrics.py --out FILE [--run-id ID]    (context from CTX_* environment variables)
"""
import argparse
import importlib.util
import json
import os
import sys
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = 1
TOP_CLASSES_PER_SHARD = 15


def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


collect = _load("collect")
tt = _load("test_timings")


def derive_conclusion(jobs):
    for j in jobs:
        if j["name"] == "CI Complete":
            return j["conclusion"]
    return "failure" if any(j["conclusion"] == "failure" for j in jobs) else "success"


def build_document(run, jobs, context, tests):
    """Pure assembly, separate from the API calls so it can be tested."""
    done = [j for j in jobs if j["status"] == "completed"]  # drops this job, which is still running
    slim = dict(run)
    slim["status"] = "completed"
    slim["conclusion"] = derive_conclusion(done)
    return {"schema": SCHEMA, "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "run": slim, "context": context, "jobs": done, "tests": tests}


def context_from_env(environ):
    return {k[4:].lower(): v for k, v in sorted(environ.items()) if k.startswith("CTX_")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID"))
    args = ap.parse_args()
    if not args.run_id:
        ap.error("--run-id or GITHUB_RUN_ID is required")
    if "GITHUB_REPOSITORY" in os.environ:
        collect.API = f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}"

    raw = collect.get(f"{collect.API}/actions/runs/{args.run_id}")
    run = collect.slim_run(raw, raw["workflow_id"])  # unknown workflow ids raise KeyError on purpose
    jobs = collect.fetch_jobs({"id": raw["id"]})
    tests = tt.test_rows_for_run({"id": raw["id"], "created_at": raw["created_at"], "head_sha": raw["head_sha"]},
                                 top_classes=TOP_CLASSES_PER_SHARD, workers=4)
    doc = build_document(run, jobs, context_from_env(os.environ), tests)
    with open(args.out, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    print(f"wrote {args.out}: {len(doc['jobs'])} jobs, {len(tests)} test rows, {os.path.getsize(args.out) / 1024:.0f} KB, "
          f"conclusion {doc['run']['conclusion']}")


if __name__ == "__main__":
    sys.exit(main())
