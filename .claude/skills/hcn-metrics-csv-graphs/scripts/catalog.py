#!/usr/bin/env python3
"""Metric catalog: topics, families, ordering, search, the selection menu, named groups and selection files.

Hierarchy: category -> topic -> family -> metric.
  - category  as shown in reports. Usually the CSV's category, but references/topics.md moves metrics out of
              catch-all CSV categories (e.g. 'internal') into the category they belong to. Each metric keeps its
              CSV category (csv_cat) for reading the data.
  - topic     a curated, meaning-based group from references/topics.md; unmatched metrics go to 'Other'.
  - family    metrics in the same category and topic whose names differ in one '_'-separated token
              ('*_unhandled_task_count', 'bytes_per_sec_sent_*', 'ds_compactions_level_*_hashesTimeMs_state'),
              or only in a trailing number / MAX / MIN ('pityParent*' for pityParent0..6,
              'numVotingRounds*' for numVotingRounds + numVotingRoundsMAX).

Usage (all forms except --list-groups accept --nodes 0,3; default: all nodes in nodes.json):
  catalog.py OUT_DIR                       write OUT_DIR/catalog.tsv, print a summary
  catalog.py OUT_DIR --menu [--full]       print the numbered selection menu: topics with counts and descriptions
                                           (--full: every family and metric too). Always writes the complete
                                           menu to OUT_DIR/menu.md, and OUT_DIR/menu.json for menu: selectors
  catalog.py OUT_DIR --search TEXT ...     find metrics by name/description (case-insensitive regex)
  catalog.py OUT_DIR --families [--multi] [--category CAT]
                                           list families (--multi: only those with 2+ members)
  catalog.py OUT_DIR --list-groups         named groups from references/metric-groups.md
  catalog.py OUT_DIR --select FILE         resolve a selection file and print what it picks

Selection file: one selector per line, '#' comments, prefix '-' to exclude. Matching is case-insensitive.
  all                     every metric
  menu:<n> | menu:<n.m>   a topic (n) or a family/metric (n.m) from the last --menu; ranges: menu:9.25-9.36
  group:<name>            a named group from references/metric-groups.md
  topic:<glob>            a topic, as 'category/topic' or just the topic name, e.g. topic:*Gossip*
  category:<glob>         e.g. category:merkle_db (the category shown in reports)
  family:<glob>           e.g. family:*_unhandled_task_count
  names:<a>, <b>, ...     exact metric names
  name:<glob>             glob on the metric name, e.g. name:*Latency*
  re:<regex>              regex search on the metric name
  desc:<regex>            regex search on the description
  <text>                  same as name:<text>; plain text without wildcards matches as a substring
If there are only exclusions, they are applied to all metrics.
"""

import argparse
import fnmatch
import json
import os
import re
import sys
import textwrap

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statscsv as sc  # noqa: E402

SKILL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUPS_FILE = os.path.join(SKILL_DIR, "references", "metric-groups.md")
TOPICS_FILE = os.path.join(SKILL_DIR, "references", "topics.md")
SKIP_NAMES = {"", "time"}
OTHER = "Other"
# A shared suffix starting with one of these words only says what *kind* of number a metric is
# (a rate or a ratio), not what it measures, so '*_per_sec' is not a family: trans_per_sec and
# syncs_per_sec measure unrelated things. Add words here if other unit-like suffixes show up.
GENERIC_SUFFIX_STARTS = ("per",)
# Always charted, first, in every report (and also with their family when it is selected).
# Matched case-insensitively on the metric name; edit this list to change the key metrics.
KEY_METRICS = ("trans_per_sec", "secC2RC", "secTransH", "PlatformStatus")
# A trailing number or MAX/MIN marks a variant of the same measurement (per peer, or the max of an average).
VARIANT_SUFFIX = re.compile(r"(\d+|MAX|MIN)$")


# ---------------------------------------------------------------- metrics

class Metric(object):
    __slots__ = ("csv_cat", "cat", "topic", "name", "desc", "family")

    def __init__(self, csv_cat, name, desc):
        self.csv_cat = csv_cat
        self.cat = csv_cat
        self.topic = OTHER
        self.name, self.desc = name, desc
        self.family = name

    @property
    def key(self):
        """Identifies the CSV column, so it uses the CSV's own category."""
        return (self.csv_cat, self.name)


def natural_key(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def load_metrics(out_dir, wanted_nodes=None, topics=None):
    """Union of metrics over the selected nodes' headers, with topics, categories and families assigned."""
    doc = sc.load_nodes(out_dir)
    seen = {}
    for e in sc.selected_nodes(doc, wanted_nodes):
        with sc.open_text(e["source"]) as fh:
            desc, cats, names, _ = sc.read_header(fh)
        for c, n in zip(cats, names):
            if n in SKIP_NAMES or (c, n) in seen:
                continue
            seen[(c, n)] = Metric(c, n, desc.get(n, ""))
    metrics = list(seen.values())
    topics = load_topics() if topics is None else topics
    assign_topics(metrics, topics)
    assign_families(metrics)
    return order_metrics(metrics, topics)


# ---------------------------------------------------------------- topics

def load_topics(path=TOPICS_FILE):
    """[(category, topic, description, from_csv_categories or None, selectors)] in file order."""
    topics = []
    if not os.path.exists(path):
        return topics
    cur, in_block = None, False
    with open(path) as f:
        for line in f:
            s = line.rstrip("\n")
            m = re.match(r"^##\s+topic:\s*(.+?)\s*/\s*(.+?)\s*$", s)
            if m and not in_block:
                cur = {"cat": m.group(1), "topic": m.group(2), "desc": [], "from": None, "sel": []}
                topics.append(cur)
                continue
            if cur is None:
                continue
            if s.strip().startswith("```"):
                in_block = not in_block
                continue
            if in_block:
                t = s.strip()
                if not t or t.startswith("#"):
                    continue
                if t.lower().startswith("from:"):
                    cur["from"] = set(x.strip() for x in t[5:].split(",") if x.strip())
                else:
                    cur["sel"].append(t)
            elif s.strip() and not s.startswith("#"):
                cur["desc"].append(s.strip())
    return [(t["cat"], t["topic"], " ".join(t["desc"]), t["from"], t["sel"]) for t in topics]


def assign_topics(metrics, topics):
    """First matching topic wins; it also sets the category shown in reports."""
    free = list(metrics)
    for cat, topic, _, from_cats, sel in topics:
        pool = [m for m in free if from_cats is None or m.csv_cat in from_cats]
        keys = resolve(sel, pool, {})
        for m in pool:
            if m.key in keys:
                m.cat, m.topic = cat, topic
        free = [m for m in free if m.key not in keys]


# ---------------------------------------------------------------- families

def _candidates(name):
    """{key: preference} for every way this name could share a family with other names."""
    out = {}
    stem = VARIANT_SUFFIX.sub("", name)
    if stem and not stem.endswith("_"):
        out[stem + "*"] = 0  # pityParent3 -> pityParent*, numVotingRoundsMAX and numVotingRounds -> numVotingRounds*
    tokens = name.split("_")
    n = len(tokens)
    for i, tok in enumerate(tokens if n >= 2 else []):
        if i == 0 and n - 1 < 2:
            continue  # '*_avg' style keys are too generic
        if i == 0 and tokens[1].lower() in GENERIC_SUFFIX_STARTS:
            continue  # '*_per_sec' style keys group by unit, not by what is measured
        key = "_".join(tokens[:i] + ["*"] + tokens[i + 1:])
        out.setdefault(key, 0 if tok.isdigit() else (1 if i == 0 else 2))
    return out


def assign_families(metrics):
    """Families never cross categories or topics, so the sidebar is a strict category -> topic -> family tree."""
    groups = {}
    for m in metrics:
        groups.setdefault((m.cat, m.topic), []).append(m)
    for ms in groups.values():
        names = sorted(set(m.name for m in ms))
        cand = {nm: _candidates(nm) for nm in names}
        counts = {}
        for c in cand.values():
            for key in c:
                counts[key] = counts.get(key, 0) + 1
        best = {}
        for nm in names:
            options = [(counts[k], -p, k) for k, p in cand[nm].items() if counts[k] >= 2]
            best[nm] = max(options)[2] if options else nm
        members = {}
        for nm in names:
            members.setdefault(best[nm], set()).add(nm)
        for m in ms:
            fam = best[m.name]
            m.family = fam if len(members[fam]) >= 2 else m.name


def family_sort_key(fam):
    return natural_key(fam.replace("*", "").strip("_:"))


def order_metrics(metrics, topics):
    """Categories and topics in topics.md order (then any others, 'Other' last), then family, then name."""
    cat_order, topic_order = [], {}
    for cat, topic, _, _, _ in topics:
        if cat not in cat_order:
            cat_order.append(cat)
        topic_order.setdefault((cat, topic), len(topic_order))
    for m in metrics:
        if m.cat not in cat_order:
            cat_order.append(m.cat)
    return sorted(metrics, key=lambda m: (cat_order.index(m.cat), topic_order.get((m.cat, m.topic), 10 ** 6),
                                          family_sort_key(m.family), m.family, natural_key(m.name)))


def key_metrics(metrics):
    """(found metrics in KEY_METRICS order, names not present in these files)."""
    found, missing = [], []
    for k in KEY_METRICS:
        hits = [m for m in metrics if m.name.lower() == k.lower()]
        if hits:
            found.append(hits[0])
        else:
            missing.append(k)
    return found, missing


# ---------------------------------------------------------------- groups

def load_groups(path=GROUPS_FILE):
    """'## group: <name>' heading, optional description lines, then a ``` block of selectors."""
    groups = {}
    if not os.path.exists(path):
        return groups
    name, desc, sel, in_block = None, [], [], False
    with open(path) as f:
        for line in f:
            s = line.rstrip("\n")
            m = re.match(r"^##\s+group:\s*(\S+)\s*$", s)
            if m and not in_block:
                if name:
                    groups[name] = (" ".join(desc).strip(), sel)
                name, desc, sel = m.group(1), [], []
                continue
            if name is None:
                continue
            if s.strip().startswith("```"):
                in_block = not in_block
                continue
            if in_block:
                if s.strip() and not s.strip().startswith("#"):
                    sel.append(s.strip())
            elif s.strip() and not s.startswith("#"):
                desc.append(s.strip())
    if name:
        groups[name] = (" ".join(desc).strip(), sel)
    return groups


# ---------------------------------------------------------------- menu

def build_menu(metrics, topic_descs=None):
    """Numbered entries: n = topic, n.m = family (or single metric) within it.
    Returns (entries, full_lines, overview_lines)."""
    topic_descs = topic_descs or {}
    tree = []  # [(cat, [(topic, [(family, [metrics])])])], metrics are already in display order
    for m in metrics:
        if not tree or tree[-1][0] != m.cat:
            tree.append((m.cat, []))
        topics = tree[-1][1]
        if not topics or topics[-1][0] != m.topic:
            topics.append((m.topic, []))
        fams = topics[-1][1]
        if not fams or fams[-1][0] != m.family:
            fams.append((m.family, []))
        fams[-1][1].append(m)
    entries, lines, overview, n = {}, [], [], 0
    for cat, topics in tree:
        head = "%s (%d)" % (cat, sum(len(ms) for _, fams in topics for _, ms in fams))
        lines += ["", head]
        overview += ["", head]
        for topic, fams in topics:
            n += 1
            entries[str(n)] = sorted(set(m.key for _, ms in fams for m in ms))
            title = "  [%d] %s (%d)" % (n, topic, sum(len(ms) for _, ms in fams))
            lines.append(title)
            desc = topic_descs.get((cat, topic), "no topic in references/topics.md yet")
            overview.append("%-44s %s" % (title, desc))
            for j, (fam, ms) in enumerate(fams, 1):
                ref = "%d.%d" % (n, j)
                entries[ref] = sorted(set(m.key for m in ms))
                lines.append("      %-6s %s%s" % (ref, fam, " (%d)" % len(ms) if len(ms) > 1 else ""))
    return entries, lines, overview


def write_menu(out_dir, metrics, topics):
    entries, lines, overview = build_menu(metrics, {(c, t): d for c, t, d, _, _ in topics})
    with open(os.path.join(out_dir, "menu.json"), "w") as f:
        json.dump({k: [list(x) for x in v] for k, v in entries.items()}, f)
    md = ["# Metric menu", "",
          "Pick by number: `[n]` is a whole topic, `n.m` one family or metric. Exclusions work too "
          "(\"8 except 8.3\").", "", "```"] + lines + ["```"]
    with open(os.path.join(out_dir, "menu.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    return lines, overview


def load_menu(out_dir):
    p = os.path.join(out_dir, "menu.json")
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return {k: set(tuple(x) for x in v) for k, v in json.load(f).items()}


# ---------------------------------------------------------------- selection

def read_selectors(path):
    with open(path) as f:
        return [ln.strip() for ln in f if ln.strip() and not ln.strip().startswith("#")]


def _glob(pattern, text):
    if not any(ch in pattern for ch in "*?["):
        return pattern.lower() in text.lower()
    return fnmatch.fnmatch(text.lower(), pattern.lower())


def match_selector(sel, metrics, groups, menu=None, _stack=()):
    if sel.lower() == "all":
        return set(m.key for m in metrics)
    kind, _, arg = sel.partition(":")
    kind = kind.strip().lower()
    arg = arg.strip()
    if kind == "menu":
        if menu is None:
            raise SystemExit("menu: selectors need OUT_DIR/menu.json - run catalog.py OUT_DIR --menu first.")
        refs = [arg]
        rng = re.match(r"^(\d+)\.(\d+)\s*-\s*(?:\1\.)?(\d+)$", arg)  # 9.25-9.36 or 9.25-36
        if rng:
            refs = ["%s.%d" % (rng.group(1), j) for j in range(int(rng.group(2)), int(rng.group(3)) + 1)]
        picked = set()
        for ref in refs:
            if ref not in menu:
                raise SystemExit("no menu entry %s (catalog.py OUT_DIR --menu)" % ref)
            picked |= menu[ref]
        return picked & set(m.key for m in metrics)
    if kind == "group":
        if arg not in groups:
            raise SystemExit("unknown group '%s' (catalog.py OUT --list-groups)" % arg)
        if arg in _stack:
            raise SystemExit("group cycle: %s" % " -> ".join(_stack + (arg,)))
        return resolve(groups[arg][1], metrics, groups, menu, _stack + (arg,))
    if kind == "topic":
        pat = arg.lower()
        return set(m.key for m in metrics
                   if fnmatch.fnmatch((m.cat + "/" + m.topic).lower(), pat) or fnmatch.fnmatch(m.topic.lower(), pat))
    if kind == "category":
        return set(m.key for m in metrics if fnmatch.fnmatch(m.cat.lower(), arg.lower()))
    if kind == "family":
        return set(m.key for m in metrics if fnmatch.fnmatch(m.family.lower(), arg.lower()))
    if kind == "names":
        wanted = set(x.strip().lower() for x in arg.split(",") if x.strip())
        return set(m.key for m in metrics if m.name.lower() in wanted)
    if kind == "name":
        return set(m.key for m in metrics if _glob(arg, m.name))
    if kind == "re":
        r = re.compile(arg, re.I)
        return set(m.key for m in metrics if r.search(m.name))
    if kind == "desc":
        r = re.compile(arg, re.I)
        return set(m.key for m in metrics if r.search(m.desc))
    # bare text (metric names may themselves contain ':', e.g. SmartContractService:...)
    return set(m.key for m in metrics if _glob(sel, m.name))


def resolve(selectors, metrics, groups, menu=None, _stack=()):
    inc, exc, any_inc = set(), set(), False
    for s in selectors:
        if s.startswith("-"):
            exc |= match_selector(s[1:].strip(), metrics, groups, menu, _stack)
        else:
            any_inc = True
            inc |= match_selector(s.lstrip("+").strip(), metrics, groups, menu, _stack)
    if not any_inc:
        inc = set(m.key for m in metrics)
    return inc - exc


def selected_metrics(selectors, metrics, out_dir=None, groups=None):
    menu = load_menu(out_dir) if out_dir else None
    keys = resolve(selectors, metrics, groups if groups is not None else load_groups(), menu)
    return [m for m in metrics if m.key in keys]


# ---------------------------------------------------------------- output

def write_tsv(out_dir, metrics):
    p = os.path.join(out_dir, "catalog.tsv")
    with open(p, "w") as f:
        f.write("category\ttopic\tfamily\tname\tcsv_category\tdescription\n")
        for m in metrics:
            f.write("%s\t%s\t%s\t%s\t%s\t%s\n" % (m.cat, m.topic, m.family, m.name, m.csv_cat,
                                                  m.desc.replace("\t", " ")))
    return p


def family_summary(metrics):
    """[(family, category, topic, [metrics])] in display order."""
    fams = {}
    for m in metrics:
        fams.setdefault((m.cat, m.topic, m.family), []).append(m)
    return [(f, c, t, ms) for (c, t, f), ms in fams.items()]


def print_families(metrics, category=None, multi_only=False):
    for fam, cat, topic, ms in family_summary(metrics):
        if category and not fnmatch.fnmatch(cat.lower(), category.lower()):
            continue
        if multi_only and len(ms) < 2:
            continue
        ex = "" if len(ms) < 2 else "   e.g. " + ", ".join(m.name for m in ms[:2])
        print("%-12s %-30s %4d  %s%s" % (cat, topic, len(ms), fam, ex))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--nodes", default="all")
    ap.add_argument("--menu", action="store_true")
    ap.add_argument("--full", action="store_true", help="with --menu: list every family and metric")
    ap.add_argument("--search", nargs="+")
    ap.add_argument("--families", action="store_true")
    ap.add_argument("--multi", action="store_true", help="with --families: only families with 2+ members")
    ap.add_argument("--category")
    ap.add_argument("--list-groups", action="store_true")
    ap.add_argument("--select")
    args = ap.parse_args()
    out = os.path.abspath(os.path.expanduser(args.out))

    if args.list_groups:
        groups = load_groups()
        if not groups:
            print("No named groups defined yet (%s)." % GROUPS_FILE)
        for g, (d, sel) in sorted(groups.items()):
            print("%s: %s\n    %s" % (g, d or "(no description)", "  |  ".join(sel)))
        return

    topics = load_topics()
    metrics = load_metrics(out, args.nodes, topics)

    if args.menu:
        lines, overview = write_menu(out, metrics, topics)
        groups = load_groups()
        print("Named groups: " + (", ".join(sorted(groups)) if groups else "none defined yet"))
        print("Key metrics (always charted first): " + ", ".join(KEY_METRICS))
        print("\n".join(lines if args.full else overview))
        others = [m for m in metrics if m.topic == OTHER]
        if others:
            print("\nNOTE: %d metric(s) have no topic yet (listed under 'Other'); consider adding them to %s:"
                  % (len(others), TOPICS_FILE))
            print(textwrap.fill(", ".join("%s (%s)" % (m.name, m.csv_cat) for m in others), 110,
                                initial_indent="  ", subsequent_indent="  "))
        print("\nComplete numbered menu (every family and metric, n.m entries): %s" % os.path.join(out, "menu.md"))
        return
    if args.search:
        rx = [re.compile(s, re.I) for s in args.search]
        for m in metrics:
            if any(r.search(m.name) or r.search(m.desc) for r in rx):
                print("%-12s %-30s %-45s %s" % (m.cat, m.topic, m.name, m.desc))
        return
    if args.families:
        print_families(metrics, args.category, args.multi)
        return
    if args.select:
        chosen = selected_metrics(read_selectors(args.select), metrics, out_dir=out)
        print("%d metrics selected (of %d)" % (len(chosen), len(metrics)))
        summary = family_summary(chosen)
        cur = None
        for fam, cat, topic, ms in summary:
            if (cat, topic) != cur:
                cur = (cat, topic)
                print("\n%s / %s (%d)" % (cat, topic, sum(len(x[3]) for x in summary if (x[1], x[2]) == cur)))
            print("    %s" % ("%s (%d)" % (fam, len(ms)) if len(ms) > 1 else ms[0].name))
        return

    p = write_tsv(out, metrics)
    fams = family_summary(metrics)
    cats = {}
    for m in metrics:
        cats[m.cat] = cats.get(m.cat, 0) + 1
    print("%d metrics, %d topics, %d families with 2+ members, %d single metrics" % (
        len(metrics), len(set((m.cat, m.topic) for m in metrics)),
        sum(1 for f in fams if len(f[3]) > 1), sum(1 for f in fams if len(f[3]) == 1)))
    print("categories: " + ", ".join("%s=%d" % (c or "(none)", n) for c, n in cats.items()))
    print("full list: %s" % p)


if __name__ == "__main__":
    main()
