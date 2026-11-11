#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Local SQLite store for CI run metrics.

  ingest.py --db ci.sqlite pull                 download new run-metrics artifacts (written by run_metrics.py) and upsert them
  ingest.py --db ci.sqlite backfill --data DIR  one-time seed from collect.py output (the ~90 days the API still has)
  ingest.py --db ci.sqlite file run-metrics.json   load one artifact file
  ingest.py --db ci.sqlite stats                row counts, date range and weekly runner hours

Runs are keyed by run id and replaced as a whole, so every command is safe to repeat. Artifact data replaces backfilled
data for the same run (it has step detail and tests); backfill never overwrites artifact data. Pull needs GH_TOKEN or `gh`.
"""
import argparse
import importlib.util
import io
import json
import os
import sqlite3
import sys
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ARTIFACT_NAME = "run-metrics"
SCHEMA = 1


def _load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


analyze = _load("analyze")
collect = _load("collect")
tt = _load("test_timings")

DDL = """
CREATE TABLE IF NOT EXISTS runs (
  run_id INTEGER PRIMARY KEY, suite TEXT NOT NULL, workflow TEXT NOT NULL, event TEXT NOT NULL, action TEXT,
  pr INTEGER, head_sha TEXT NOT NULL, conclusion TEXT NOT NULL, created_at TEXT NOT NULL, day TEXT NOT NULL,
  week TEXT NOT NULL, attempts INTEGER NOT NULL, wall_s REAL NOT NULL, runner_s REAL NOT NULL, queue_s REAL NOT NULL,
  wasted_s REAL NOT NULL, docs_only TEXT, enable_tests TEXT, workflow_files_changed TEXT, smart_contracts_changed TEXT,
  failure_mode TEXT, source TEXT NOT NULL, artifact_id INTEGER, url TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS jobs (
  job_id INTEGER PRIMARY KEY, run_id INTEGER NOT NULL REFERENCES runs(run_id), name TEXT NOT NULL, stage TEXT NOT NULL,
  runner_class TEXT NOT NULL, attempt INTEGER NOT NULL, conclusion TEXT NOT NULL, created_at TEXT NOT NULL,
  started_at TEXT NOT NULL, completed_at TEXT NOT NULL, dur_s REAL NOT NULL, queue_s REAL NOT NULL, redone INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS steps (
  job_id INTEGER NOT NULL REFERENCES jobs(job_id), idx INTEGER NOT NULL, name TEXT NOT NULL, conclusion TEXT,
  seconds REAL, PRIMARY KEY (job_id, idx));
CREATE TABLE IF NOT EXISTS test_shards (
  run_id INTEGER NOT NULL, shard TEXT NOT NULL, classes INTEGER NOT NULL, tests INTEGER NOT NULL, failed INTEGER NOT NULL,
  skipped INTEGER NOT NULL, suite_seconds REAL NOT NULL, cached_classes INTEGER NOT NULL, cached_seconds REAL NOT NULL,
  PRIMARY KEY (run_id, shard));
CREATE TABLE IF NOT EXISTS test_classes (
  run_id INTEGER NOT NULL, shard TEXT NOT NULL, class TEXT NOT NULL, tests INTEGER NOT NULL, failed INTEGER NOT NULL,
  skipped INTEGER NOT NULL, seconds REAL NOT NULL, PRIMARY KEY (run_id, shard, class));
CREATE TABLE IF NOT EXISTS test_cases (
  run_id INTEGER NOT NULL, shard TEXT NOT NULL, class TEXT NOT NULL, test TEXT NOT NULL, seconds REAL NOT NULL,
  outcome TEXT NOT NULL, PRIMARY KEY (run_id, shard, class, test));
CREATE INDEX IF NOT EXISTS jobs_run ON jobs(run_id);
CREATE INDEX IF NOT EXISTS runs_day ON runs(day);
CREATE VIEW IF NOT EXISTS weekly_cost AS
  SELECT suite, week, COUNT(*) AS runs, ROUND(SUM(runner_s) / 3600.0, 1) AS runner_h, ROUND(SUM(wasted_s) / 3600.0, 1) AS wasted_h,
         ROUND(SUM(queue_s) / 3600.0, 1) AS queue_h FROM runs GROUP BY suite, week;
CREATE VIEW IF NOT EXISTS stage_cost AS
  SELECT r.suite, j.stage, j.runner_class, COUNT(*) AS jobs, ROUND(SUM(j.dur_s) / 3600.0, 1) AS runner_h,
         ROUND(AVG(j.dur_s) / 60.0, 1) AS mean_min, ROUND(AVG(j.queue_s), 0) AS mean_queue_s
  FROM jobs j JOIN runs r USING (run_id) GROUP BY r.suite, j.stage, j.runner_class;
"""


def connect(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(DDL)
    return conn


def _step_seconds(st):
    if not st["started_at"] or not st["completed_at"]:
        return None
    return (analyze.ts(st["completed_at"]) - analyze.ts(st["started_at"])).total_seconds()


def store_run(conn, run, jobs, context, tests, source, artifact_id=None):
    """Replace everything stored for this run. run/jobs use the collect.py shapes."""
    rid = run["id"]
    run_rows, job_rows, _ = analyze.build_rows({rid: run}, {rid: jobs}, {rid})
    if run_rows:
        r = run_rows[0]
        cost = (r["attempts"], r["wall"], r["runner"], r["queue"], r["wasted"], r["week"])
    else:  # every job was skipped or never ran: the run cost nothing, but it still happened
        created = analyze.ts(run["created_at"])
        cost = (max((j["run_attempt"] for j in jobs), default=1), 0.0, 0.0, 0.0, 0.0, analyze.week_of(created))
    day = analyze.ts(run["created_at"]).strftime("%Y-%m-%d")
    ctx = context or {}
    with conn:
        conn.execute("DELETE FROM steps WHERE job_id IN (SELECT job_id FROM jobs WHERE run_id = ?)", (rid,))
        for table in ("jobs", "test_shards", "test_classes", "test_cases"):
            conn.execute(f"DELETE FROM {table} WHERE run_id = ?", (rid,))
        conn.execute("DELETE FROM runs WHERE run_id = ?", (rid,))
        conn.execute("INSERT INTO runs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (
            rid, run["suite"], run["workflow"], run["event"], analyze.pr_action(run.get("display_title")) or ctx.get("action"),
            (run["pr_numbers"] or [None])[0], run["head_sha"], run["conclusion"], run["created_at"], day, cost[5], cost[0],
            cost[1], cost[2], cost[3], cost[4], ctx.get("docs_only"), ctx.get("enable_tests"), ctx.get("workflow_files_changed"),
            ctx.get("smart_contracts_changed"), ctx.get("failure_mode"), source, artifact_id, run["html_url"]))
        steps_of = {j["id"]: j["steps"] for j in jobs}
        for j in job_rows:
            conn.execute("INSERT OR REPLACE INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                _job_id(jobs, j), rid, j["name"], j["stage"], j["class"], j["attempt"], j["conclusion"], j["created"],
                j["started"], j["completed"], j["dur"], j["queue"], int(j["redone"])))
        for jid, steps in steps_of.items():
            for i, st in enumerate(steps):
                if conn.execute("SELECT 1 FROM jobs WHERE job_id = ?", (jid,)).fetchone():
                    conn.execute("INSERT OR REPLACE INTO steps VALUES (?,?,?,?,?)", (jid, i, st["name"], st["conclusion"], _step_seconds(st)))
        for t in tests:
            if t["kind"] == "shard":
                conn.execute("INSERT OR REPLACE INTO test_shards VALUES (?,?,?,?,?,?,?,?,?)",
                             (rid, t["shard"], t["classes"], t["tests"], t["failed"], t["skipped"], t["suite_seconds"],
                              t["cached_classes"], t["cached_seconds"]))
            elif t["kind"] == "class":
                conn.execute("INSERT OR REPLACE INTO test_classes VALUES (?,?,?,?,?,?,?)",
                             (rid, t["shard"], t["class"], t["tests"], t["failed"], t["skipped"], t["seconds"]))
            else:
                conn.execute("INSERT OR REPLACE INTO test_cases VALUES (?,?,?,?,?,?)",
                             (rid, t["shard"], t["class"], t["case"], t["seconds"], t["outcome"]))


def _job_id(jobs, job_row):
    """build_rows rows do not carry the job id; recover it from the raw job with the same name, attempt and start."""
    matches = [j["id"] for j in jobs if j["name"] == job_row["name"] and j["run_attempt"] == job_row["attempt"]
               and j["started_at"] == job_row["started"]]
    if len(matches) != 1:
        raise ValueError(f"cannot identify job {job_row['name']!r} attempt {job_row['attempt']} started {job_row['started']}: {len(matches)} matches")
    return matches[0]


def load_document(conn, doc, artifact_id=None):
    if doc["schema"] != SCHEMA:
        raise ValueError(f"unsupported run-metrics schema {doc['schema']}")
    store_run(conn, doc["run"], doc["jobs"], doc["context"], doc["tests"], "artifact", artifact_id)


def backfill(conn, data_dir):
    runs, jobs_by_run = {}, defaultdict(list)
    with open(os.path.join(data_dir, "runs.jsonl")) as f:
        for l in f:
            r = json.loads(l)
            runs[r["id"]] = r
    with open(os.path.join(data_dir, "jobs.jsonl")) as f:
        for l in f:
            j = json.loads(l)
            jobs_by_run[j["run_id"]].append(j)
    with open(os.path.join(data_dir, "empty_runs.txt")) as f:
        empty = {int(x) for x in f.read().split()}
    have = {row[0] for row in conn.execute("SELECT run_id FROM runs WHERE source = 'artifact'")}
    added = 0
    for rid, r in runs.items():
        if r["status"] != "completed" or rid in have:
            continue
        store_run(conn, r, jobs_by_run.get(rid, []), None, [], "api", None)
        added += 1
    return added


def list_artifacts(known):
    """Newest first; stop at the first page whose artifacts are all already stored."""
    out, page = [], 1
    while True:
        data = collect.get(f"{collect.API}/actions/artifacts?name={ARTIFACT_NAME}&per_page=100&page={page}")
        arts = data["artifacts"]
        fresh = [a for a in arts if not a["expired"] and known.get(a["workflow_run"]["id"]) != a["id"]]
        out += fresh
        if not arts or not fresh or len(arts) < 100:
            return out
        page += 1


def read_document(zip_bytes):
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
        names = [n for n in z.namelist() if n.endswith(".json")]
        if len(names) != 1:
            raise ValueError(f"expected one json file in the artifact, found {names}")
        if z.getinfo(names[0]).file_size > 50 * 1024 * 1024:
            raise ValueError("run-metrics artifact is unexpectedly large")
        return json.loads(z.read(names[0]))


def pull(conn, workers=8):
    known = dict(conn.execute("SELECT run_id, artifact_id FROM runs WHERE artifact_id IS NOT NULL"))
    todo = list_artifacts(known)
    print(f"[pull] {len(todo)} new run-metrics artifacts", flush=True)
    done = 0
    with ThreadPoolExecutor(workers) as pool:
        for a, doc in zip(todo, pool.map(lambda a: read_document(tt.download_zip(a["id"])), todo)):
            if doc["run"]["id"] != a["workflow_run"]["id"]:
                raise ValueError(f"artifact {a['id']} belongs to run {a['workflow_run']['id']} but contains run {doc['run']['id']}")
            load_document(conn, doc, a["id"])
            done += 1
            if done % 25 == 0 or done == len(todo):
                print(f"[pull] {done}/{len(todo)}", flush=True)
    return len(todo)


def stats(conn):
    n = {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in
         ("runs", "jobs", "steps", "test_shards", "test_classes", "test_cases")}
    print("rows:", ", ".join(f"{k} {v:,}" for k, v in n.items()))
    lo, hi = conn.execute("SELECT MIN(day), MAX(day) FROM runs").fetchone()
    print(f"days: {lo} to {hi}")
    for src, c in conn.execute("SELECT source, COUNT(*) FROM runs GROUP BY source"):
        print(f"  {src}: {c:,} runs")
    print("weekly runner hours (suite, week, runs, runner_h, wasted_h, queue_h):")
    for row in conn.execute("SELECT * FROM weekly_cost ORDER BY week DESC, suite LIMIT 8"):
        print("  ", row)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("pull")
    b = sub.add_parser("backfill")
    b.add_argument("--data", required=True)
    f = sub.add_parser("file")
    f.add_argument("path")
    sub.add_parser("stats")
    args = ap.parse_args()
    conn = connect(args.db)
    if args.cmd == "pull":
        pull(conn)
    elif args.cmd == "backfill":
        print(f"[backfill] {backfill(conn, args.data)} runs added")
    elif args.cmd == "file":
        with open(args.path) as fh:
            load_document(conn, json.load(fh))
    stats(conn)


if __name__ == "__main__":
    sys.exit(main())
