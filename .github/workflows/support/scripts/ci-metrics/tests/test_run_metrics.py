# SPDX-License-Identifier: Apache-2.0
import importlib.util
import os
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("run_metrics", os.path.join(ROOT, "run_metrics.py"))
run_metrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run_metrics)


def raw_run():
    return {"id": 1, "name": "600: PR Checks", "event": "pull_request", "head_sha": "0123456789abcdef0123456789abcdef01234567", "status": "in_progress",
            "conclusion": None, "run_attempt": 1, "created_at": "2026-11-09T10:00:00Z", "html_url": "https://x/1",
            "display_title": "PR #7 labeled: title", "workflow_id": 291484377, "workflow": "600: PR Checks", "suite": "MATS",
            "pr_numbers": [7]}


def job(jid, name, start, end, conclusion="success", status="completed", attempt=1):
    return {"run_id": 1, "id": jid, "name": name, "status": status, "conclusion": conclusion, "run_attempt": attempt,
            "created_at": start, "started_at": start, "completed_at": end, "runner_name": "r1",
            "runner_group_name": "g", "labels": ["hl-cn-hapi-lin-lg"], "steps": []}


class Conclusion(unittest.TestCase):
    def test_comes_from_ci_complete(self):
        jobs = [job(10, "MATS / HAPI Tests / a", "2026-11-09T10:01:00Z", "2026-11-09T10:11:00Z"),
                job(11, "CI Complete", "2026-11-09T10:11:05Z", "2026-11-09T10:11:10Z", conclusion="failure")]
        self.assertEqual(run_metrics.derive_conclusion(jobs), "failure")

    def test_latest_attempt_wins_when_a_rerun_has_two_ci_complete_jobs(self):
        jobs = [job(11, "CI Complete", "2026-11-09T10:11:05Z", "2026-11-09T10:11:10Z", conclusion="failure", attempt=1),
                job(21, "CI Complete", "2026-11-09T12:11:05Z", "2026-11-09T12:11:10Z", conclusion="success", attempt=2)]
        self.assertEqual(run_metrics.derive_conclusion(jobs), "success")

    def test_without_ci_complete_failure_and_cancellation_are_not_success(self):
        ok = job(1, "a", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z")
        self.assertEqual(run_metrics.derive_conclusion([ok]), "success")
        self.assertEqual(run_metrics.derive_conclusion([ok, job(2, "b", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z", conclusion="failure")]), "failure")
        self.assertEqual(run_metrics.derive_conclusion([ok, job(3, "c", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z", conclusion="cancelled")]), "cancelled")
        self.assertEqual(run_metrics.derive_conclusion([ok, job(4, "d", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z", conclusion="timed_out")]), "failure")

    def test_only_the_latest_attempt_counts_without_ci_complete(self):
        jobs = [job(1, "a", "2026-11-09T10:00:00Z", "2026-11-09T10:01:00Z", conclusion="failure", attempt=1),
                job(2, "a", "2026-11-09T12:00:00Z", "2026-11-09T12:01:00Z", conclusion="success", attempt=2)]
        self.assertEqual(run_metrics.derive_conclusion(jobs), "success")


class Document(unittest.TestCase):
    def test_in_progress_job_is_dropped_and_the_pieces_are_carried(self):
        jobs = [job(10, "x", "2026-11-09T10:01:00Z", "2026-11-09T10:11:00Z"),
                job(12, "Publish Run Metrics", "2026-11-09T10:11:12Z", None, conclusion=None, status="in_progress")]
        doc = run_metrics.build_document(raw_run(), jobs, {"action": "labeled"}, [{"kind": "shard"}], [{"artifact": "a", "error": "e"}])
        self.assertEqual([j["name"] for j in doc["jobs"]], ["x"])
        self.assertEqual((doc["run"]["status"], doc["run"]["conclusion"], doc["schema"]), ("completed", "success", 1))
        self.assertEqual((doc["context"], doc["tests"], doc["test_errors"]), ({"action": "labeled"}, [{"kind": "shard"}], [{"artifact": "a", "error": "e"}]))


class Citr(unittest.TestCase):
    SHA = "0123456789abcdef0123456789abcdef01234567"

    def run_(self, event, attempt=1, sha=None):
        return {"id": 4321, "event": event, "run_attempt": attempt, "head_sha": sha or self.SHA}

    def test_pull_request_and_push_runs_use_their_head_commit(self):
        for event in ("pull_request", "push"):
            self.assertEqual(run_metrics.citr_labels(self.run_(event, 2), {}),
                             {"test_run_id": "4321", "run_attempt": "2", "consensus_short_commit": "0123456"})

    def test_a_dispatched_run_has_no_commit_unless_the_workflow_says_which(self):
        self.assertEqual(run_metrics.citr_labels(self.run_("workflow_dispatch"), {}), {"test_run_id": "4321", "run_attempt": "1"})
        tested = "fedcba9876543210fedcba9876543210fedcba98"
        self.assertEqual(run_metrics.citr_labels(self.run_("workflow_dispatch"), {"tested_commit": tested})["consensus_short_commit"], "fedcba9")

    def test_tested_commit_wins_over_the_head_commit(self):
        tested = "fedcba9876543210fedcba9876543210fedcba98"
        self.assertEqual(run_metrics.citr_labels(self.run_("push"), {"tested_commit": tested})["consensus_short_commit"], "fedcba9")

    def test_a_commit_that_is_not_a_full_sha_is_rejected(self):
        for bad in ("main", "abc1234", "0123456789ABCDEF0123456789ABCDEF01234567"):
            with self.assertRaises(ValueError):
                run_metrics.citr_labels(self.run_("workflow_dispatch"), {"tested_commit": bad})

    def test_the_document_carries_the_labels(self):
        run = dict(raw_run(), event="pull_request", run_attempt=1, head_sha=self.SHA)
        doc = run_metrics.build_document(run, [], {}, [], [])
        self.assertEqual(doc["citr"], {"test_run_id": "1", "run_attempt": "1", "consensus_short_commit": "0123456"})


class Context(unittest.TestCase):
    def test_ctx_variables_only(self):
        env = {"CTX_DOCS_ONLY": "false", "CTX_ACTION": "opened", "PATH": "/bin", "GH_TOKEN": "secret"}
        self.assertEqual(run_metrics.context_from_env(env), {"action": "opened", "docs_only": "false"})

    def test_json_context_is_merged_and_variables_win(self):
        env = {"CTX_JSON": '{"action": "push", "ref": "abc"}', "CTX_ACTION": "dispatch"}
        self.assertEqual(run_metrics.context_from_env(env), {"action": "dispatch", "ref": "abc"})

    def test_json_context_must_be_an_object_of_strings(self):
        for bad in ('["a"]', '{"a": 1}', "not json"):
            with self.assertRaises(ValueError):
                run_metrics.context_from_env({"CTX_JSON": bad})


if __name__ == "__main__":
    unittest.main()
