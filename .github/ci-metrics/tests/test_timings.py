# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import json
import os
import tempfile
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("test_timings", os.path.join(ROOT, "test_timings.py"))
tt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tt)


RUN_START = "2026-11-09T14:00:00Z"


def junit(name, cases, suite_time=None, timestamp="2026-11-09T15:00:00.000Z"):
    body = "".join(f'<testcase name="{n}" classname="{name}" time="{t}">{extra}</testcase>' for n, t, extra in cases)
    total = suite_time if suite_time is not None else sum(c[1] for c in cases)
    failed = sum("<failure" in c[2] or "<error" in c[2] for c in cases)
    skipped = sum("<skipped" in c[2] for c in cases)
    return (f'<?xml version="1.0"?><testsuite name="{name}" tests="{len(cases)}" skipped="{skipped}" failures="{failed}" '
            f'errors="0" time="{total}" timestamp="{timestamp}">{body}<system-out><![CDATA[{"x" * 1000}]]></system-out></testsuite>')


def zipped(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for path, text in files.items():
            z.writestr(path, text)
    return buf.getvalue()


class Parse(unittest.TestCase):
    def test_reads_suites_and_cases(self):
        data = zipped({"a/test-results/TEST-x.Slow.xml": junit("x.Slow", [("one", 30.5, ""), ("two", 1.0, "<failure/>"), ("three", 0.1, "<skipped/>")]),
                       "a/README.txt": "ignored"})
        suites, cases = tt.parse_junit_zip(data)
        self.assertEqual(suites, [("x.Slow", 3, 1, 1, 31.6, "2026-11-09T15:00:00.000Z")])
        self.assertEqual([(c[1], c[3]) for c in cases], [("one", "passed"), ("two", "failed"), ("three", "skipped")])

    def test_rejects_entity_declarations(self):
        evil = '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><testsuite name="x" tests="0" skipped="0" failures="0" errors="0" time="0" timestamp="2026-11-09T15:00:00Z"/>'
        with self.assertRaises(ValueError):
            tt.parse_junit_zip(zipped({"TEST-x.xml": evil}))

    def test_missing_attribute_fails_loudly(self):
        bad = '<testsuite name="x" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-11-09T15:00:00Z"><testcase name="a" classname="x" time="1"/></testsuite>'
        with self.assertRaises(KeyError):
            tt.parse_junit_zip(zipped({"TEST-x.xml": bad}))


class Rows(unittest.TestCase):
    def data(self):
        return zipped({"TEST-a.xml": junit("a.Big", [("t", 100.0, "")]), "TEST-b.xml": junit("b.Tiny", [("t", 0.5, "")])})

    def test_small_classes_are_dropped_but_counted_in_shard_row(self):
        rows = tt.rows_for_artifact(1, "2026-11-09", "sha", "HAPI Test (X) Reports", self.data(), RUN_START)
        kinds = [r["kind"] for r in rows]
        self.assertEqual(kinds.count("shard"), 1)
        self.assertEqual([r["class"] for r in rows if r["kind"] == "class"], ["a.Big"])
        shard = next(r for r in rows if r["kind"] == "shard")
        self.assertEqual((shard["classes"], shard["tests"]), (2, 2))
        self.assertAlmostEqual(shard["suite_seconds"], 100.5)

    def test_merge_replaces_same_run_and_shard_and_keeps_others(self):
        with tempfile.TemporaryDirectory() as d:
            path = f"{d}/t.jsonl"
            tt.merge(path, tt.rows_for_artifact(1, "2026-11-08", "s", "S1", self.data(), RUN_START))
            tt.merge(path, tt.rows_for_artifact(2, "2026-11-09", "s", "S1", self.data(), RUN_START))
            n = tt.merge(path, tt.rows_for_artifact(2, "2026-11-09", "s", "S1", self.data(), RUN_START))
            with open(path) as f:
                rows = [json.loads(l) for l in f]
            self.assertEqual(n, len(rows))
            self.assertEqual({r["run_id"] for r in rows}, {1, 2})
            self.assertEqual(sum(r["kind"] == "shard" and r["run_id"] == 2 for r in rows), 1)


class Cached(unittest.TestCase):
    def test_results_older_than_the_run_are_cached_and_dropped(self):
        data = zipped({"TEST-a.xml": junit("a.Live", [("t", 50.0, "")], timestamp="2026-11-09T15:00:00Z"),
                       "TEST-b.xml": junit("b.Restored", [("t", 90.0, "")], timestamp="2026-11-08T09:00:00Z")})
        rows = tt.rows_for_artifact(1, "2026-11-09", "s", "S1", data, RUN_START)
        shard = next(r for r in rows if r["kind"] == "shard")
        self.assertEqual((shard["classes"], shard["cached_classes"]), (2, 1))
        self.assertAlmostEqual(shard["suite_seconds"], 50)
        self.assertAlmostEqual(shard["cached_seconds"], 90)
        self.assertEqual([r["class"] for r in rows if r["kind"] == "class"], ["a.Live"])
        self.assertEqual([r["class"] for r in rows if r["kind"] == "case"], ["a.Live"])

    def test_fully_cached_shard_is_left_out_of_the_means(self):
        live = zipped({"TEST-a.xml": junit("a.A", [("t", 60.0, "")])})
        restored = zipped({"TEST-a.xml": junit("a.A", [("t", 60.0, "")], timestamp="2026-11-01T00:00:00Z")})
        rows = tt.rows_for_artifact(1, "2026-11-09", "s", "S1", live, RUN_START) + tt.rows_for_artifact(2, "2026-11-09", "s", "S1", restored, RUN_START)
        sh = tt.summarize(rows)["shards"][0]
        self.assertEqual((sh["runs"], sh["cached_runs"]), (1, 1))
        self.assertAlmostEqual(sh["classes"][0]["mean_s"], 60)


class Sampling(unittest.TestCase):
    def mk(self, rid, day, repo="o/r", event="pull_request", conclusion="success", attempt=1):
        return {"id": rid, "status": "completed", "conclusion": conclusion, "run_attempt": attempt, "event": event,
                "created_at": f"{day}T10:00:00Z", "head_repository": {"full_name": repo}, "head_sha": "s"}

    def test_skips_forks_failures_reruns_and_other_events(self):
        runs = [self.mk(1, "2026-11-09", repo="fork/r"), self.mk(2, "2026-11-09", conclusion="failure"),
                self.mk(3, "2026-11-09", attempt=2), self.mk(4, "2026-11-09", event="push"), self.mk(5, "2026-11-09")]
        self.assertEqual([r["id"] for r in tt.sample_runs(runs, "o/r", 2)], [5])

    def test_spreads_picks_across_the_day(self):
        runs = [self.mk(i, "2026-11-09") for i in range(1, 11)]
        self.assertEqual(sorted(r["id"] for r in tt.sample_runs(runs, "o/r", 2)), [1, 10])


class Summary(unittest.TestCase):
    def test_share_and_ranking(self):
        rows = []
        for run_id in (1, 2):
            rows += tt.rows_for_artifact(run_id, "2026-11-09", "s", "S1", zipped({"TEST-a.xml": junit("a.Big", [("t", 60.0, "")]), "TEST-b.xml": junit("b.Mid", [("t", 40.0, "")])}), RUN_START)
        s = tt.summarize(rows)
        sh = s["shards"][0]
        self.assertEqual(sh["runs"], 2)
        self.assertEqual(sh["classes"][0]["class"], "a.Big")
        self.assertAlmostEqual(sh["classes"][0]["share"], 0.6)
        self.assertEqual(s["cases"][0]["case"], "t")


if __name__ == "__main__":
    unittest.main()
