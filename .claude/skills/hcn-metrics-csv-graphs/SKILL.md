---
name: hcn-metrics-csv-graphs
description: Turn the per-node metric CSV files a consensus node writes during a run (MainNetStats<N>.csv and similar, possibly in per-node folders or .gz/.zip/.tar.gz archives) into interactive, self-contained HTML graphs (zoom linked across charts, node selection, searchable metric names; uPlot, works offline). Supports graphing the whole run or only selected load sections (found from changes in trans_per_sec), a subset of nodes, and a subset of metrics chosen by category, family, name, description or a named group, with related metrics ordered next to each other. Use when someone asks to graph, plot or chart node metrics, stats CSVs, or a performance test's metrics.
---

# Graph consensus node metric CSVs

In the commands below, `$SKILL_DIR` is this skill's directory (`.claude/skills/hcn-metrics-csv-graphs`), `$PY` is the
Python chosen in step 1, and `$OUT` is the output folder (default `<root>/metrics-graphs`, where `<root>` is the folder
the user points to). Everything the scripts write goes under `$OUT`: `nodes.json`, `.cache/`, `catalog.tsv`,
`sections.json`, `sections-overview.png` and the `.html` reports.

Before starting, read [references/csv-format.md](references/csv-format.md) for the file layout and how families work.

## Hard rules

- **Never write throughput numbers into this skill** (`trans_per_sec`, `transH_per_sec` values, thresholds or ranges).
  Section detection must stay relative to each run's own peak. Values the scripts print at run time are fine to show the user.
- Ask before installing anything (step 1). Don't ask if the dependencies are already importable.
- Always confirm the metric selection, the nodes and the time window with the user before rendering.

## 1. Python and dependencies

```bash
for c in "$HOME/.cache/hcn-metrics-graphs/venv/bin/python" python3; do
  "$c" -c "import numpy, matplotlib" 2>/dev/null && { echo "PY=$c"; break; }
done
```

If one of them prints `PY=...`, use it and don't ask. Otherwise, ask the user how to install the missing packages,
with these options:
- a private virtual environment, created with
  `python3 -m venv ~/.cache/hcn-metrics-graphs/venv && ~/.cache/hcn-metrics-graphs/venv/bin/pip install numpy matplotlib`
- `python3 -m pip install --user numpy matplotlib`
- the user installs them themselves

Don't assume the choice from a previous session; machines have different restrictions. `matplotlib` is used only for
the section overview image. numpy 1.20 or later is required.

## 2. Find the node files

```bash
$PY "$SKILL_DIR/scripts/discover.py" <root> [--out $OUT]
$PY "$SKILL_DIR/scripts/prepare.py" $OUT
```

`discover.py` searches the tree, including archives, and identifies stats CSVs by their first line, not their name.
It gets each node id from `memberID`. Show the user the node → file table. If it prints `CHECK WITH USER` (a node with
several files, a node with no id, or an id that doesn't match the file name), resolve it with the user by setting
`"ignored": true` in `nodes.json` on the files not to use, then re-run `prepare.py`.

`prepare.py` parses each file once into `$OUT/.cache` (about 5 s per 100 MB file, files in parallel). It re-parses
only files that have changed. Ask which nodes the user wants; the default is all. Pass `--nodes 0,3` to the scripts
that follow.

## 3. Time window

```bash
$PY "$SKILL_DIR/scripts/sections.py" $OUT [--nodes ...]
```

This prints the run length, the sample interval and a numbered table of **load** and **idle** sections, and writes
`sections-overview.png`. Look at the PNG (Read it) to check that the split makes sense.
- **Several load sections**, which is typical for long runs that apply different loads: show the table and ask which
  section(s) to graph. Each chosen section becomes its own report. Also offer the whole run and a custom time window.
- **One load section, or a short run**: suggest the whole run, but still say what was detected.
- A short section at the start of a load is usually the ramp-up. Point this out rather than offering it as its own load.
- If the split looks wrong, re-run with `--level-change` (higher means fewer splits; the default is 0.2) or
  `--min-section-min`, or use a custom `--start/--end` the user gives.

## 4. Choose the metrics

The user rarely knows the exact names or prefixes. Turn their request into selectors, using:

```bash
$PY "$SKILL_DIR/scripts/catalog.py" $OUT                        # summary + $OUT/catalog.tsv (all metrics, plot order)
$PY "$SKILL_DIR/scripts/catalog.py" $OUT --search latency queue  # regex search on names and descriptions
$PY "$SKILL_DIR/scripts/catalog.py" $OUT --families --multi      # families with 2+ members
$PY "$SKILL_DIR/scripts/catalog.py" $OUT --list-groups           # named groups
```

**Always show the user the list of named groups** (`--list-groups`) as one of the choices, even when it is empty.

Write the selectors to `$OUT/selection.txt` (the syntax is in the `catalog.py` docstring: `group:`, `category:`,
`family:`, `name:`, `re:`, `desc:`, plain text, and `-` to exclude). Then check what it picks:

```bash
$PY "$SKILL_DIR/scripts/catalog.py" $OUT --select $OUT/selection.txt
```

Show the counts and the families to the user, and adjust until they confirm. If they want to reuse a selection,
it can be saved as a `## group: <name>` section in [references/metric-groups.md](references/metric-groups.md) (the
format is in that file). That edits a file in the repository, so show the user the exact section and ask before
writing it.

## 5. Render

```bash
$PY "$SKILL_DIR/scripts/plot.py" $OUT --select $OUT/selection.txt [--nodes ...] \
    [--section 3,4 | --start '2026-01-01 10:00' --end '12:30' | (nothing = whole run)] \
    [--name <stem>] [--title <text>]
```

**Key metrics** (`KEY_METRICS` in `scripts/catalog.py`: `trans_per_sec`, `secC2RC`, `secTransH`, `PlatformStatus`) are
always charted first, whatever the selection and even when they're constant. They are also charted again with their
family when the selection includes that family. If one is missing from the files, `plot.py` says so. Change the list
only when the user asks.

Each window produces its own `$OUT/<stem>-<section|whole-run|HHMM-HHMM>.html`. Metrics that are constant or
have no data in the window are listed by name at the end instead of being charted (`--keep-flat` charts them anyway).
For a quick check, use `--limit 8`.

Each report is self-contained (uPlot is embedded from `scripts/vendor/uplot`, so
nothing is loaded from the network). It holds full-resolution data, so zooming shows every sample. Features:
- **Zoom:** drag to zoom, double-click to reset.
- **Linked zoom:** a "Link zoom, cursor & node selection" checkbox, on by default, applies zoom, the vertical cursor
  line and the node selection to every chart. The horizontal cursor line shows only on the chart under the mouse.
  Turning linking back on returns every chart to the shared zoom and node selection.
- **Node selection:** click a node name in the header or in any chart legend to show only that node; click it again to
  show all. Ctrl/⌘/Shift-click adds or removes a node. With linking off, a legend click affects only that chart.
- **Theme:** dark by default; a toggle at the far right of the controls switches to light, and the browser remembers the choice.
- **Sidebar:** Key metrics, then category → family, with a filter box (every word must match the name, category,
  family or description).
- **Hover values:** the legend under each chart shows each node's value at the cursor.
- **Value names:** metrics whose description lists values (`0=NO_VALUE 1=STARTING_UP …`, e.g. `PlatformStatus`) are
  drawn as steps, with the names on the axis and in the hover values.
- **Search:** Ctrl-F finds metric names, because chart titles are page text.
- **Axes:** UTC times on the x axis. The legend also shows minutes since the window start.
- **Node colors:** a palette validated for colorblind separation and contrast on both themes. Nodes 8+ reuse the
  colors with dashed lines.

**Size limit.** The file size is roughly charts × nodes × samples in the window × ~5 bytes. Some reference points:
- one ~2 h section with every metric: ~65 MB;
- one family over a 19 h run: ~20–30 MB;
- every metric over 19 h: ~500 MB.

`plot.py` estimates the size first (to within a few percent). Above `--max-mb` (default 100) it writes nothing, prints `TOO LARGE` with the
estimate, and exits with code 3. When that happens, show the user the estimate and ask how to proceed:
- fewer families or groups,
- fewer nodes,
- a shorter window or a single section,
- or `--force` if they accept the size.

Don't add `--force` on your own.

Give the user the path(s) and sizes. To open: `open <file>` on macOS. To share a report, send just the `.html` file: it
needs nothing else. Its header lists each node's source file relative to the run folder (e.g.
`SDPT Pass/MainNetStats0_pass.csv`), so local paths and user names are not included.

## Re-plotting

The cache, `nodes.json` and `sections.json` stay in `$OUT`. A new selection, new nodes or a new window only needs
steps 4–5. `prepare.py` removes cache files for source files that changed or are no longer listed. Delete
`$OUT/.cache` to free disk space; it is about 1.4× the size of the uncompressed CSVs.
