# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import os
import re
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

    def test_doctype_after_a_long_prolog_is_still_rejected(self):
        padding = "<!-- " + "x" * 10_000 + " -->"
        evil = ('<?xml version="1.0"?>' + padding + '<!DOCTYPE x [<!ENTITY a "aaaa">]><testsuite name="x" tests="0" skipped="0" '
                'failures="0" errors="0" time="0" timestamp="2026-11-09T15:00:00Z"/>')
        with self.assertRaises(ValueError):
            tt.parse_junit_zip(zipped({"TEST-x.xml": evil}))

    def test_a_declaration_split_across_read_chunks_is_still_found(self):
        padding = "<!-- " + "x" * ((1 << 20) - 40) + " -->"
        evil = ('<?xml version="1.0"?>' + padding + '<!DOCTYPE x><testsuite name="x" tests="0" skipped="0" failures="0" '
                'errors="0" time="0" timestamp="2026-11-09T15:00:00Z"/>')
        with self.assertRaises(ValueError):
            tt.parse_junit_zip(zipped({"TEST-x.xml": evil}))

    def test_utf16_reports_are_refused(self):
        xml = junit("a.A", [("t", 1.0, "")]).encode("utf-16")
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("TEST-x.xml", xml)
        with self.assertRaisesRegex(ValueError, "UTF-8"):
            tt.parse_junit_zip(buf.getvalue())

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


class Artifacts(unittest.TestCase):
    """The API calls are replaced so the paging and the error handling can be checked."""

    def setUp(self):
        self.real = (tt.collect.get, tt.download_zip)
        self.addCleanup(lambda: (setattr(tt.collect, "get", self.real[0]), setattr(tt, "download_zip", self.real[1])))

    def test_artifact_list_is_paginated_and_newest_wins(self):
        pages = {1: [{"id": i, "name": f"HAPI Test (S{i % 3}) Reports", "expired": False} for i in range(1, 101)],
                 2: [{"id": 500, "name": "HAPI Test (S1) Reports", "expired": False},
                     {"id": 501, "name": "other-artifact", "expired": False},
                     {"id": 502, "name": "HAPI Test (S9) Reports", "expired": True}]}
        tt.collect.get = lambda url: {"artifacts": pages[int(re.search(r"&page=(\d+)", url).group(1))], "total_count": 103}
        got = {a["name"]: a["id"] for a in tt.shard_artifacts(1)}
        self.assertEqual(got, {"HAPI Test (S1) Reports": 500, "HAPI Test (S0) Reports": 99, "HAPI Test (S2) Reports": 98})

    def test_one_unreadable_report_is_recorded_and_the_rest_are_kept(self):
        good = zipped({"TEST-a.xml": junit("a.Big", [("t", 60.0, "")])})
        tt.collect.get = lambda url: {"artifacts": [{"id": 1, "name": "HAPI Test (Good) Reports", "expired": False},
                                                    {"id": 2, "name": "HAPI Test (Broken) Reports", "expired": False},
                                                    {"id": 3, "name": "HAPI Test (Gone) Reports", "expired": False}], "total_count": 3}

        def download(artifact_id):
            if artifact_id == 3:
                raise RuntimeError("GET artifact -> 410")
            return good if artifact_id == 1 else zipped({"TEST-b.xml": "<testsuite"})
        tt.download_zip = download
        rows, errors = tt.test_rows_for_run({"id": 9, "created_at": RUN_START, "head_sha": "s"})
        self.assertEqual({r["shard"] for r in rows}, {"HAPI Test (Good) Reports"})
        self.assertEqual(sorted(e["artifact"] for e in errors), ["HAPI Test (Broken) Reports", "HAPI Test (Gone) Reports"])
        self.assertIn("RuntimeError", errors[1]["error"])


if __name__ == "__main__":
    unittest.main()
