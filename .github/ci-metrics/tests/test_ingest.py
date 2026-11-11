# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import json
import os
import re
import sqlite3
import tempfile
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ingest = load("ingest")
run_metrics = load("run_metrics")


def raw_run(rid=1, created="2026-11-09T10:00:00Z"):
    return {"id": rid, "name": "600: PR Checks", "event": "pull_request", "head_branch": "b", "head_sha": "abc", "status": "in_progress",
            "conclusion": None, "run_attempt": 1, "created_at": created, "run_started_at": created, "updated_at": created,
            "html_url": f"https://x/{rid}", "display_title": "PR #7 labeled: title", "workflow_id": 291484377,
            "workflow": "600: PR Checks", "suite": "MATS", "pr_numbers": [7]}


def raw_job(jid, name, start, end, conclusion="success", status="completed", runner="r1", created=None, attempt=1, steps=None):
    return {"run_id": 1, "id": jid, "name": name, "status": status, "conclusion": conclusion, "run_attempt": attempt,
            "created_at": created or start, "started_at": start, "completed_at": end, "runner_name": runner,
            "runner_group_name": "g", "labels": ["hl-cn-hapi-lin-lg"], "steps": steps or []}


def jobs():
    steps = [{"name": "Prepare Runner", "conclusion": "success", "started_at": "2026-11-09T10:01:00Z", "completed_at": "2026-11-09T10:01:20Z"},
             {"name": "HAPI Testing", "conclusion": "success", "started_at": "2026-11-09T10:01:20Z", "completed_at": "2026-11-09T10:11:00Z"}]
    return [raw_job(10, "MATS / HAPI Tests / a / HAPI Tests (Misc)", "2026-11-09T10:01:00Z", "2026-11-09T10:11:00Z", created="2026-11-09T10:00:30Z", steps=steps),
            raw_job(11, "CI Complete", "2026-11-09T10:11:05Z", "2026-11-09T10:11:10Z", runner="r2"),
            raw_job(12, "Publish Run Metrics", "2026-11-09T10:11:12Z", None, conclusion=None, status="in_progress", runner="r3")]


def tests(extra_class=False):
    rows = [{"kind": "shard", "run_id": 1, "day": "2026-11-09", "sha": "abc", "shard": "S1", "classes": 2, "tests": 3, "failed": 0,
             "skipped": 0, "suite_seconds": 120.0, "cached_classes": 0, "cached_seconds": 0.0},
            {"kind": "class", "run_id": 1, "day": "2026-11-09", "sha": "abc", "shard": "S1", "class": "a.Big", "tests": 2, "failed": 0,
             "skipped": 0, "seconds": 100.0},
            {"kind": "case", "run_id": 1, "day": "2026-11-09", "sha": "abc", "shard": "S1", "class": "a.Big", "case": "t1", "seconds": 90.0,
             "outcome": "passed"}]
    return rows


class RunMetrics(unittest.TestCase):
    def test_in_progress_job_is_dropped_and_conclusion_comes_from_ci_complete(self):
        doc = run_metrics.build_document(raw_run(), jobs(), {"action": "labeled"}, tests())
        self.assertEqual([j["name"] for j in doc["jobs"]], ["MATS / HAPI Tests / a / HAPI Tests (Misc)", "CI Complete"])
        self.assertEqual((doc["run"]["status"], doc["run"]["conclusion"]), ("completed", "success"))
        self.assertEqual(doc["schema"], 1)

    def test_conclusion_without_ci_complete_uses_job_failures(self):
        js = [raw_job(1, "x", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z", conclusion="failure")]
        self.assertEqual(run_metrics.derive_conclusion(js), "failure")
        self.assertEqual(run_metrics.derive_conclusion([]), "success")

    def test_context_comes_from_ctx_variables_only(self):
        env = {"CTX_DOCS_ONLY": "false", "CTX_ACTION": "opened", "PATH": "/bin", "GH_TOKEN": "secret"}
        self.assertEqual(run_metrics.context_from_env(env), {"action": "opened", "docs_only": "false"})


class Ingest(unittest.TestCase):
    def conn(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        return ingest.connect(os.path.join(self.tmp.name, "ci.sqlite"))

    def doc(self, **kw):
        return run_metrics.build_document(raw_run(**kw), jobs(), {"docs_only": "false", "failure_mode": "none"}, tests())

    def test_run_jobs_steps_and_tests_are_stored(self):
        c = self.conn()
        ingest.load_document(c, self.doc(), artifact_id=99)
        run = c.execute("SELECT suite, action, pr, conclusion, runner_s, wall_s, docs_only, failure_mode, source, artifact_id FROM runs").fetchone()
        self.assertEqual(run[:4], ("MATS", "labeled", 7, "success"))
        self.assertAlmostEqual(run[4], 600 + 5)
        self.assertEqual(run[6:], ("false", "none", "artifact", 99))
        self.assertEqual(c.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 2)
        self.assertEqual(c.execute("SELECT name, seconds FROM steps WHERE job_id = 10 ORDER BY idx").fetchall(), [("Prepare Runner", 20.0), ("HAPI Testing", 580.0)])
        self.assertEqual(c.execute("SELECT shard, cached_classes FROM test_shards").fetchall(), [("S1", 0)])
        self.assertEqual(c.execute("SELECT class, seconds FROM test_classes").fetchall(), [("a.Big", 100.0)])
        self.assertEqual(c.execute("SELECT test FROM test_cases").fetchall(), [("t1",)])

    def test_loading_twice_replaces_instead_of_duplicating(self):
        c = self.conn()
        ingest.load_document(c, self.doc(), artifact_id=1)
        ingest.load_document(c, self.doc(), artifact_id=2)
        self.assertEqual([c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("runs", "jobs", "steps", "test_classes")], [1, 2, 2, 1])
        self.assertEqual(c.execute("SELECT artifact_id FROM runs").fetchone()[0], 2)

    def test_run_with_only_skipped_jobs_is_still_recorded_at_zero_cost(self):
        c = self.conn()
        doc = run_metrics.build_document(raw_run(), [raw_job(1, "skipped", "2026-11-09T10:00:00Z", "2026-11-09T10:00:00Z", conclusion="skipped", runner=None)], {}, [])
        ingest.load_document(c, doc)
        self.assertEqual(c.execute("SELECT runner_s, wall_s FROM runs").fetchone(), (0.0, 0.0))

    def test_unknown_schema_is_rejected(self):
        doc = self.doc()
        doc["schema"] = 2
        with self.assertRaises(ValueError):
            ingest.load_document(self.conn(), doc)

    def test_backfill_adds_api_runs_but_never_overwrites_artifact_runs(self):
        c = self.conn()
        ingest.load_document(c, self.doc(), artifact_id=5)
        with tempfile.TemporaryDirectory() as d:
            completed = dict(raw_run(1), status="completed", conclusion="failure")
            other = dict(raw_run(2, created="2026-11-08T10:00:00Z"), status="completed", conclusion="success")
            other_jobs = [dict(j, run_id=2, id=j["id"] + 100) for j in jobs()[:2]]
            with open(f"{d}/runs.jsonl", "w") as f:
                f.writelines(json.dumps(r) + "\n" for r in (completed, other))
            with open(f"{d}/jobs.jsonl", "w") as f:
                f.writelines(json.dumps(j) + "\n" for j in jobs()[:2] + other_jobs)
            open(f"{d}/empty_runs.txt", "w").close()
            self.assertEqual(ingest.backfill(c, d), 1)
        self.assertEqual(c.execute("SELECT run_id, source, conclusion FROM runs ORDER BY run_id").fetchall(),
                         [(1, "artifact", "success"), (2, "api", "success")])

    def test_read_document_wants_exactly_one_json_file(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("a.json", "{}")
            z.writestr("b.json", "{}")
        with self.assertRaises(ValueError):
            ingest.read_document(buf.getvalue())

    def test_list_artifacts_stops_at_a_page_that_is_already_stored(self):
        pages = {1: [{"id": i, "expired": False, "workflow_run": {"id": 1000 + i}} for i in range(100, 0, -1)],
                 2: [{"id": i, "expired": False, "workflow_run": {"id": 1000 + i}} for i in range(200, 100, -1)]}
        calls = []

        def fake_get(url):
            page = int(re.search(r"&page=(\d+)", url).group(1))
            calls.append(page)
            return {"artifacts": pages.get(page, [])}
        original = ingest.collect.get
        ingest.collect.get = fake_get
        try:
            known = {1000 + i: i for i in range(1, 101)}  # everything on page 1 is already stored
            self.assertEqual(ingest.list_artifacts(known), [])
            self.assertEqual(calls, [1])
            calls.clear()
            self.assertEqual(len(ingest.list_artifacts({})), 200)  # full fresh pages keep paging until an empty one
        finally:
            ingest.collect.get = original


if __name__ == "__main__":
    unittest.main()
