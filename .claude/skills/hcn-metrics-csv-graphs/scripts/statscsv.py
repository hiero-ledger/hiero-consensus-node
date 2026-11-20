"""Shared helpers for consensus node metric CSV files.

File layout (see references/csv-format.md):
  line 1            filename:,<path>,
  lines 2..k        <metric>:,<description>,
  blank line
  category row      ,,<category>,<category>,...
  name row          ,,<metric>,<metric>,...
  data rows         ,,<value>,<value>,...

Sources are plain paths or "<archive>::<member>" for files inside .zip / .tar.gz / .tgz.
Kept compatible with Python 3.9.
"""

import csv
import gzip
import hashlib
import io
import json
import os
import re
import tarfile
import zipfile
from datetime import datetime, timezone

import numpy as np

# Java Long.MIN_VALUE / Long.MAX_VALUE are written when a metric has no value yet; they become NaN (a gap).
SENTINEL_ABS = 9.2e18
ARCHIVE_SEP = "::"
TIME_FORMAT = "%Y-%m-%d %H:%M:%S"


# ---------------------------------------------------------------- opening sources

def is_archive(path):
    p = path.lower()
    return p.endswith(".zip") or p.endswith(".tar.gz") or p.endswith(".tgz") or p.endswith(".tar")


def archive_members(path):
    """CSV-like members of an archive (nested archives are not opened)."""
    p = path.lower()
    try:
        if p.endswith(".zip"):
            with zipfile.ZipFile(path) as z:
                return [n for n in z.namelist() if _csv_like(n)]
        with tarfile.open(path, "r:*") as t:
            return [m.name for m in t.getmembers() if m.isfile() and _csv_like(m.name)]
    except (OSError, zipfile.BadZipFile, tarfile.TarError):
        return []


def _csv_like(name):
    n = name.lower()
    return n.endswith(".csv") or n.endswith(".csv.gz")


class open_text:
    """Context manager yielding a text stream for a plain, .gz, zip-member or tar-member source."""

    def __init__(self, source):
        self.source = source
        self._closers = []

    def __enter__(self):
        if ARCHIVE_SEP in self.source:
            archive, member = self.source.split(ARCHIVE_SEP, 1)
            if archive.lower().endswith(".zip"):
                z = zipfile.ZipFile(archive)
                self._closers.append(z)
                raw = z.open(member)
            else:
                t = tarfile.open(archive, "r:*")
                self._closers.append(t)
                raw = t.extractfile(member)
            self._closers.append(raw)
            if member.lower().endswith(".gz"):
                raw = gzip.GzipFile(fileobj=raw)
                self._closers.append(raw)
        elif self.source.lower().endswith(".gz"):
            raw = gzip.open(self.source, "rb")
            self._closers.append(raw)
        else:
            raw = open(self.source, "rb")
            self._closers.append(raw)
        text = io.TextIOWrapper(raw, encoding="utf-8", errors="replace", newline="")
        self._closers.append(text)
        return text

    def __exit__(self, *exc):
        for c in reversed(self._closers):
            try:
                c.close()
            except Exception:
                pass
        return False


def source_fingerprint(source):
    """Changes when the underlying file changes; used to invalidate the cache."""
    path = source.split(ARCHIVE_SEP, 1)[0]
    st = os.stat(path)
    return "%s|%d|%d" % (source, st.st_size, int(st.st_mtime))


def source_label(source):
    return source.split(ARCHIVE_SEP, 1)[-1]


# ---------------------------------------------------------------- header

def looks_like_stats(source):
    try:
        with open_text(source) as fh:
            return fh.readline().startswith("filename:")
    except (OSError, EOFError, KeyError, tarfile.TarError, zipfile.BadZipFile, gzip.BadGzipFile):
        return False


def read_header(fh):
    """Consume the header from an open text stream. Returns (desc, cats, names)."""
    reader = csv.reader(fh)
    desc = {}
    first = True
    for row in reader:
        if not row or all(x.strip() == "" for x in row):
            break
        if first:
            first = False
            continue  # filename:,... line
        name = row[0][:-1] if row[0].endswith(":") else row[0]
        desc[name] = row[1] if len(row) > 1 else ""
    cats = next(reader)
    names = next(reader)
    return desc, cats, names, reader


def time_column(cats, names):
    hits = [i for i, n in enumerate(names) if n == "time"]
    for i in hits:
        if cats[i] == "platform:info":
            return i
    return hits[0] if hits else None


def parse_time(s):
    s = s.strip()
    if s.endswith(" UTC"):
        s = s[:-4]
    try:
        return datetime.strptime(s, TIME_FORMAT).replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        return float("nan")


def fmt_time(epoch, with_date=True):
    if epoch is None or epoch != epoch:
        return "?"
    d = datetime.fromtimestamp(epoch, tz=timezone.utc)
    return d.strftime("%Y-%m-%d %H:%M:%S" if with_date else "%H:%M:%S")


def parse_user_time(s, reference_epoch=None):
    """Accepts 'YYYY-MM-DD HH:MM[:SS]' (UTC) or 'HH:MM[:SS]' (date taken from reference_epoch)."""
    s = s.strip()
    if s.upper().endswith("UTC"):
        s = s[:-3].strip()
    s = re.sub(r"^(\d{4}-\d{2}-\d{2})T", r"\1 ", s)  # ISO 'YYYY-MM-DDTHH:MM'
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(s, fmt).replace(tzinfo=timezone.utc).timestamp()
        except ValueError:
            pass
    if reference_epoch is not None:
        base = datetime.fromtimestamp(reference_epoch, tz=timezone.utc)
        for fmt in ("%H:%M:%S", "%H:%M"):
            try:
                t = datetime.strptime(s, fmt)
                d = base.replace(hour=t.hour, minute=t.minute, second=t.second)
                return d.timestamp()
            except ValueError:
                pass
    raise ValueError("unrecognised time: %r" % s)


def guess_node_from_name(source):
    m = re.findall(r"(\d+)", os.path.basename(source_label(source)))
    return int(m[-1]) if m else None


def read_member_id(source):
    """Node id from the platform:info memberID column (first data row that has it)."""
    with open_text(source) as fh:
        _, cats, names, reader = read_header(fh)
        idx = [i for i, n in enumerate(names) if n == "memberID"]
        if not idx:
            return None
        i = idx[0]
        for n, row in enumerate(reader):
            if i < len(row) and row[i].strip():
                try:
                    return int(float(row[i]))
                except ValueError:
                    pass
            if n > 50:
                break
    return None


# ---------------------------------------------------------------- cache

def cache_key(source):
    return hashlib.sha1(source_fingerprint(source).encode()).hexdigest()[:16]


def cache_paths(cache_dir, source):
    k = cache_key(source)
    return (os.path.join(cache_dir, k + ".meta.json"),
            os.path.join(cache_dir, k + ".values.npy"),
            os.path.join(cache_dir, k + ".time.npy"))


def is_cached(cache_dir, source):
    return all(os.path.exists(p) for p in cache_paths(cache_dir, source))


def build_cache(source, cache_dir, node_id=None):
    """Parse a whole CSV once into a column-major float64 matrix (+ epoch-seconds time vector)."""
    os.makedirs(cache_dir, exist_ok=True)
    meta_p, val_p, time_p = cache_paths(cache_dir, source)
    rows = []
    times = []
    skipped = 0
    with open_text(source) as fh:
        desc, cats, names, reader = read_header(fh)
        width = len(names)
        ti = time_column(cats, names)
        for row in reader:
            if len(row) < 3:
                skipped += 1
                continue
            if len(row) < width:
                row = row + [""] * (width - len(row))
            elif len(row) > width:
                row = row[:width]
            t = parse_time(row[ti]) if ti is not None else float("nan")
            cells = [c if c.strip() != "" else "nan" for c in row]
            if ti is not None:
                cells[ti] = "nan"
            try:
                vals = np.array(cells, dtype=np.float64)
            except ValueError:
                vals = np.array([_to_float(c) for c in cells], dtype=np.float64)
            if t != t and not np.isfinite(vals).any():
                skipped += 1  # repeated header / definitions after a restart
                continue
            rows.append(vals)
            times.append(t)
    if rows:
        m = np.vstack(rows)
    else:
        m = np.zeros((0, len(names)))
    m[np.abs(m) >= SENTINEL_ABS] = np.nan
    m[~np.isfinite(m)] = np.nan  # Infinity in a cell is not a usable value either; treat it as a gap
    np.save(val_p, np.asfortranarray(m))
    np.save(time_p, np.array(times, dtype=np.float64))
    tt = np.array(times, dtype=np.float64)
    finite = tt[np.isfinite(tt)]
    meta = {
        "source": source,
        "fingerprint": source_fingerprint(source),
        "node": node_id,
        "cats": cats,
        "names": names,
        "desc": desc,
        "rows": int(m.shape[0]),
        "skipped_rows": skipped,
        "t_first": float(finite.min()) if finite.size else None,
        "t_last": float(finite.max()) if finite.size else None,
    }
    with open(meta_p, "w") as f:
        json.dump(meta, f)
    return meta


def _to_float(c):
    try:
        return float(c)
    except ValueError:
        return float("nan")


class NodeData:
    """Cached data for one node. values is memory-mapped; columns are contiguous."""

    def __init__(self, cache_dir, source, node_id):
        meta_p, val_p, time_p = cache_paths(cache_dir, source)
        with open(meta_p) as f:
            self.meta = json.load(f)
        self.node = node_id
        self.source = source
        self.values = np.load(val_p, mmap_mode="r")
        self.time = np.load(time_p)
        self.names = self.meta["names"]
        self.cats = self.meta["cats"]
        self.desc = self.meta["desc"]
        self.index = {}
        for i, (c, n) in enumerate(zip(self.cats, self.names)):
            if n:
                self.index.setdefault((c, n), i)
        self.by_name = {}
        for i, n in enumerate(self.names):
            if n:
                self.by_name.setdefault(n, i)

    def column(self, key):
        """key is (category, name) or a bare name. Returns float64 array or None."""
        i = self.index.get(key) if isinstance(key, tuple) else self.by_name.get(key)
        if i is None:
            return None
        return np.asarray(self.values[:, i])


# ---------------------------------------------------------------- nodes.json

def load_nodes(out_dir):
    p = os.path.join(out_dir, "nodes.json")
    if not os.path.exists(p):
        raise SystemExit("No %s - run discover.py first." % p)
    with open(p) as f:
        return json.load(f)


def selected_nodes(nodes_doc, wanted):
    """wanted: None/'all' or comma list of node ids."""
    entries = [e for e in nodes_doc["nodes"] if not e.get("ignored")]
    if wanted and wanted != "all":
        ids = set(int(x) for x in str(wanted).split(",") if x.strip() != "")
        entries = [e for e in entries if e["node"] in ids]
        missing = ids - set(e["node"] for e in entries)
        if missing:
            raise SystemExit("Unknown node id(s): %s" % sorted(missing))
    return sorted(entries, key=lambda e: (e["node"] is None, e["node"] or 0))


def cache_dir_for(out_dir):
    return os.path.join(out_dir, ".cache")


def open_nodes(out_dir, wanted=None):
    doc = load_nodes(out_dir)
    cdir = cache_dir_for(out_dir)
    result = []
    for e in selected_nodes(doc, wanted):
        if not is_cached(cdir, e["source"]):
            raise SystemExit("Node %s is not cached (or its file changed) - run prepare.py." % e["node"])
        result.append(NodeData(cdir, e["source"], e["node"]))
    return result
