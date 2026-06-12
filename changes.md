## 2026-05-20 16:00

### Workflow Visualizer: Live data from YAML files

Replaced hardcoded workflow data in `workflow-graph.html` with a small Node.js/Express server that reads and parses the actual `.github/workflows/*.yaml` files on each request. The graph now stays accurate automatically as workflows are added, renamed, or updated.

**New files:**
- `server.js` — Express server (port 3001). Serves the HTML on `GET /` and parsed workflow data on `GET /api/workflows`. Uses `js-yaml` to extract workflow names, triggers, trigger detail (branches, PR types), concurrency, jobs, inputs, and edges (from `uses: ./.github/workflows/` references). Auto-derives node group (`entry`, `release`, `perf`, `reusable`, `leaf`, `manual`) from filename patterns and trigger types.
- `package.json` — Dependencies: `express`, `js-yaml`
- `package-lock.json` — Lockfile
- `Dockerfile` — `node:20-alpine` image exposing port 3001, compatible with existing `fly.toml` config
- `changes.md` — This file

**Modified:**
- `workflow-graph.html` — Removed ~200 lines of hardcoded `workflowMeta`, `workflows`, `edges`, `groupColors`, and `groupLabels` constants. Replaced with module-level `let` declarations, an `initGraph()` function wrapping all vis.js initialization, and a `fetch('/api/workflows')` call that hydrates the data on page load.

**Usage:**
```
npm install
node server.js
# Open http://localhost:3001
```
