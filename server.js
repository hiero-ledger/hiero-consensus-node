'use strict';
const express = require('express');
const yaml = require('js-yaml');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3001;
const WORKFLOWS_DIR = path.join(__dirname, '.github', 'workflows');

const GROUP_COLORS = {
  entry:    { bg: '#dafbe1', border: '#2da44e', font: '#1a7f37' },
  manual:   { bg: '#ddf4ff', border: '#0969da', font: '#0550ae' },
  release:  { bg: '#ffebe9', border: '#cf222e', font: '#82071e' },
  perf:     { bg: '#fbefff', border: '#8250df', font: '#5a32a3' },
  reusable: { bg: '#fff8c5', border: '#bf8700', font: '#7d4e00' },
  leaf:     { bg: '#f3eeff', border: '#6639ba', font: '#3e1f79' },
};

const GROUP_LABELS = {
  entry: 'Push / PR', manual: 'Manual / Cron',
  release: 'Release', perf: 'Performance',
  reusable: 'Reusable', leaf: 'Leaf',
};

function normalizeTriggers(on) {
  if (!on) return [];
  if (typeof on === 'string') return [on];
  if (Array.isArray(on)) return on;
  return Object.keys(on);
}

function extractTriggerDetail(on) {
  if (!on || typeof on !== 'object' || Array.isArray(on)) return '';
  const parts = [];
  const arr = v => [].concat(v);
  if (on.push?.branches) parts.push('branches: ' + arr(on.push.branches).join(', '));
  if (on.pull_request?.types) parts.push('types: ' + arr(on.pull_request.types).join(', '));
  if (on.pull_request?.branches) parts.push('branches: ' + arr(on.pull_request.branches).join(', '));
  if (on.pull_request_target?.branches) parts.push('branches: ' + arr(on.pull_request_target.branches).join(', '));
  if (on.push?.paths) parts.push('paths: ' + arr(on.push.paths).join(', '));
  if (on.pull_request?.paths) parts.push('paths: ' + arr(on.pull_request.paths).join(', '));
  return parts.join(' · ');
}

function extractConcurrency(conc) {
  if (!conc) return { group: null, cancelInProgress: null };
  if (typeof conc === 'string') return { group: conc, cancelInProgress: null };
  return {
    group: conc.group || null,
    cancelInProgress: conc['cancel-in-progress'] || null,
  };
}

function extractInputs(on) {
  const rawInputs = on?.workflow_dispatch?.inputs || on?.workflow_call?.inputs || {};
  return Object.entries(rawInputs || {}).map(([name, inp]) => ({
    name,
    type: inp?.type || 'string',
    default: inp?.default ?? null,
    required: inp?.required || false,
  }));
}

function extractJobs(jobsObj) {
  return Object.entries(jobsObj || {}).map(([id, job]) => {
    const usesStr = job.uses || null;
    const usesWorkflow = usesStr?.startsWith('./.github/workflows/')
      ? path.basename(usesStr).replace(/\.ya?ml$/, '')
      : null;
    return {
      id,
      name: job.name || id,
      runner: job['runs-on'] || (job.uses ? 'reusable' : 'unknown'),
      line: null,
      needs: [].concat(job.needs || []),
      steps: (job.steps || []).map(s => s.name || '').filter(Boolean),
      usesWorkflow,
    };
  });
}

function detectGroup(id, triggers, outgoingCount) {
  const releasePatterns = ['release', 'deploy-release', 'deploy-adhoc', 'deploy-production', 'generate-release-notes'];
  if (releasePatterns.some(p => id.includes(p)) && !triggers.includes('workflow_call')) return 'release';
  if (triggers.some(t => ['push', 'pull_request', 'pull_request_target'].includes(t))) return 'entry';
  if (id.includes('performance') || id.includes('longevity') || id.includes('canonical')) return 'perf';
  if (triggers.includes('workflow_call')) return outgoingCount > 0 ? 'reusable' : 'leaf';
  return 'manual';
}

function makeLabel(name) {
  const words = name.replace(/^\[.*?\]\s*/, '').split(/\s+/);
  if (words.length <= 2) return words.join(' ');
  const mid = Math.ceil(words.length / 2);
  return words.slice(0, mid).join(' ') + '\n' + words.slice(mid).join(' ');
}

function buildWorkflowData() {
  const files = fs.readdirSync(WORKFLOWS_DIR)
    .filter(f => f.endsWith('.yaml') || f.endsWith('.yml'))
    .sort();

  const workflowMeta = {};
  const allEdges = [];

  for (const file of files) {
    const id = path.basename(file).replace(/\.ya?ml$/, '');
    let doc;
    try {
      const content = fs.readFileSync(path.join(WORKFLOWS_DIR, file), 'utf8');
      doc = yaml.load(content);
    } catch (e) {
      console.warn(`Failed to parse ${file}:`, e.message);
      continue;
    }
    if (!doc || typeof doc !== 'object') continue;

    const triggers = normalizeTriggers(doc.on);
    const triggerDetail = extractTriggerDetail(doc.on);
    const { group: concurrency, cancelInProgress } = extractConcurrency(doc.concurrency);
    const jobs = extractJobs(doc.jobs);
    const inputs = extractInputs(doc.on);

    for (const job of jobs) {
      if (job.usesWorkflow) {
        allEdges.push({ from: id, to: job.usesWorkflow });
      }
    }

    workflowMeta[id] = {
      name: doc.name || id,
      triggers,
      triggerDetail,
      concurrency,
      cancelInProgress,
      jobs,
      inputs,
    };
  }

  const workflows = Object.entries(workflowMeta).map(([id, meta]) => {
    const outgoing = allEdges.filter(e => e.from === id).length;
    const group = detectGroup(id, meta.triggers, outgoing);
    return {
      id,
      label: makeLabel(meta.name),
      trigger: meta.triggers.join(', '),
      group,
    };
  });

  return {
    workflowMeta,
    workflows,
    edges: allEdges,
    groupColors: GROUP_COLORS,
    groupLabels: GROUP_LABELS,
  };
}

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'workflow-graph.html'));
});

app.get('/api/workflows', (req, res) => {
  try {
    res.json(buildWorkflowData());
  } catch (err) {
    console.error('Error building workflow data:', err);
    res.status(500).json({ error: err.message });
  }
});

app.listen(PORT, () => {
  console.log(`Workflow visualizer running at http://localhost:${PORT}`);
});
