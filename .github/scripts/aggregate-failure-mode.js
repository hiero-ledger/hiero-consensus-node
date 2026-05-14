// SPDX-License-Identifier: Apache-2.0
const fs = require('fs');

const resultLines = (process.env.JOB_RESULTS      || '').split('\n').map(s => s.trim());
const modeLines   = (process.env.JOB_FAILURE_MODES || '').split('\n').map(s => s.trim());
const pairs       = resultLines.map((r, i) => [r, modeLines[i] || 'none']).filter(([r]) => r);
const handleCancelled = (process.env.HANDLE_CANCELLED || '').toLowerCase() === 'true';

let failureMode = 'none';

for (const [status, mode] of pairs) {
  if (status === 'failure') {
    if (failureMode === 'none') {
      failureMode = mode;
    } else if (mode === 'workflow') {
      failureMode = 'workflow';
    }
  } else if (handleCancelled && status === 'cancelled') {
    if (failureMode !== 'workflow') {
      failureMode = 'workflow';
    }
  }
}

fs.appendFileSync(process.env.GITHUB_OUTPUT, `failure-mode=${failureMode}\n`);
