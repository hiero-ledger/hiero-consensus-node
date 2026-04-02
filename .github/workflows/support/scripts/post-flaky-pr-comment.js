// SPDX-License-Identifier: Apache-2.0

const fs = require('fs');

module.exports = async ({ github, context, core }) => {
  const flakyIssues = JSON.parse(fs.readFileSync('flaky-issues.json', 'utf8'));
  if (flakyIssues.length === 0) return;

  const workflowContext = process.env.WORKFLOW_CONTEXT;
  let prNumber = null;

  if (workflowContext === 'pr-checks') {
    prNumber = context.payload.pull_request?.number;
  } else if (workflowContext === 'dry-run') {
    const branch = process.env.BRANCH_NAME;
    try {
      const { data: prs } = await github.rest.pulls.list({
        owner: context.repo.owner,
        repo: context.repo.repo,
        head: `${context.repo.owner}:${branch}`,
        state: 'open',
        per_page: 1,
      });
      if (prs.length > 0) {
        prNumber = prs[0].number;
      }
    } catch (e) {
      core.notice(`Could not detect PR for branch ${branch}: ${e.message}`);
    }
  }

  if (!prNumber) {
    core.notice('No PR found for this run — skipping PR comment.');
    return;
  }

  const hasNew = flakyIssues.some(t => t.is_new);
  const marker = '<!-- flaky-test-report -->';

  let body = marker + '\n';
  if (hasNew) {
    body += '## ⚠️ New Flaky Test(s) Detected — Action Required\n\n';
    body += 'New flaky tests were detected in this run. **New tickets have been created and require your attention — please investigate whether the flakiness was introduced by changes in this PR.**\n\n';
  } else {
    body += '## Flaky Test(s) Detected\n\n';
    body += 'One or more flaky tests were detected in this run. These tests have been reported before.\n\n';
  }

  body += '| Test | Ticket |\n';
  body += '|------|--------|\n';
  for (const test of flakyIssues) {
    const testName = `\`${test.class}#${test.method}\``;
    if (hasNew) {
      const status = test.is_new ? '🆕 New' : '🔁 Existing';
      body += `| ${testName} | #${test.issue_number} (${status}) |\n`;
    } else {
      body += `| ${testName} | #${test.issue_number} |\n`;
    }
  }

  if (hasNew) {
    body += '\nPlease review the linked tickets and determine if any of the new issues were caused by your changes.\n';
  }

  await github.rest.issues.createComment({
    owner: context.repo.owner,
    repo: context.repo.repo,
    issue_number: prNumber,
    body: body,
  });
};
