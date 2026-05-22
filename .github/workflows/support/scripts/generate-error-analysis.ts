// SPDX-License-Identifier: Apache-2.0

import * as fs from 'fs';
import * as path from 'path';

const SCRIPT_FILE = process.argv[1];
const WORKFLOWS_DIR = path.resolve(SCRIPT_FILE, '../../../');
const REPO_ROOT = path.resolve(SCRIPT_FILE, '../../../../../');
const REPORT_OUTPUT = path.join(REPO_ROOT, 'workflow-error-analysis.md');

interface Match {
  absPath: string;
  line: number;
  label: string;
  context: string;
}

function rel(absPath: string): string {
  return path.relative(REPO_ROOT, absPath);
}

function fileLink(m: Match): string {
  return `[${path.basename(m.absPath)}:${m.line}](${rel(m.absPath)}#L${m.line})`;
}

function lookBack(lines: string[], fromLine: number, n: number): string {
  for (let i = fromLine - 2; i >= Math.max(0, fromLine - n); i--) {
    const m = lines[i].match(/^\s+(?:name|id):\s+(.+)$/);
    if (m) return m[1].trim().replace(/['"]/g, '');
    if (/^\s+-\s+uses:/.test(lines[i]) && i < fromLine - 2) break;
  }
  return '';
}

function lookForward(lines: string[], fromLine: number, pattern: RegExp, n: number): string {
  for (let i = fromLine; i < Math.min(lines.length, fromLine + n); i++) {
    const m = lines[i].match(pattern);
    if (m) return m[1] ?? m[0];
  }
  return '';
}

function scanFile(filePath: string): {
  failureModes: Match[];
  slackNotifications: Match[];
  rootlyNotifications: Match[];
} {
  const lines = fs.readFileSync(filePath, 'utf-8').split('\n');
  const failureModes: Match[] = [];
  const slackNotifications: Match[] = [];
  const rootlyNotifications: Match[] = [];

  lines.forEach((line, i) => {
    const lineNum = i + 1;
    const trimmed = line.trim();

    // --- Failure mode ---

    // Step definitions: id: set-failure-mode
    if (/^\s+id:\s+set-failure-mode/.test(line)) {
      failureModes.push({ absPath: filePath, line: lineNum, label: 'set-failure-mode step', context: '' });
    }
    // Job output definition: failure-mode:
    else if (/^\s+failure-mode:/.test(line) && !line.includes('needs.')) {
      failureModes.push({ absPath: filePath, line: lineNum, label: 'output definition', context: trimmed });
    }
    // Consumed as needs output
    else if (/needs\.[^.]+\.outputs\.failure-mode/.test(line)) {
      failureModes.push({ absPath: filePath, line: lineNum, label: 'consumed output', context: trimmed });
    }
    // if: failure() checks
    else if (/if:.*\bfailure\(\)/.test(line)) {
      failureModes.push({ absPath: filePath, line: lineNum, label: 'if: failure() check', context: trimmed });
    }

    // --- Slack notifications ---

    if (/uses:\s+slackapi\/slack-github-action/.test(line)) {
      const stepName = lookBack(lines, lineNum, 10);
      const webhook = lookForward(lines, lineNum, /\$({{[^}]+}}|secrets\.(SLACK_\w+|slack-\w+))/, 15);
      slackNotifications.push({
        absPath: filePath,
        line: lineNum,
        label: stepName || 'send notification',
        context: webhook,
      });
    }

    // --- Rootly notifications ---

    if (/uses:\s+pandaswhocode\/rootly-alert-action/.test(line)) {
      const stepName = lookBack(lines, lineNum, 10);
      const service = lookForward(lines, lineNum, /service[_-]?name[^:]*:\s*["']?([^"'\n#]+)/, 20);
      rootlyNotifications.push({
        absPath: filePath,
        line: lineNum,
        label: stepName || 'create alert',
        context: service.trim(),
      });
    }
  });

  return { failureModes, slackNotifications, rootlyNotifications };
}

const workflowFiles = fs.readdirSync(WORKFLOWS_DIR)
  .filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))
  .sort()
  .map(f => path.join(WORKFLOWS_DIR, f));

const allFailureModes: Match[] = [];
const allSlack: Match[] = [];
const allRootly: Match[] = [];

for (const file of workflowFiles) {
  const { failureModes, slackNotifications, rootlyNotifications } = scanFile(file);
  allFailureModes.push(...failureModes);
  allSlack.push(...slackNotifications);
  allRootly.push(...rootlyNotifications);
}

function table(matches: Match[], col3Header: string): string {
  if (matches.length === 0) return '_No matches found._\n\n';
  const rows = matches.map(m => `| ${fileLink(m)} | ${m.label} | ${m.context} |`);
  return ['| File | Pattern | ' + col3Header + ' |', '|------|---------|---------|', ...rows].join('\n') + '\n\n';
}

const report = `# Workflow Error Analysis

> Generated on ${new Date().toISOString().slice(0, 10)} — run \`npx tsx .github/workflows/support/scripts/generate-error-analysis.ts\` to refresh.

---

## Failure Mode Checks

Covers: \`set-failure-mode\` step definitions, \`failure-mode\` output declarations,
consumed outputs (\`needs.*.outputs.failure-mode\`), and \`if: failure()\` conditions.

${table(allFailureModes, 'Detail')}---

## Slack Notifications

Every \`slackapi/slack-github-action\` invocation across all workflows.

${table(allSlack, 'Webhook / Secret')}---

## Rootly Notifications

Every \`pandaswhocode/rootly-alert-action\` invocation across all workflows.

${table(allRootly, 'Service')}`;

fs.writeFileSync(REPORT_OUTPUT, report);
console.log(`Report written to ${REPORT_OUTPUT}`);
console.log(`  ${allFailureModes.length} failure-mode entries`);
console.log(`  ${allSlack.length} Slack notifications`);
console.log(`  ${allRootly.length} Rootly notifications`);
