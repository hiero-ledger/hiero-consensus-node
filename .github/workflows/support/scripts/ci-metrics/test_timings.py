#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Per-test timings from the JUnit report artifacts a run's HAPI/Otter shards upload (TEST-*.xml, kept 7 days).

Used by run_metrics.py. Rows (kind): "shard" (totals per shard), "class" (slowest classes) and "case" (slowest 30 tests
per shard). Results that Gradle restored from its build cache are counted in the shard row but get no class or case
rows. Class times are wall-clock per class; HAPI classes run concurrently on a shared network, so they overlap and
their sum exceeds the shard's duration.
"""
import importlib.util
import io
import os
import re
import time
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ARTIFACT_RE = re.compile(r"^(HAPI Test .+ Reports|.+ Otter Tests Report|Falcon Tests Report)$")
MAX_ZIP_BYTES = 40 * 1024 * 1024
MAX_MEMBER_BYTES = 300 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
MIN_CLASS_SECONDS = 2.0
SLOW_CASES_PER_SHARD = 30


def _load_collect():
    # python -I does not put the script directory on sys.path, so load the sibling module by path
    spec = importlib.util.spec_from_file_location("collect", os.path.join(HERE, "collect.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


collect = _load_collect()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def download_zip(artifact_id):
    """Artifact zips redirect to blob storage. Follow the redirect by hand so the API token is never sent there."""
    url = f"{collect.API}/actions/artifacts/{artifact_id}/zip"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {collect.token()}",
                                               "Accept": "application/vnd.github+json",
                                               "X-GitHub-Api-Version": "2022-11-28"})
    try:
        urllib.request.build_opener(_NoRedirect).open(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code != 302:
            raise RuntimeError(f"GET {url} -> {e.code}") from e
        location = e.headers["Location"]
    else:
        raise RuntimeError(f"GET {url}: expected a redirect")
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(location, timeout=180) as resp:
                data = resp.read(MAX_ZIP_BYTES + 1)
            break
        except (OSError, urllib.error.URLError) as e:  # transient network failure: bounded retry
            if attempt == 3:
                raise
            print(f"[retry {attempt}/3] artifact {artifact_id}: {type(e).__name__}", flush=True)
            time.sleep(2 * attempt)
    if len(data) > MAX_ZIP_BYTES:
        raise RuntimeError(f"artifact {artifact_id} larger than {MAX_ZIP_BYTES} bytes")
    return data


def _reject_dtd(f, filename):
    """Scan the whole file (in chunks) for DOCTYPE or ENTITY declarations, wherever they appear. ElementTree's C parser
    offers no hook to refuse them, so this is checked before parsing. Non-UTF-8 files are refused too, since the
    declarations would not show up as ASCII bytes in them."""
    head = f.read(4)
    if head[:2] in (b"\xff\xfe", b"\xfe\xff") or b"\x00" in head:
        raise ValueError(f"{filename}: only UTF-8 reports are accepted")
    tail = head
    while True:
        chunk = f.read(1 << 20)
        window = tail + chunk
        if b"<!DOCTYPE" in window or b"<!ENTITY" in window:
            raise ValueError(f"{filename}: DOCTYPE/ENTITY declarations are not accepted")
        if not chunk:
            return
        tail = window[-16:]


def parse_junit_zip(data):
    """-> (suites, cases). suites: [(class, tests, failed, skipped, suite_seconds, timestamp)],
    cases: [(class, name, seconds, outcome, suite_timestamp)].

    The zip comes from a CI run, so treat it as untrusted: size caps, no DOCTYPE or entity declarations anywhere in a file, never extracted.
    """
    suites, cases, total = [], [], 0
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for info in z.infolist():
            if not re.search(r"(^|/)TEST-[^/]+\.xml$", info.filename):
                continue
            total += info.file_size
            if info.file_size > MAX_MEMBER_BYTES or total > MAX_TOTAL_BYTES:
                raise ValueError(f"{info.filename}: report archive too large to parse")
            with z.open(info) as f:
                _reject_dtd(f, info.filename)
            pending = []  # cases of the file being read; their suite closes after them
            with z.open(info) as f:
                for _, el in ET.iterparse(f, events=("end",)):
                    if el.tag == "testcase":
                        outcome = ("failed" if el.find("failure") is not None or el.find("error") is not None
                                   else "skipped" if el.find("skipped") is not None else "passed")
                        pending.append((el.attrib["classname"], el.attrib["name"], float(el.attrib["time"]), outcome))
                        el.clear()
                    elif el.tag == "testsuite":
                        a = el.attrib
                        suites.append((a["name"], int(a["tests"]), int(a["failures"]) + int(a["errors"]),
                                       int(a["skipped"]), float(a["time"]), a["timestamp"]))
                        cases += [c + (a["timestamp"],) for c in pending]
                        pending = []
                        el.clear()
    return suites, cases


def is_cached(timestamp, run_created_at):
    """Gradle restores test results from its build cache when nothing relevant changed. The XML then carries the
    timestamp of the original execution, which is older than the run that restored it."""
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00")) < datetime.fromisoformat(run_created_at.replace("Z", "+00:00"))


def rows_for_artifact(run_id, day, sha, shard, data, run_created_at, top_classes=None):
    """Rows for one shard artifact, executed classes only. Results restored from the Gradle build cache are counted in the
    shard row (cached_classes, cached_seconds) but get no class or case rows, because they were not run in this run.
    top_classes=N keeps only the N slowest classes (compact, for every run); the default keeps every class taking at
    least MIN_CLASS_SECONDS (for sampling)."""
    suites, cases = parse_junit_zip(data)
    base = {"run_id": run_id, "day": day, "sha": sha, "shard": shard}
    live = [s for s in suites if not is_cached(s[5], run_created_at)]
    cached = [s for s in suites if is_cached(s[5], run_created_at)]
    rows = [{**base, "kind": "shard", "classes": len(suites), "tests": sum(s[1] for s in suites),
             "failed": sum(s[2] for s in suites), "skipped": sum(s[3] for s in suites),
             "suite_seconds": round(sum(s[4] for s in live), 3), "cached_classes": len(cached),
             "cached_seconds": round(sum(s[4] for s in cached), 3)}]
    ranked = sorted(live, key=lambda s: -s[4])
    for name, tests, failed, skipped, seconds, _ in (ranked[:top_classes] if top_classes else
                                                     [s for s in ranked if s[4] >= MIN_CLASS_SECONDS]):
        rows.append({**base, "kind": "class", "class": name, "tests": tests, "failed": failed, "skipped": skipped,
                     "seconds": round(seconds, 3)})
    live_cases = [c for c in cases if not is_cached(c[4], run_created_at)]
    for cls, name, seconds, outcome, _ in sorted(live_cases, key=lambda c: -c[2])[:SLOW_CASES_PER_SHARD]:
        rows.append({**base, "kind": "case", "class": cls, "case": name, "seconds": round(seconds, 3), "outcome": outcome})
    return rows


def shard_artifacts(run_id):
    """Non-expired shard report artifacts of a run; if a re-run attempt uploaded a name twice, the newest wins."""
    newest, page = {}, 1
    while True:
        data = collect.get(f"{collect.API}/actions/runs/{run_id}/artifacts?per_page=100&page={page}")
        for a in data["artifacts"]:
            if not a["expired"] and ARTIFACT_RE.match(a["name"]) and a["id"] > newest.get(a["name"], {"id": 0})["id"]:
                newest[a["name"]] = a
        if page * 100 >= data["total_count"] or not data["artifacts"]:
            return list(newest.values())
        page += 1


# What can go wrong with one report artifact. Recorded per artifact so the run's job and step data is never lost to it.
ARTIFACT_ERRORS = (ValueError, KeyError, RuntimeError, OSError, zipfile.BadZipFile, ET.ParseError)


def test_rows_for_run(run, top_classes=None, workers=1):
    """-> (rows, errors). A report that cannot be fetched or parsed is listed in errors instead of aborting the run."""
    def one(a):
        try:
            return rows_for_artifact(run["id"], run["created_at"][:10], run["head_sha"], a["name"], download_zip(a["id"]),
                                     run["created_at"], top_classes), None
        except ARTIFACT_ERRORS as e:
            print(f"::warning::skipping report artifact {a['name']!r}: {type(e).__name__}: {e}", flush=True)
            return [], {"artifact": a["name"], "error": f"{type(e).__name__}: {e}"[:300]}
    with ThreadPoolExecutor(workers) as pool:
        results = list(pool.map(one, shard_artifacts(run["id"])))
    return [row for rows, _ in results for row in rows], [err for _, err in results if err]
