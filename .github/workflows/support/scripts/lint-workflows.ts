// SPDX-License-Identifier: Apache-2.0

import * as fs from 'fs';
import * as path from 'path';
import * as yaml from 'js-yaml';


const INIT_GITHUB_JOB_NAME = 'pandaswhocode/initialize-github-job'.toLowerCase();
const EXPECTED_VERSION = 'v1.1.0';


const SCRIPT_FILE = process.argv[1];
const WORKFLOWS_DIR = process.argv[2] ? path.resolve(process.argv[2]) : path.resolve(SCRIPT_FILE, '../../../');

interface Step {
  uses?: string;
  with?: Record<string, unknown>;
}

interface Job {
  steps?: Step[];
}

interface Workflow {
  jobs?: Record<string, Job>;
}

let errors = 0;

function checkVersionInRawLines(lines: string[], filePath: string): void {
  lines.forEach((line, i) => {
    if (!line.toLowerCase().includes(INIT_GITHUB_JOB_NAME)) return;
    if (!line.includes(INIT_GITHUB_JOB_NAME)) {
      console.error(`ERROR: ${filePath}:${i + 1}: action name should be lowercase (found: ${line.trim()})`);
      errors++;
    }
    const versionMatch = line.match(/#\s*(v[\d.]+)/);
    if (!versionMatch || versionMatch[1] !== EXPECTED_VERSION) {
      console.error(`ERROR: ${filePath}:${i + 1}: ${INIT_GITHUB_JOB_NAME} should use ${EXPECTED_VERSION} (found: ${line.trim()})`);
      errors++;
    }
  });
}

function checkUnquotedParams(workflow: Workflow, filePath: string): void {
  if (!workflow?.jobs) return;
  for (const job of Object.values(workflow.jobs)) {
    if (!job?.steps) continue;
    for (const step of job.steps) {
      if (!step?.uses?.toLowerCase().includes(INIT_GITHUB_JOB_NAME)) continue;
      if (!step.with) continue;
      for (const [key, value] of Object.entries(step.with)) {
        if (typeof value !== 'string') {
          console.error(`ERROR: ${filePath}: parameter "${key}" has unquoted value: ${JSON.stringify(value)} (should be a quoted string)`);
          errors++;
        }
      }
    }
  }
}

function checkFile(filePath: string): void {
  const content = fs.readFileSync(filePath, 'utf-8');

  checkVersionInRawLines(content.split('\n'), filePath);

  let workflow: Workflow;
  try {
    workflow = yaml.load(content) as Workflow;
  } catch (e) {
    console.error(`ERROR: Failed to parse ${filePath}: ${e}`);
    errors++;
    return;
  }

  checkUnquotedParams(workflow, filePath);
}

console.log(`scanning ${WORKFLOWS_DIR}`);
const files = fs.readdirSync(WORKFLOWS_DIR)
  .filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))
  .map(f => path.join(WORKFLOWS_DIR, f));

for (const file of files) {
  checkFile(file);
}

if (errors > 0) {
  console.error(`\nFound ${errors} error(s).`);
  process.exit(1);
} else {
  console.log('All checks passed.');
}
