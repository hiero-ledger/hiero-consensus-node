#!/usr/bin/env python3
"""Metric catalog: families, ordering, search, named groups and selection files.

A *family* is a set of metrics whose names are identical except for one '_'-separated
token, e.g. '*_unhandled_task_count' (component name differs), 'bytes_per_sec_sent_*'
(peer index differs), 'ds_compactions_level_*_hashesTimeMs_state' (level differs).
Charts are ordered by category, then family, then member, so related metrics sit together.

Usage:
  catalog.py OUT_DIR                       write OUT_DIR/catalog.tsv, print a summary
  catalog.py OUT_DIR --search TEXT ...     find metrics by name/description (case-insensitive regex)
  catalog.py OUT_DIR --families [--multi] [--category CAT]
                                           list families (--multi: only those with 2+ members)
  catalog.py OUT_DIR --list-groups         named groups from references/metric-groups.md
  catalog.py OUT_DIR --select FILE         resolve a selection file and print what it picks
All forms except --list-groups accept --nodes 0,3 (default: all nodes in nodes.json).

Selection file: one selector per line, '#' comments, prefix '-' to exclude.
  all                     every metric
  group:<name>            a named group from references/metric-groups.md
  category:<glob>         e.g. category:merkle_db
  family:<glob>           e.g. family:*_unhandled_task_count
  name:<glob>             glob on the metric name, e.g. name:*Latency*
  re:<regex>              regex search on the metric name
  desc:<regex>            regex search on the description
  <text>                  same as name:<text>; plain text without wildcards matches as a substring
If there are only exclusions, they are applied to all metrics. Matching is case-insensitive.
"""

import argparse
import fnmatch
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statscsv as sc  # noqa: E402

SKILL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROUPS_FILE = os.path.join(SKILL_DIR, "references", "metric-groups.md")
SKIP_NAMES = {"", "time"}
# A shared suffix starting with one of these words only says what *kind* of number a metric is
# (a rate or a ratio), not what it measures, so '*_per_sec' is not a family: trans_per_sec and
# syncs_per_sec measure unrelated things. Add words here if other unit-like suffixes show up.
GENERIC_SUFFIX_STARTS = ("per",)
# Always charted, first, in every report (and also with their family when it is selected).
# Matched case-insensitively on the metric name; edit this list to change the key metrics.
KEY_METRICS = ("trans_per_sec", "secC2RC", "secTransH", "PlatformStatus")


# ---------------------------------------------------------------- metrics + families

class Metric(object):
    __slots__ = ("cat", "name", "desc", "family")

    def __init__(self, cat, name, desc):
        self.cat, self.name, self.desc = cat, name, desc
        self.family = name

    @property
    def key(self):
        return (self.cat, self.name)


def natural_key(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def load_metrics(out_dir, wanted_nodes=None):
    """Union of metrics over the selected nodes' headers, in first-seen order."""
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
    assign_families(metrics)
    return order_metrics(metrics)


def _candidates(tokens):
    """(key, preference) for every single-token wildcard that leaves a specific enough name."""
    out = []
    n = len(tokens)
    if n < 2:
        return out
    for i, tok in enumerate(tokens):
        fixed = n - 1
        if i == 0 and fixed < 2:
            continue  # '*_avg' style keys are too generic
        if i == 0 and tokens[1].lower() in GENERIC_SUFFIX_STARTS:
            continue  # '*_per_sec' style keys group by unit, not by what is measured
        key = "_".join(tokens[:i] + ["*"] + tokens[i + 1:])
        pref = 0 if tok.isdigit() else (1 if i == 0 else 2)
        out.append((key, pref))
    return out


def assign_families(metrics):
    """Families never cross categories, so the report sidebar is always category -> family."""
    by_cat = {}
    for m in metrics:
        by_cat.setdefault(m.cat, []).append(m)
    for ms in by_cat.values():
        names = sorted(set(m.name for m in ms))
        counts = {}
        cand = {}
        for nm in names:
            c = _candidates(nm.split("_"))
            cand[nm] = c
            for key, _ in c:
                counts[key] = counts.get(key, 0) + 1
        best = {}
        for nm in names:
            options = [(counts[k], -p, k) for k, p in cand[nm] if counts[k] >= 2]
            best[nm] = max(options)[2] if options else nm
        members = {}
        for nm in names:
            members.setdefault(best[nm], set()).add(nm)
        for m in ms:
            fam = best[m.name]
            m.family = fam if len(members[fam]) >= 2 else m.name


def family_sort_key(fam):
    return natural_key(fam.replace("*", "").strip("_:"))


def order_metrics(metrics):
    cat_order = []
    for m in metrics:
        if m.cat not in cat_order:
            cat_order.append(m.cat)
    return sorted(metrics, key=lambda m: (cat_order.index(m.cat), family_sort_key(m.family),
                                          m.family, natural_key(m.name)))


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


# ---------------------------------------------------------------- selection

def read_selectors(path):
    with open(path) as f:
        return [ln.strip() for ln in f if ln.strip() and not ln.strip().startswith("#")]


def _glob(pattern, text):
    if not any(ch in pattern for ch in "*?["):
        return pattern.lower() in text.lower()
    return fnmatch.fnmatch(text.lower(), pattern.lower())


def match_selector(sel, metrics, groups, _stack=()):
    if sel == "all":
        return set(m.key for m in metrics)
    kind, _, arg = sel.partition(":")
    kind = kind.strip().lower()
    arg = arg.strip()
    if kind == "group":
        if arg not in groups:
            raise SystemExit("unknown group '%s' (catalog.py OUT --list-groups)" % arg)
        if arg in _stack:
            raise SystemExit("group cycle: %s" % " -> ".join(_stack + (arg,)))
        return resolve(groups[arg][1], metrics, groups, _stack + (arg,))
    if kind == "category":
        return set(m.key for m in metrics if fnmatch.fnmatch(m.cat.lower(), arg.lower()))
    if kind == "family":
        return set(m.key for m in metrics if fnmatch.fnmatch(m.family.lower(), arg.lower()))
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


def resolve(selectors, metrics, groups, _stack=()):
    inc, exc, any_inc = set(), set(), False
    for s in selectors:
        if s.startswith("-"):
            exc |= match_selector(s[1:].strip(), metrics, groups, _stack)
        else:
            any_inc = True
            inc |= match_selector(s.lstrip("+").strip(), metrics, groups, _stack)
    if not any_inc:
        inc = set(m.key for m in metrics)
    return inc - exc


def selected_metrics(selectors, metrics, groups=None):
    keys = resolve(selectors, metrics, groups if groups is not None else load_groups())
    return [m for m in metrics if m.key in keys]


# ---------------------------------------------------------------- output

def write_tsv(out_dir, metrics):
    p = os.path.join(out_dir, "catalog.tsv")
    with open(p, "w") as f:
        f.write("category\tfamily\tname\tdescription\n")
        for m in metrics:
            f.write("%s\t%s\t%s\t%s\n" % (m.cat, m.family, m.name, m.desc.replace("\t", " ")))
    return p


def family_summary(metrics):
    fams = {}
    for m in metrics:
        fams.setdefault((m.cat, m.family), []).append(m)
    return [(f, c, ms) for (c, f), ms in fams.items()]


def print_families(metrics, category=None, multi_only=False):
    for fam, cat, ms in family_summary(metrics):
        if category and not fnmatch.fnmatch(cat.lower(), category.lower()):
            continue
        if multi_only and len(ms) < 2:
            continue
        ex = "" if len(ms) < 2 else "   e.g. " + ", ".join(m.name for m in ms[:2])
        print("%-14s %4d  %s%s" % (cat, len(ms), fam, ex))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--nodes", default="all")
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

    metrics = load_metrics(out, args.nodes)

    if args.search:
        rx = [re.compile(s, re.I) for s in args.search]
        for m in metrics:
            if any(r.search(m.name) or r.search(m.desc) for r in rx):
                print("%-14s %-45s %-50s %s" % (m.cat, m.family, m.name, m.desc))
        return
    if args.families:
        print_families(metrics, args.category, args.multi)
        return
    if args.select:
        chosen = selected_metrics(read_selectors(args.select), metrics)
        fams = family_summary(chosen)
        print("%d metrics in %d families/singletons selected (of %d)\n" % (len(chosen), len(fams), len(metrics)))
        cats = {}
        for m in chosen:
            cats[m.cat] = cats.get(m.cat, 0) + 1
        print("by category: " + ", ".join("%s=%d" % kv for kv in cats.items()) + "\n")
        print_families(chosen)
        return

    p = write_tsv(out, metrics)
    fams = family_summary(metrics)
    cats = {}
    for m in metrics:
        cats[m.cat] = cats.get(m.cat, 0) + 1
    print("%d metrics, %d families with 2+ members, %d singletons" % (
        len(metrics), sum(1 for f in fams if len(f[2]) > 1), sum(1 for f in fams if len(f[2]) == 1)))
    print("categories: " + ", ".join("%s=%d" % (c or "(none)", n) for c, n in cats.items()))
    print("full list: %s" % p)


if __name__ == "__main__":
    main()
