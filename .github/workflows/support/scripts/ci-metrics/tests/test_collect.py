# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import os
import unittest
import urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("collect", os.path.join(ROOT, "collect.py"))
collect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collect)


def raw(path, number=1):
    return {"id": 5, "path": path, "workflow_id": 99, "event": "push", "actor": {"login": "a"}, "pull_requests": [{"number": number}],
            "status": "completed", "conclusion": "success"}


class Workflows(unittest.TestCase):
    def test_runs_are_identified_by_workflow_file_and_get_a_suite(self):
        cases = {".github/workflows/600-flow-pull-request-checks.yaml": ("600: PR Checks", "MATS"),
                 ".github/workflows/300-flow-build-application.yaml": ("300: Build Application", "MATS"),
                 ".github/workflows/226-disp-citr-xts-controller.yaml": ("226: XTS Controller", "XTS"),
                 ".github/workflows/600-flow-pull-request-checks.yaml@refs/heads/main": ("600: PR Checks", "MATS")}
        for path, (name, suite) in cases.items():
            run = collect.slim_run(raw(path))
            self.assertEqual((run["workflow"], run["suite"], run["workflow_id"]), (name, suite, 99), path)

    def test_an_unknown_workflow_file_raises(self):
        with self.assertRaisesRegex(KeyError, "not in collect.WORKFLOWS"):
            collect.slim_run(raw(".github/workflows/999-other.yaml"))

    def test_cron_shells_are_not_instrumented(self):
        for f in ("900-cron-extended-test-suite.yaml", "901-cron-promote-build-candidate.yaml", "227-disp-daily-controllers.yaml"):
            self.assertNotIn(f, collect.WORKFLOWS)


class Get(unittest.TestCase):
    def setUp(self):
        self.urlopen, self.sleep, self.token = collect.urllib.request.urlopen, collect.time.sleep, collect._token
        collect.time.sleep = lambda s: None
        collect._token = "t"
        self.addCleanup(lambda: (setattr(collect.urllib.request, "urlopen", self.urlopen), setattr(collect.time, "sleep", self.sleep), setattr(collect, "_token", self.token)))

    def http_error(self, code, headers=None):
        return urllib.error.HTTPError("u", code, "m", headers or {}, io.BytesIO(b"{}"))

    def test_a_transient_server_error_is_retried(self):
        calls = []

        def fake(req, timeout):
            calls.append(1)
            if len(calls) < 3:
                raise self.http_error(502)
            return io.BytesIO(b'{"ok": true}')
        collect.urllib.request.urlopen = fake
        self.assertEqual(collect.get("https://x"), {"ok": True})
        self.assertEqual(len(calls), 3)

    def test_retries_are_bounded(self):
        collect.urllib.request.urlopen = lambda req, timeout: (_ for _ in ()).throw(self.http_error(503))
        with self.assertRaisesRegex(RuntimeError, "503"):
            collect.get("https://x")

    def test_a_rate_limit_raises_instead_of_waiting(self):
        headers = {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1794512238"}
        collect.urllib.request.urlopen = lambda req, timeout: (_ for _ in ()).throw(self.http_error(403, headers))
        with self.assertRaisesRegex(RuntimeError, "rate limit"):
            collect.get("https://x")

    def test_other_errors_raise_immediately(self):
        calls = []

        def fake(req, timeout):
            calls.append(1)
            raise self.http_error(404)
        collect.urllib.request.urlopen = fake
        with self.assertRaisesRegex(RuntimeError, "404"):
            collect.get("https://x")
        self.assertEqual(len(calls), 1)


if __name__ == "__main__":
    unittest.main()
