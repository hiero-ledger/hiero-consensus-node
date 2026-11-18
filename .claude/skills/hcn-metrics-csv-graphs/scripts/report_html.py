"""Interactive HTML report: one self-contained file per time window, charts drawn with uPlot.

Data layout in the file (JSON inside a <script type="application/json"> tag):
  x       base64 Float64 - union of all nodes' sample times in the window (epoch seconds)
  pos     per node, base64 Int32 - index of each of that node's samples in x
  charts  per metric: category, name, family, description and, per node, base64 values
          (Float32 when that is lossless for the series, Float64 otherwise; NaN = gap)
Each node keeps its own sample times. In the browser a node's values are spread onto x with
'undefined' at other nodes' instants (uPlot bridges those) and 'null' at real gaps.
All metric text is inserted with textContent, never as HTML.
"""

import base64
import json
import os

import numpy as np

import statscsv as sc

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor", "uplot")
TEMPLATE = os.path.join(HERE, "report_template.html")
# Node colors: an 8-slot categorical palette validated (colorblind separation, lightness, contrast)
# separately for the light surface #fcfcfb and the dark surface #1a1a19. Slot order matters for
# colorblind safety - do not reorder. Node n uses slot n % 8; nodes 8+ add a dash pattern instead
# of inventing more hues.
NODE_COLORS = {
    "light": ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"],
    "dark": ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#0ca30c", "#9085e9", "#e66767"],
}
DASHES = [[], [8, 4], [2, 3], [10, 3, 2, 3]]  # uPlot line dash patterns (px) for nodes 8-15, 16-23, ...


def _b64(arr):
    return base64.b64encode(np.ascontiguousarray(arr).tobytes()).decode("ascii")


def _f4_lossless(y):
    """True if every finite value survives a round trip through float32."""
    fin = np.isfinite(y)
    return np.array_equal(y[fin].astype("<f4").astype(np.float64), y[fin])


def _encode_values(y):
    if _f4_lossless(y):
        return "f4", _b64(y.astype("<f4"))
    return "f8", _b64(y.astype("<f8"))


def estimate_bytes(charts, node_times):
    """Expected file size: base64 values (4 or 8 bytes each, as render() will choose), the shared
    time axis and per-node positions, per-chart text, and the fixed template + uPlot overhead."""
    b64 = 4.0 / 3.0
    values = sum(len(y) * (4 if _f4_lossless(np.asarray(y, dtype=np.float64)) else 8)
                 for _, series in charts for _, y in series)
    union = len(np.unique(np.concatenate([t for t in node_times if t.size] or [np.zeros(0)])))
    positions = sum(t.size for t in node_times)
    text = sum(200 + len(m.name) + len(m.family) + len(m.desc) for m, _ in charts)
    fixed = sum(os.path.getsize(p) for p in (TEMPLATE, os.path.join(VENDOR, "uPlot.min.css"),
                                             os.path.join(VENDOR, "uPlot.iife.min.js")))
    return int(b64 * (values + 8 * union + 4 * positions) + text + fixed)


def display_source(source, root):
    """Source path as shown in the report: relative to the run folder (keeps local paths private)."""
    path, sep, member = source.partition(sc.ARCHIVE_SEP)
    try:
        rel = os.path.relpath(path, root)
    except ValueError:
        rel = os.path.basename(path)
    if rel.startswith(".."):
        rel = os.path.basename(path)
    return os.path.join(os.path.basename(root.rstrip(os.sep)), rel) + sep + member


def render(path, title, label, ws, we, nodes, node_times, plotted, key_plotted, flat, nodata, selectors, root):
    """nodes: NodeData list; node_times: per node, epoch times of its samples in the window
    (same order as each series' values); plotted: [(Metric, [(node_id, values), ...])] in display
    order; key_plotted: same shape, shown first. A metric's data is stored once even when it
    appears both in the key section and with its family."""
    union = np.unique(np.concatenate([t for t in node_times if t.size] or [np.zeros(0)]))
    node_index = {n.node: i for i, n in enumerate(nodes)}
    pos = [_b64(np.searchsorted(union, t).astype("<i4")) for t in node_times]

    charts = []
    index_of = {}

    def add(met, series, regular):
        s = []
        for node, y in series:
            kind, data = _encode_values(np.asarray(y, dtype=np.float64))
            s.append([node_index[node], kind, data])
        index_of[met.key] = len(charts)
        charts.append({"c": met.cat, "n": met.name, "f": met.family, "d": met.desc, "s": s, "r": regular})

    for met, series in plotted:
        add(met, series, True)
    for met, series in key_plotted:
        if met.key not in index_of:
            add(met, series, False)
    key = [index_of[m.key] for m, _ in key_plotted]

    doc = {
        "title": title,
        "label": label,
        "start": ws,
        "end": we,
        "startUtc": sc.fmt_time(ws),
        "endUtc": sc.fmt_time(we),
        "nodes": [{"id": n.node, "slot": n.node % 8, "dash": DASHES[(n.node // 8) % len(DASHES)],
                   "file": display_source(n.source, root)} for n in nodes],
        "colors": NODE_COLORS,
        "key": key,
        "x": _b64(union.astype("<f8")),
        "pos": pos,
        "charts": charts,
        "notCharted": [{"n": m.name, "c": m.cat, "v": float(v)} for m, v in flat] +
                      [{"n": m.name, "c": m.cat, "v": None} for m in nodata],
        "selection": selectors,
    }
    # allow_nan=False: NaN/Infinity are not valid JSON and would stop the whole page from loading
    data = json.dumps(doc, separators=(",", ":"), allow_nan=False).replace("</", "<\\/").replace("<!--", "<\\u0021--")
    with open(TEMPLATE) as f:
        html = f.read()
    with open(os.path.join(VENDOR, "uPlot.min.css")) as f:
        css = f.read()
    with open(os.path.join(VENDOR, "uPlot.iife.min.js")) as f:
        js = f.read().replace("</script", "<\\/script")
    page_title = "%s - %s" % (title, label)
    html = (html.replace("%%PAGE_TITLE%%", _html_escape(page_title))
                .replace("%%UPLOT_CSS%%", css)
                .replace("%%UPLOT_JS%%", js)
                .replace("%%DATA%%", data))
    with open(path, "w") as f:
        f.write(html)
    return os.path.getsize(path)


def _html_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")
