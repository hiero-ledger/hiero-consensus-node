#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""End-of-run metrics: write run-metrics.json for the run this job belongs to.

Runs as the last job of a workflow (see 865-call-publish-run-metrics.yaml). At that moment the run's jobs, step detail and
shard JUnit reports all still exist, so nothing is lost to the API's retention limits (steps ~18 days, reports 7 days). The
file is uploaded as an artifact for a consumer outside this repository (see .github/workflows/docs/ci-metrics.md).

The run's conclusion is not known yet (this job is part of it), so it is derived: from the latest attempt's "CI Complete" job
when the workflow has one, otherwise from the latest attempt's other jobs.

Context comes from the environment: a JSON object in CTX_JSON and/or individual CTX_<NAME> variables.

Usage: run_metrics.py --out FILE [--run-id ID]
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
    """Conclusion of the run from its completed jobs, using the latest attempt only."""
    if not jobs:
        return "success"
    latest = max(j["run_attempt"] for j in jobs)
    current = [j for j in jobs if j["run_attempt"] == latest]
    for j in current:
        if j["name"] == "CI Complete":
            return j["conclusion"]
    conclusions = {j["conclusion"] for j in current}
    if "failure" in conclusions or "timed_out" in conclusions:
        return "failure"
    if "cancelled" in conclusions:
        return "cancelled"
    return "success"


def build_document(run, jobs, context, tests, test_errors):
    """Pure assembly, separate from the API calls so it can be tested."""
    done = [j for j in jobs if j["status"] == "completed"]  # drops this job, which is still running
    slim = dict(run)
    slim["status"] = "completed"
    slim["conclusion"] = derive_conclusion(done)
    return {"schema": SCHEMA, "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "run": slim, "context": context, "jobs": done, "tests": tests, "test_errors": test_errors}


def context_from_env(environ):
    """CTX_JSON (an object of string values) first, then CTX_<NAME> variables, which win on a clash."""
    ctx = {}
    if environ.get("CTX_JSON"):
        parsed = json.loads(environ["CTX_JSON"])
        if not isinstance(parsed, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in parsed.items()):
            raise ValueError("CTX_JSON must be a JSON object whose values are strings")
        ctx.update(parsed)
    ctx.update({k[4:].lower(): v for k, v in sorted(environ.items()) if k.startswith("CTX_") and k != "CTX_JSON"})
    return ctx


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
    run = collect.slim_run(raw)  # a workflow that is not in collect.WORKFLOWS raises on purpose
    jobs = collect.fetch_jobs({"id": raw["id"]})
    tests, test_errors = tt.test_rows_for_run({"id": raw["id"], "created_at": raw["created_at"], "head_sha": raw["head_sha"]},
                                              top_classes=TOP_CLASSES_PER_SHARD, workers=4)
    doc = build_document(run, jobs, context_from_env(os.environ), tests, test_errors)
    with open(args.out, "w") as f:
        json.dump(doc, f, separators=(",", ":"))
    print(f"wrote {args.out}: {len(doc['jobs'])} jobs, {len(tests)} test rows, {len(test_errors)} unreadable reports, "
          f"{os.path.getsize(args.out) / 1024:.0f} KB, conclusion {doc['run']['conclusion']}")


if __name__ == "__main__":
    sys.exit(main())
