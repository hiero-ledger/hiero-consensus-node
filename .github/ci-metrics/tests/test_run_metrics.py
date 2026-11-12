# SPDX-License-Identifier: Apache-2.0
import importlib.util
import os
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("run_metrics", os.path.join(ROOT, "run_metrics.py"))
run_metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run_metrics)


def raw_run():
    return {"id": 1, "name": "600: PR Checks", "event": "pull_request", "head_sha": "abc", "status": "in_progress",
            "conclusion": None, "run_attempt": 1, "created_at": "2026-11-09T10:00:00Z", "html_url": "https://x/1",
            "display_title": "PR #7 labeled: title", "workflow_id": 291484377, "workflow": "600: PR Checks", "suite": "MATS",
            "pr_numbers": [7]}


def job(jid, name, start, end, conclusion="success", status="completed"):
    return {"run_id": 1, "id": jid, "name": name, "status": status, "conclusion": conclusion, "run_attempt": 1,
            "created_at": start, "started_at": start, "completed_at": end, "runner_name": "r1",
            "runner_group_name": "g", "labels": ["hl-cn-hapi-lin-lg"], "steps": []}


class RunMetrics(unittest.TestCase):
    def test_in_progress_job_is_dropped_and_conclusion_comes_from_ci_complete(self):
        jobs = [job(10, "MATS / HAPI Tests / a", "2026-11-09T10:01:00Z", "2026-11-09T10:11:00Z"),
                job(11, "CI Complete", "2026-11-09T10:11:05Z", "2026-11-09T10:11:10Z", conclusion="failure"),
                job(12, "Publish Run Metrics", "2026-11-09T10:11:12Z", None, conclusion=None, status="in_progress")]
        doc = run_metrics.build_document(raw_run(), jobs, {"action": "labeled"}, [])
        self.assertEqual([j["name"] for j in doc["jobs"]], ["MATS / HAPI Tests / a", "CI Complete"])
        self.assertEqual((doc["run"]["status"], doc["run"]["conclusion"]), ("completed", "failure"))
        self.assertEqual((doc["schema"], doc["context"]), (1, {"action": "labeled"}))

    def test_conclusion_without_ci_complete_uses_job_failures(self):
        failed = [job(1, "x", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z", conclusion="failure")]
        self.assertEqual(run_metrics.derive_conclusion(failed), "failure")
        self.assertEqual(run_metrics.derive_conclusion([]), "success")

    def test_context_comes_from_ctx_variables_only(self):
        env = {"CTX_DOCS_ONLY": "false", "CTX_ACTION": "opened", "PATH": "/bin", "GH_TOKEN": "secret"}
        self.assertEqual(run_metrics.context_from_env(env), {"action": "opened", "docs_only": "false"})


if __name__ == "__main__":
    unittest.main()
