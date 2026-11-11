# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


analyze = load("analyze")
report = load("report")


def job(jid, run_id, name, created, started, completed, conclusion="success", attempt=1, runner="r1",
        label="hl-cn-hapi-lin-lg"):
    return {"run_id": run_id, "id": jid, "name": name, "status": "completed", "conclusion": conclusion,
            "run_attempt": attempt, "created_at": created, "started_at": started, "completed_at": completed,
            "runner_name": runner, "runner_group_name": "g", "labels": [label], "steps": []}


def run(rid, suite="MATS", wf_id=291484377, conclusion="success", attempt=1, created="2026-10-05T10:00:00Z"):
    wf = {"MATS": "600: PR Checks", "XTS": "226: XTS Controller"}[suite]
    return {"id": rid, "name": wf, "event": "pull_request", "head_branch": "b", "head_sha": "s", "status": "completed",
            "conclusion": conclusion, "run_attempt": attempt, "created_at": created, "run_started_at": created,
            "updated_at": created, "html_url": f"https://x/{rid}", "actor_login": "a", "workflow_id": wf_id,
            "workflow": wf, "suite": suite, "pr_numbers": [1]}


def run_history_only(runs, jobs, hist_path):
    with tempfile.TemporaryDirectory() as d:
        with open(f"{d}/runs.jsonl", "w") as f:
            f.writelines(json.dumps(r) + "\n" for r in runs)
        with open(f"{d}/jobs.jsonl", "w") as f:
            f.writelines(json.dumps(j) + "\n" for j in jobs)
        open(f"{d}/empty_runs.txt", "w").close()
        subprocess.run([sys.executable, "-I", os.path.join(ROOT, "analyze.py"), "--data", d, "--history", hist_path,
                        "--history-only"], check=True, capture_output=True, text=True)
        with open(hist_path) as f:
            return [json.loads(l) for l in f]


def run_analyze(runs, jobs, empty=()):
    with tempfile.TemporaryDirectory() as d:
        with open(f"{d}/runs.jsonl", "w") as f:
            f.writelines(json.dumps(r) + "\n" for r in runs)
        with open(f"{d}/jobs.jsonl", "w") as f:
            f.writelines(json.dumps(j) + "\n" for j in jobs)
        with open(f"{d}/empty_runs.txt", "w") as f:
            f.write("\n".join(str(e) for e in empty))
        out = f"{d}/m.json"
        hist = f"{d}/h.jsonl"
        subprocess.run([sys.executable, "-I", os.path.join(ROOT, "analyze.py"), "--data", d, "--out", out,
                        "--history", hist], check=True, capture_output=True, text=True)
        with open(out) as f:
            m = json.load(f)
        with open(hist) as f:
            h = [json.loads(l) for l in f]
        return m, h


# a minimal data set needs both suites present because analyze reports both
def both_suites(mats_jobs, mats_run=None, extra_runs=(), xts_day="2026-10-05"):
    xr = run(900, "XTS", 382618421, created=f"{xts_day}T10:00:00Z")
    xj = [job(9000, 900, "Execute XTS / XTS HAPI Tests / a", f"{xts_day}T10:00:00Z", f"{xts_day}T10:00:10Z", f"{xts_day}T10:30:10Z")]
    return [mats_run or run(1)] + [xr] + list(extra_runs), mats_jobs + xj


class Helpers(unittest.TestCase):
    def test_percentile(self):
        self.assertEqual(analyze.pct([1, 2, 3, 4, 5], .5), 3)
        self.assertAlmostEqual(analyze.pct([0, 10], .9), 9)
        with self.assertRaises(ValueError):
            analyze.pct([], .5)

    def test_stage_groups_matrix_legs(self):
        self.assertEqual(analyze.stage_of("MATS / HAPI Tests / hapi-tests-misc / HAPI Tests (Misc)"), "MATS / HAPI Tests")
        self.assertEqual(analyze.stage_of("Detect Change Type"), "Detect Change Type")

    def test_runner_class_raises_without_labels(self):
        with self.assertRaises(ValueError):
            analyze.runner_class({"id": 1, "name": "x", "labels": []})


class Metrics(unittest.TestCase):
    def test_runner_and_queue_time(self):
        jobs = [job(1, 1, "MATS / HAPI Tests / a", "2026-10-05T10:00:00Z", "2026-10-05T10:01:00Z", "2026-10-05T10:11:00Z")]
        runs, js = both_suites(jobs)
        m, _ = run_analyze(runs, js)
        s = m["suites"]["MATS"]["summary"]
        self.assertAlmostEqual(s["runner_min"], 10)
        self.assertAlmostEqual(s["queue_min"], 1)
        self.assertAlmostEqual(s["wall_p50_min"], 11)

    def test_carried_over_jobs_are_not_counted_twice(self):
        # attempt 2 copies the passed job with its original timestamps but a later created_at
        j1 = job(1, 1, "MATS / Unit Tests / u", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:10:10Z")
        j2 = job(2, 1, "MATS / Unit Tests / u", "2026-10-06T09:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:10:10Z", attempt=2)
        runs, js = both_suites([j1, j2], run(1, attempt=2))
        m, _ = run_analyze(runs, js)
        self.assertAlmostEqual(m["suites"]["MATS"]["summary"]["runner_min"], 10)

    def test_real_rerun_counts_and_is_wasted(self):
        j1 = job(1, 1, "MATS / HAPI Tests / a", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:10:10Z", conclusion="failure")
        j2 = job(2, 1, "MATS / HAPI Tests / a", "2026-10-05T11:00:00Z", "2026-10-05T11:00:10Z", "2026-10-05T11:10:10Z", attempt=2)
        runs, js = both_suites([j1, j2], run(1, attempt=2))
        m, _ = run_analyze(runs, js)
        w = m["suites"]["MATS"]["waste"]
        self.assertAlmostEqual(m["suites"]["MATS"]["summary"]["runner_min"], 20)
        self.assertAlmostEqual(w["failed_min"], 10)
        self.assertAlmostEqual(w["wasted_min"], 10)

    def test_job_without_runner_is_not_runner_time(self):
        j = job(1, 1, "MATS / Unit Tests / u", "2026-10-05T10:00:00Z", "2026-10-05T10:00:00Z", "2026-10-06T10:00:00Z",
                conclusion="cancelled", runner="")
        ok = job(2, 1, "MATS / HAPI Tests / a", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:05:10Z")
        runs, js = both_suites([j, ok])
        m, _ = run_analyze(runs, js)
        w = m["suites"]["MATS"]["waste"]
        self.assertAlmostEqual(m["suites"]["MATS"]["summary"]["runner_min"], 5)
        self.assertEqual(w["never_started_jobs"], 1)
        self.assertAlmostEqual(w["never_started_queue_h"], 24)

    def test_short_queue_cancel_is_not_stuck(self):
        j = job(1, 1, "MATS / Unit Tests / u", "2026-10-05T10:00:00Z", "2026-10-05T10:00:00Z", "2026-10-05T10:05:00Z",
                conclusion="cancelled", runner="")
        ok = job(2, 1, "MATS / HAPI Tests / a", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:05:10Z")
        runs, js = both_suites([j, ok])
        m, _ = run_analyze(runs, js)
        w = m["suites"]["MATS"]["waste"]
        self.assertEqual(w["never_started_jobs"], 0)
        self.assertEqual(w["cancelled_while_queued_jobs"], 1)

    def test_critical_path_walks_back_through_serial_chain(self):
        c = job(1, 1, "Compile", "2026-10-05T10:00:00Z", "2026-10-05T10:00:00Z", "2026-10-05T10:05:00Z")
        t = job(2, 1, "MATS / HAPI Tests / a", "2026-10-05T10:05:00Z", "2026-10-05T10:05:00Z", "2026-10-05T10:35:00Z")
        quick = job(3, 1, "MATS / Unit Tests / u", "2026-10-05T10:05:00Z", "2026-10-05T10:05:00Z", "2026-10-05T10:10:00Z")
        runs, js = both_suites([c, t, quick])
        m, _ = run_analyze(runs, js)
        cp = {x["stage"]: x for x in m["suites"]["MATS"]["critical_path"]}
        self.assertIn("MATS / HAPI Tests", cp)
        self.assertIn("Compile", cp)
        self.assertNotIn("MATS / Unit Tests", cp)
        self.assertEqual(cp["MATS / HAPI Tests"]["share_of_runs"], 1.0)

    def test_stage_counted_once_per_run_in_critical_path_share(self):
        # two serial jobs of the same stage on one path must not push the share above 100%
        a = job(1, 1, "MATS / HAPI Tests / a", "2026-10-05T10:00:00Z", "2026-10-05T10:00:00Z", "2026-10-05T10:10:00Z")
        b = job(2, 1, "MATS / HAPI Tests / b", "2026-10-05T10:10:00Z", "2026-10-05T10:10:00Z", "2026-10-05T10:20:00Z")
        runs, js = both_suites([a, b])
        m, _ = run_analyze(runs, js)
        self.assertLessEqual(m["suites"]["MATS"]["critical_path"][0]["share_of_runs"], 1.0)

    def test_completed_run_without_jobs_raises(self):
        runs, js = both_suites([])
        # run 1 completed but has no jobs and is not recorded as empty
        with self.assertRaises(subprocess.CalledProcessError):
            run_analyze(runs, js)

    def test_history_is_written_and_idempotent(self):
        jobs = [job(1, 1, "MATS / HAPI Tests / a", "2026-10-05T10:00:00Z", "2026-10-05T10:01:00Z", "2026-10-05T10:11:00Z")]
        runs, js = both_suites(jobs)
        _, h = run_analyze(runs, js)
        self.assertEqual({r["id"] for r in h}, {1, 900})
        self.assertEqual(h[0]["stages"][0]["stage"], "MATS / HAPI Tests")


class GapsAndEras(unittest.TestCase):
    def _mats(self, rid, created, sha="s1", pr=1):
        r = run(rid, created=created)
        r["head_sha"] = sha
        r["pr_numbers"] = [pr]
        t = datetime.fromisoformat(created.replace("Z", "+00:00"))
        fmt = lambda d: d.strftime("%Y-%m-%dT%H:%M:%SZ")
        j = job(rid * 10, rid, "MATS / HAPI Tests / a", created, fmt(t + timedelta(seconds=10)), fmt(t + timedelta(minutes=10, seconds=10)))
        return r, j

    def test_gap_of_three_or_more_days_is_reported(self):
        a, aj = self._mats(1, "2026-10-01T10:00:00Z")
        b, bj = self._mats(2, "2026-10-06T10:00:00Z")
        runs, js = both_suites([aj, bj], a, extra_runs=[b], xts_day="2026-10-01")
        m, _ = run_analyze(runs, js)
        self.assertEqual(m["gaps"], [{"start": "2026-10-02", "end": "2026-10-05", "days": 4}])

    def test_era_compares_before_and_after_the_gap(self):
        a, aj = self._mats(1, "2026-10-01T10:00:00Z")
        b, bj = self._mats(2, "2026-10-06T10:00:00Z")
        runs, js = both_suites([aj, bj], a, extra_runs=[b], xts_day="2026-10-01")
        m, _ = run_analyze(runs, js)
        era = m["suites"]["MATS"]["era"]
        self.assertEqual(era["split"], "2026-10-06")
        self.assertEqual((era["pre_runs"], era["post_runs"]), (1, 1))

    def test_no_gap_means_no_era(self):
        a, aj = self._mats(1, "2026-10-01T10:00:00Z")
        runs, js = both_suites([aj], a, xts_day="2026-10-01")
        m, _ = run_analyze(runs, js)
        self.assertEqual(m["gaps"], [])
        self.assertIsNone(m["suites"]["MATS"]["era"])

    def test_repeat_run_on_same_commit_is_counted(self):
        a, aj = self._mats(1, "2026-10-01T10:00:00Z", sha="same")
        b, bj = self._mats(2, "2026-10-01T11:00:00Z", sha="same")
        c, cj = self._mats(3, "2026-10-01T12:00:00Z", sha="new")
        runs, js = both_suites([aj, bj, cj], a, extra_runs=[b, c], xts_day="2026-10-01")
        m, _ = run_analyze(runs, js)
        self.assertEqual(m["suites"]["MATS"]["retrigger"]["runs"], 1)


class History(unittest.TestCase):
    def test_history_only_works_when_a_suite_has_no_runs(self):
        j = job(1, 1, "MATS / HAPI Tests / hapi-tests-clpr / HAPI Tests (CLPR)", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:15:10Z")
        with tempfile.TemporaryDirectory() as d:
            h = run_history_only([run(1)], [j], f"{d}/h.jsonl")
        self.assertEqual([r["id"] for r in h], [1])
        self.assertEqual(h[0]["shards"][0]["leg"], "HAPI Tests (CLPR)")
        self.assertAlmostEqual(h[0]["shards"][0]["min"], 15)

    def test_history_replaces_rows_for_the_same_run_and_keeps_older_ones(self):
        with tempfile.TemporaryDirectory() as d:
            path = f"{d}/h.jsonl"
            j1 = job(1, 1, "MATS / Unit Tests / u", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:05:10Z")
            run_history_only([run(1)], [j1], path)
            j2 = job(2, 2, "MATS / Unit Tests / u", "2026-10-06T10:00:00Z", "2026-10-06T10:00:10Z", "2026-10-06T10:05:10Z")
            h = run_history_only([run(2, created="2026-10-06T10:00:00Z")], [j2], path)
            self.assertEqual([r["id"] for r in h], [1, 2])
            j2b = job(2, 2, "MATS / Unit Tests / u", "2026-10-06T10:00:00Z", "2026-10-06T10:00:10Z", "2026-10-06T10:10:10Z")
            h = run_history_only([run(2, created="2026-10-06T10:00:00Z")], [j2b], path)
            self.assertEqual(len(h), 2)
            self.assertAlmostEqual(h[1]["runner"], 600)

    def test_history_only_requires_history_path(self):
        with self.assertRaises(subprocess.CalledProcessError):
            subprocess.run([sys.executable, "-I", os.path.join(ROOT, "analyze.py"), "--data", "x", "--history-only"],
                           check=True, capture_output=True)


class PrAction(unittest.TestCase):
    def test_parses_action_from_run_title(self):
        self.assertEqual(analyze.pr_action("PR #27556 labeled: feat: Make x configurable"), "labeled")
        self.assertEqual(analyze.pr_action("PR #1 synchronize: t"), "synchronize")

    def test_old_titles_have_no_action(self):
        self.assertIsNone(analyze.pr_action("feat: Make lowTpsTargetRounds configurable"))
        self.assertIsNone(analyze.pr_action(None))

    def test_action_reaches_history_and_retrigger_counts(self):
        a = run(1); a["display_title"] = "PR #1 opened: t"
        b = run(2, created="2026-10-05T11:00:00Z"); b["display_title"] = "PR #1 labeled: t"
        for r in (a, b):
            r["head_sha"] = "same"; r["pr_numbers"] = [1]
        jobs = [job(10, 1, "MATS / Unit Tests / u", "2026-10-05T10:00:00Z", "2026-10-05T10:00:10Z", "2026-10-05T10:05:10Z"),
                job(20, 2, "MATS / Unit Tests / u", "2026-10-05T11:00:00Z", "2026-10-05T11:00:10Z", "2026-10-05T11:05:10Z")]
        runs, js = both_suites(jobs, a, extra_runs=[b])
        m, h = run_analyze(runs, js)
        self.assertEqual({r["id"]: r["action"] for r in h if r["suite"] == "MATS"}, {1: "opened", 2: "labeled"})
        self.assertEqual(m["suites"]["MATS"]["retrigger"]["by_action"], {"labeled": 1, "opened": 1})


class Markdown(unittest.TestCase):
    def test_escapes_html(self):
        self.assertIn("&lt;script&gt;", report.md_to_html("a <script> b"))

    def test_table_and_lists(self):
        out = report.md_to_html("| a | b |\n|---|---|\n| 1 | **2** |\n\n- x\n- y\n\n1. p\n2. q")
        self.assertIn("<th>a</th>", out)
        self.assertIn("<b>2</b>", out)
        self.assertEqual(out.count("<li>"), 4)


if __name__ == "__main__":
    unittest.main()
