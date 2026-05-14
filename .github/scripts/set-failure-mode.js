// SPDX-License-Identifier: Apache-2.0
const fs = require('fs');

const workflowOutcomes = (process.env.WORKFLOW_OUTCOMES || '').split('\n').map(s => s.trim()).filter(Boolean);
const testOutcomes     = (process.env.TEST_OUTCOMES     || '').split('\n').map(s => s.trim()).filter(Boolean);
const cancelledIsWorkflow = (process.env.CANCELLED_IS_WORKFLOW || '').toLowerCase() === 'true';

let failureMode = 'none';
if (testOutcomes.some(s => s === 'failure'))     failureMode = 'test';
if (workflowOutcomes.some(s => s === 'failure')) failureMode = 'workflow';
if (cancelledIsWorkflow && testOutcomes.some(s => s === 'cancelled')) failureMode = 'workflow';

fs.appendFileSync(process.env.GITHUB_OUTPUT, `failure-mode=${failureMode}\n`);
