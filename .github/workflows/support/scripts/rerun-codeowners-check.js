// SPDX-License-Identifier: Apache-2.0

// Re-runs the existing codeowners check for the pull request's head commit.
//
// A review changes whether the codeowners check should pass, but the 702 run
// triggered by the push has already finished and its check run is frozen at
// that moment. Starting a second 702 run would leave the first behind on the
// same commit as a stale red - or, when the two overlap, a cancelled check.
// Re-running the existing run instead updates its check in place, so the
// commit keeps exactly one "Codeowners Check" and it flips red <-> green as
// reviews are submitted and dismissed.

const WORKFLOW = '702-flow-codeowners.yaml';
const POLL_INTERVAL_MS = 15000;
const POLL_TIMEOUT_MS = 10 * 60 * 1000;

module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const sha = context.payload.pull_request.head.sha;

  // Most recent run of the codeowners workflow for this exact commit.
  const latestRun = async () => {
    const { data } = await github.rest.actions.listWorkflowRuns({
      owner,
      repo,
      workflow_id: WORKFLOW,
      head_sha: sha,
      per_page: 20,
    });
    const runs = data.workflow_runs;
    if (runs.length === 0) return null;
    runs.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
    return runs[0];
  };

  let run = await latestRun();
  if (!run) {
    core.info(`No ${WORKFLOW} run found for ${sha}; nothing to re-run.`);
    return;
  }

  // A run that is still going has probably already read the approval state
  // from before this review, so wait for it rather than racing it. The re-run
  // API only accepts completed runs in any case.
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  while (run.status !== 'completed' && Date.now() < deadline) {
    core.info(`Run ${run.id} is ${run.status}; waiting for it to finish...`);
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
    run = await latestRun();
  }

  if (run.status !== 'completed') {
    core.warning(`Run ${run.id} is still ${run.status}; skipping re-run.`);
    return;
  }

  core.info(`Re-running run ${run.id} (previous conclusion: ${run.conclusion}).`);
  await github.rest.actions.reRunWorkflow({ owner, repo, run_id: run.id });
};
