#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Turn collect.py output (runs.jsonl, jobs.jsonl) into metrics.json for report.py.

Definitions (all durations in seconds unless suffixed _min):
  runner time   = completed_at - started_at of every non-skipped job, all attempts (reruns cost too)
  queue time    = started_at - created_at of every non-skipped job (waiting for a runner)
  wall clock    = latest attempt: max(job completed_at) - run created_at
  wasted time   = runner time of jobs that ended failure/cancelled/timed_out, plus all runner time of
                  superseded attempts (run_attempt < the run's latest attempt)

Usage: analyze.py --data DIR --out metrics.json
"""
import argparse
import json
import re
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

RUNNER_TIME_CONCLUSIONS = {"success", "failure", "cancelled", "timed_out"}
FAILED = {"failure", "cancelled", "timed_out"}
OVERHEAD_STEP_PREFIXES = ("Set up job", "Prepare Runner", "Post ", "Complete job", "Initialize GitHub Job", "Harden Runner",
                          "Checkout", "Upload", "Publish")


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def pct(values, p):
    if not values:
        raise ValueError("percentile of empty list")
    v = sorted(values)
    k = (len(v) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def pr_action(display_title):
    """Event action from the run title `PR #123 <action>: <title>` that 600 sets (run-name); None for older runs."""
    m = re.match(r"PR #\d+ (opened|reopened|synchronize|labeled|ready_for_review|edited): ", display_title or "")
    return m.group(1) if m else None


def week_of(dt):
    iso = dt.isocalendar()
    return f"{iso.year}-W{iso.week:02d}"


def runner_class(job):
    if not job["labels"]:
        raise ValueError(f"job {job['id']} ({job['name']}) ran but has no runner labels")
    return job["labels"][0]


def stage_of(name):
    """'MATS / HAPI Tests / Hapi (Crypto)' -> 'MATS / HAPI Tests'. Groups matrix legs under their stage."""
    parts = [p.strip() for p in name.split(" / ")]
    return " / ".join(parts[:2])


def write_history(path, run_rows, job_rows):
    """Merge this window's per-run rows into the append-only history file (keyed by run id)."""
    stage_by_run = defaultdict(lambda: defaultdict(lambda: {"min": 0.0, "queue_min": 0.0, "wasted_min": 0.0, "jobs": 0,
                                                            "stepped_min": 0.0, "setup_min": 0.0}))
    for j in job_rows:
        x = stage_by_run[j["run_id"]][(j["stage"], j["class"])]
        x["min"] += j["dur"] / 60
        x["queue_min"] += j["queue"] / 60
        x["jobs"] += 1
        if j["steps"]:  # step detail expires after ~18 days, so keep the setup share of each stage in the history
            x["stepped_min"] += j["dur"] / 60
            x["setup_min"] += sum((ts(st["completed_at"]) - ts(st["started_at"])).total_seconds() for st in j["steps"]
                                  if st["started_at"] and st["completed_at"] and st["name"].startswith(OVERHEAD_STEP_PREFIXES)) / 60
        if j["conclusion"] in FAILED or j["redone"]:
            x["wasted_min"] += j["dur"] / 60
    hist = {}
    try:
        with open(path) as f:
            for l in f:
                row = json.loads(l)
                hist[row["id"]] = row
    except FileNotFoundError:
        pass  # first run: no history yet
    jobs_of = defaultdict(list)
    for j in job_rows:
        jobs_of[j["run_id"]].append(j)
    for r in run_rows:
        hist[r["id"]] = {k: r[k] for k in ("id", "suite", "workflow", "event", "conclusion", "day", "wall", "runner",
                                           "queue", "wasted", "attempts", "pr", "action")} | {
            # one row per HAPI/Otter shard job, so a shard that is added or slows down shows up over time
            "shards": [{"leg": j["name"].split(" / ")[-1], "class": j["class"], "min": round(j["dur"] / 60, 3),
                        "queue_min": round(j["queue"] / 60, 3), "conclusion": j["conclusion"], "attempt": j["attempt"]}
                       for j in jobs_of[r["id"]] if j["stage"].endswith(("HAPI Tests", "Otter Tests"))],
            "stages": [{"stage": st, "class": cl, **{k: round(v, 3) for k, v in x.items()}}
                       for (st, cl), x in sorted(stage_by_run[r["id"]].items())]}
    with open(path, "w") as f:
        for rid in sorted(hist):
            f.write(json.dumps(hist[rid], separators=(",", ":")) + "\n")
    print(f"history: {len(hist)} runs in {path}")


def build_rows(runs, jobs_by_run, empty, allow_missing=False):
    """Turn raw runs and jobs (collect.py shapes) into per-run and per-job rows plus jobs that never got a runner.

    Shared by the analysis and by ingest.py, so the SQLite database and the report use the same definitions.
    """
    run_rows, job_rows, never_started = [], [], []
    for rid, r in runs.items():
        if r["status"] != "completed":
            continue
        jobs = jobs_by_run.get(rid, [])
        if not jobs:
            if rid not in empty and allow_missing:
                continue
            if rid not in empty:
                raise RuntimeError(f"completed run {rid} has no jobs and is not recorded as empty")
            continue
        latest = max(j["run_attempt"] for j in jobs)
        # "Re-run failed jobs" copies the already-passed jobs into the new attempt with their ORIGINAL
        # started/completed times but a fresh created_at, so started_at < created_at. They did not execute
        # again; counting them would double-bill the run.
        carried = {j["id"] for j in jobs if j["run_attempt"] > 1 and j["started_at"] and j["created_at"]
                   and ts(j["started_at"]) < ts(j["created_at"])}
        ran = [j for j in jobs if j["id"] not in carried
               and j["conclusion"] in RUNNER_TIME_CONCLUSIONS and j["runner_name"]  # "" or None = never got a runner
               and j["started_at"] and j["completed_at"]]
        for j in jobs:
            # cancelled/failed without ever being assigned a runner: waited in queue (e.g. a runner label nothing serves)
            if j["id"] not in carried and j["conclusion"] in ("cancelled", "failure", "timed_out") and not j["runner_name"] \
                    and j["created_at"] and j["completed_at"]:
                never_started.append({"suite": r["suite"], "run_id": rid, "name": j["name"], "label": (j["labels"] or ["?"])[0],
                                      "queued_h": (ts(j["completed_at"]) - ts(j["created_at"])).total_seconds() / 3600})
        if not ran:
            continue  # every job skipped: costs nothing, says nothing about duration
        # a job is redone if a later attempt really re-executed a job with the same name
        reexec = defaultdict(set)
        for j in ran:
            reexec[j["name"]].add(j["run_attempt"])
        runner_s = queue_s = wasted_s = 0.0
        for j in ran:
            dur = (ts(j["completed_at"]) - ts(j["started_at"])).total_seconds()
            q = (ts(j["started_at"]) - ts(j["created_at"])).total_seconds()
            if dur < 0 or q < 0:
                raise ValueError(f"negative duration in job {j['id']}")
            redone = any(a > j["run_attempt"] for a in reexec[j["name"]])
            runner_s += dur
            queue_s += q
            if redone or j["conclusion"] in FAILED:
                wasted_s += dur
            job_rows.append({
                "run_id": rid, "suite": r["suite"], "workflow": r["workflow"], "name": j["name"],
                "stage": stage_of(j["name"]), "class": runner_class(j), "dur": dur, "queue": q,
                "conclusion": j["conclusion"], "attempt": j["run_attempt"], "latest": latest, "redone": redone,
                "created": j["created_at"], "started": j["started_at"], "completed": j["completed_at"],
                "steps": j["steps"],
            })
        top_attempt = max(j["run_attempt"] for j in ran)  # latest attempt that actually executed something
        last = [j for j in ran if j["run_attempt"] == top_attempt]
        wall = (max(ts(j["completed_at"]) for j in last) - min(ts(j["created_at"]) for j in last)).total_seconds()
        created = ts(r["created_at"])
        run_rows.append({
            "id": rid, "suite": r["suite"], "workflow": r["workflow"], "event": r["event"],
            "conclusion": r["conclusion"], "day": created.strftime("%Y-%m-%d"), "week": week_of(created),
            "wall": wall, "runner": runner_s, "queue": queue_s, "wasted": wasted_s, "attempts": latest,
            "pr": (r["pr_numbers"] or [None])[0], "url": r["html_url"], "sha": r["head_sha"],
            "action": pr_action(r.get("display_title")),
        })
    return run_rows, job_rows, never_started


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", help="metrics.json to write (required unless --history-only)")
    ap.add_argument("--history-only", action="store_true",
                    help="only update --history; skip the aggregates (a short window may lack a suite entirely)")
    ap.add_argument("--history", help="append-only per-run history file (jsonl). Rows for runs in this window are "
                    "replaced, older rows are kept, so history survives beyond the 90 day API window.")
    ap.add_argument("--allow-missing", action="store_true",
                    help="skip completed runs whose jobs were not collected yet (partial data, for testing only)")
    args = ap.parse_args()
    if args.history_only and not args.history:
        ap.error("--history-only needs --history")
    if not args.history_only and not args.out:
        ap.error("--out is required unless --history-only")

    runs = {}
    with open(f"{args.data}/runs.jsonl") as f:
        for l in f:
            r = json.loads(l)
            runs[r["id"]] = r
    jobs_by_run = defaultdict(list)
    with open(f"{args.data}/jobs.jsonl") as f:
        for l in f:
            j = json.loads(l)
            jobs_by_run[j["run_id"]].append(j)
    with open(f"{args.data}/empty_runs.txt") as f:
        empty = {int(x) for x in f.read().split()}

    run_rows, job_rows, never_started = build_rows(runs, jobs_by_run, empty, args.allow_missing)

    if args.history_only:
        write_history(args.history, run_rows, job_rows)
        return

    # ---------------------------------------------------------- per-suite aggregates
    out = {"generated": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"), "suites": {}}
    all_days = sorted({r["day"] for r in run_rows})
    out["window"] = [all_days[0], all_days[-1]]

    # days with no runs at all across the collected workflows (3+ consecutive): an outage, a pause, or deleted runs
    d0 = datetime.strptime(all_days[0], "%Y-%m-%d")
    day_set = set(all_days)
    gaps, cur = [], []
    for k in range((datetime.strptime(all_days[-1], "%Y-%m-%d") - d0).days + 1):
        d = (d0 + timedelta(days=k)).strftime("%Y-%m-%d")
        if d in day_set:
            if len(cur) >= 3:
                gaps.append({"start": cur[0], "end": cur[-1], "days": len(cur)})
            cur = []
        else:
            cur.append(d)
    out["gaps"] = gaps
    with_steps = [j["started"] for j in job_rows if j["steps"]]
    out["step_data_from"] = min(with_steps)[:10] if with_steps else None

    for suite in ("MATS", "XTS"):
        sr = [r for r in run_rows if r["suite"] == suite]
        sj = [j for j in job_rows if j["suite"] == suite]
        # the controllers (900/901/227) are cron prime/dispatch shells; keep them out of duration stats
        # but inside cost totals so the cost picture is complete.
        dur_runs = [r for r in sr if r["workflow"] not in ("900: Ext Test Suite cron", "901: Promote Build cron",
                                                          "227: Daily Controllers")]

        by_wf = {}
        for wf in sorted({r["workflow"] for r in sr}):
            rs = [r for r in sr if r["workflow"] == wf]
            by_wf[wf] = {
                "runs": len(rs),
                "success": sum(r["conclusion"] == "success" for r in rs),
                "failure": sum(r["conclusion"] == "failure" for r in rs),
                "cancelled": sum(r["conclusion"] == "cancelled" for r in rs),
                "runner_min": sum(r["runner"] for r in rs) / 60,
                "wasted_min": sum(r["wasted"] for r in rs) / 60,
                "wall_p50_min": pct([r["wall"] for r in rs], .5) / 60,
                "wall_p90_min": pct([r["wall"] for r in rs], .9) / 60,
                "runner_p50_min": pct([r["runner"] for r in rs], .5) / 60,
            }

        weeks = sorted({r["week"] for r in sr})
        weekly = []
        for w in weeks:
            rs = [r for r in sr if r["week"] == w]
            drs = [r for r in dur_runs if r["week"] == w]
            jw = [j for j in sj if week_of(ts(j["created"])) == w]
            by_class = defaultdict(float)
            for j in jw:
                by_class[j["class"]] += j["dur"] / 60
            days_in_week = {r["day"] for r in rs}
            monday = datetime.strptime(w + "-1", "%G-W%V-%u").replace(tzinfo=timezone.utc)
            first, last_day = datetime.strptime(all_days[0], "%Y-%m-%d").replace(tzinfo=timezone.utc), \
                datetime.strptime(all_days[-1], "%Y-%m-%d").replace(tzinfo=timezone.utc)
            weekly.append({
                "week": w, "runs": len(rs), "monday": monday.strftime("%Y-%m-%d"),
                # a week not fully inside the collected window would understate totals
                "partial": monday < first or (monday + timedelta(days=6)) > last_day,
                "runner_min": sum(r["runner"] for r in rs) / 60,
                "wasted_min": sum(r["wasted"] for r in rs) / 60,
                "queue_min": sum(r["queue"] for r in rs) / 60,
                "wall_p50_min": pct([r["wall"] for r in drs], .5) / 60 if drs else None,
                "wall_p90_min": pct([r["wall"] for r in drs], .9) / 60 if drs else None,
                "queue_job_p50_s": pct([j["queue"] for j in jw], .5) if jw else None,
                "queue_job_p90_s": pct([j["queue"] for j in jw], .9) if jw else None,
                "success_rate": sum(r["conclusion"] == "success" for r in drs) / len(drs) if drs else None,
                "by_class_min": dict(by_class),
            })

        daily = defaultdict(lambda: {"runs": 0, "runner_min": 0.0})
        for r in sr:
            daily[r["day"]]["runs"] += 1
            daily[r["day"]]["runner_min"] += r["runner"] / 60

        # ---- jobs aggregated by name
        agg = defaultdict(list)
        for j in sj:
            agg[j["name"]].append(j)
        jobs_out = []
        for name, js in agg.items():
            durs = [j["dur"] for j in js]
            jobs_out.append({
                "name": name, "stage": js[0]["stage"], "class": js[0]["class"], "count": len(js),
                "total_min": sum(durs) / 60, "mean_min": statistics.fmean(durs) / 60,
                "p90_min": pct(durs, .9) / 60, "max_min": max(durs) / 60,
                "queue_mean_s": statistics.fmean(j["queue"] for j in js),
                "queue_total_min": sum(j["queue"] for j in js) / 60,
                "fail_rate": sum(j["conclusion"] == "failure" for j in js) / len(js),
                "cancel_rate": sum(j["conclusion"] == "cancelled" for j in js) / len(js),
                "wasted_min": sum(j["dur"] for j in js if j["conclusion"] in FAILED or j["redone"]) / 60,
            })
        jobs_out.sort(key=lambda x: -x["total_min"])

        # ---- stage aggregates (matrix legs rolled up)
        stg = defaultdict(lambda: {"total_min": 0.0, "jobs": 0, "queue_min": 0.0, "wasted_min": 0.0})
        for j in sj:
            s = stg[j["stage"]]
            s["total_min"] += j["dur"] / 60
            s["queue_min"] += j["queue"] / 60
            s["jobs"] += 1
            if j["conclusion"] in FAILED or j["redone"]:
                s["wasted_min"] += j["dur"] / 60
        stages_out = sorted(({"stage": k, **v} for k, v in stg.items()), key=lambda x: -x["total_min"])

        # ---- runner classes
        cls = defaultdict(list)
        for j in sj:
            cls[j["class"]].append(j)
        classes_out = sorted((
            {"class": c, "jobs": len(js), "runner_min": sum(j["dur"] for j in js) / 60,
             "queue_min": sum(j["queue"] for j in js) / 60,
             "queue_p50_s": pct([j["queue"] for j in js], .5), "queue_p90_s": pct([j["queue"] for j in js], .9),
             "queue_max_s": max(j["queue"] for j in js)} for c, js in cls.items()),
            key=lambda x: -x["runner_min"])

        # ---- critical path: per run (latest attempt), walk back from the last-finishing job through the
        # latest-finishing job that completed before the current one started.
        crit = defaultdict(lambda: {"on_path": 0, "path_min": 0.0})
        by_run = defaultdict(list)
        for j in sj:
            if j["latest"] == 1:  # critical path only meaningful for runs without reruns
                by_run[j["run_id"]].append(j)
        n_path_runs = 0
        for rid, js in by_run.items():
            if runs[rid]["workflow"] in ("900: Ext Test Suite cron", "901: Promote Build cron", "227: Daily Controllers"):
                continue
            n_path_runs += 1
            cur = max(js, key=lambda j: j["completed"])
            seen, stages_here = set(), set()
            while cur and id(cur) not in seen:
                seen.add(id(cur))
                span = (ts(cur["completed"]) - ts(cur["created"])).total_seconds() / 60
                stages_here.add(cur["stage"])
                crit[cur["stage"]]["path_min"] += span
                prev = [j for j in js if ts(j["completed"]) <= ts(cur["started"]) and id(j) not in seen]
                cur = max(prev, key=lambda j: j["completed"]) if prev else None
            for st in stages_here:
                crit[st]["on_path"] += 1
        crit_out = sorted(({"stage": k, "runs_on_path": v["on_path"], "share_of_runs": v["on_path"] / n_path_runs,
                            "mean_path_min": v["path_min"] / v["on_path"], "total_path_min": v["path_min"]}
                           for k, v in crit.items()), key=lambda x: -x["total_path_min"])

        # ---- step breakdown for the 12 most expensive job names
        steps_out = []
        for jo in jobs_out[:12]:
            st = defaultdict(lambda: [0.0, 0])
            for j in agg[jo["name"]]:
                for s in j["steps"]:
                    if s["started_at"] and s["completed_at"] and s["conclusion"] in ("success", "failure"):
                        st[s["name"]][0] += (ts(s["completed_at"]) - ts(s["started_at"])).total_seconds()
                        st[s["name"]][1] += 1
            top = sorted(st.items(), key=lambda kv: -kv[1][0])[:8]
            steps_out.append({"job": jo["name"], "total_min": jo["total_min"],
                              "steps": [{"step": k, "total_min": v[0] / 60, "mean_s": v[0] / v[1], "n": v[1]}
                                        for k, v in top]})

        # ---- waste
        failed_runs = [r for r in sr if r["conclusion"] in ("failure", "cancelled")]
        waste = {
            "total_min": sum(r["runner"] for r in sr) / 60,
            # non-overlapping buckets of runner time that did not contribute to a final passing result
            "cancelled_min": sum(j["dur"] for j in sj if j["conclusion"] == "cancelled") / 60,
            "failed_min": sum(j["dur"] for j in sj if j["conclusion"] in ("failure", "timed_out")) / 60,
            "redone_ok_min": sum(j["dur"] for j in sj if j["conclusion"] == "success" and j["redone"]) / 60,
            "rerun_runs": sum(r["attempts"] > 1 for r in sr),
            "cancelled_runs": sum(r["conclusion"] == "cancelled" for r in sr),
            "failed_runs": sum(r["conclusion"] == "failure" for r in sr),
            "by_event": {},
        }
        ns = [x for x in never_started if x["suite"] == suite]
        stuck = [x for x in ns if x["queued_h"] >= 1]  # waited 1h+ without a runner: nothing serves that label
        waste["cancelled_while_queued_jobs"] = len(ns) - len(stuck)
        waste["never_started_jobs"] = len(stuck)
        waste["never_started_runs"] = len({x["run_id"] for x in stuck})
        waste["never_started_queue_h"] = sum(x["queued_h"] for x in stuck)
        waste["never_started_labels"] = {l: sum(x["label"] == l for x in stuck) for l in sorted({x["label"] for x in stuck})}
        waste["wasted_min"] = waste["cancelled_min"] + waste["failed_min"] + waste["redone_ok_min"]
        for ev in sorted({r["event"] for r in sr}):
            rs = [r for r in sr if r["event"] == ev]
            waste["by_event"][ev] = {"runs": len(rs), "runner_min": sum(r["runner"] for r in rs) / 60,
                                     "wasted_min": sum(r["wasted"] for r in rs) / 60}

        # ---- fixed overhead: runner/step bookkeeping vs real work, and many-tiny-jobs cost
        overhead_pat = OVERHEAD_STEP_PREFIXES
        # the API only returns step detail for recent jobs (since ~Oct 22 in this data), so every ratio below is
        # computed over jobs that have steps, and job_min_with_steps says how much runner time that covers
        oh_step_s = work_step_s = unaccounted_s = covered_s = 0.0
        for j in sj:
            if not j["steps"]:
                continue
            acc = 0.0
            for st in j["steps"]:
                if st["started_at"] and st["completed_at"] and st["conclusion"] in ("success", "failure", "cancelled"):
                    d = (ts(st["completed_at"]) - ts(st["started_at"])).total_seconds()
                    acc += d
                    if st["name"].startswith(overhead_pat):
                        oh_step_s += d
                    else:
                        work_step_s += d
            covered_s += j["dur"]
            unaccounted_s += max(j["dur"] - acc, 0)
        small = [j for j in sj if j["dur"] < 180]
        # queue incidents: jobs that waited 30+ min for a runner, by day
        qi = defaultdict(lambda: {"jobs": 0, "queue_h": 0.0})
        for j in sj:
            if j["queue"] >= 1800:
                d = j["created"][:10]
                qi[d]["jobs"] += 1
                qi[d]["queue_h"] += j["queue"] / 3600
        # gate waste: runner time of heavy test shards in runs where a cheap gate (compile/unit/integration) failed
        gate_stages = {"MATS / Standard Compile and Spotless", "MATS / Unit Tests", "MATS / Integration Tests"}
        heavy = lambda st: st in ("MATS / HAPI Tests", "MATS / Otter Tests")
        gate_failed_runs = {j["run_id"] for j in sj if j["stage"] in gate_stages and j["conclusion"] == "failure"}
        heavy_in_gate_failed = sum(j["dur"] for j in sj if j["run_id"] in gate_failed_runs and heavy(j["stage"])) / 60
        overhead = {
            "job_min_with_steps": covered_s / 60, "step_overhead_min": oh_step_s / 60, "step_work_min": work_step_s / 60, "unaccounted_min": unaccounted_s / 60,
            "small_jobs": len(small), "small_jobs_min": sum(j["dur"] for j in small) / 60,
            "small_jobs_queue_min": sum(j["queue"] for j in small) / 60,
            "jobs": len(sj), "gate_failed_runs": len(gate_failed_runs), "heavy_min_in_gate_failed_runs": heavy_in_gate_failed,
        }
        # ---- before/after the last data gap (the workflows were restructured around then): cost per run by stage
        era = None
        if gaps:
            split = (datetime.strptime(gaps[-1]["end"], "%Y-%m-%d") + timedelta(days=1)).strftime("%Y-%m-%d")
            day_of = {r["id"]: r["day"] for r in sr}
            runs_n = {False: sum(r["day"] < split for r in dur_runs), True: sum(r["day"] >= split for r in dur_runs)}
            if runs_n[False] and runs_n[True]:
                per = defaultdict(lambda: {False: 0.0, True: 0.0})
                tot = {False: 0.0, True: 0.0}
                for j in sj:
                    post = day_of[j["run_id"]] >= split
                    if runs[j["run_id"]]["workflow"] in ("900: Ext Test Suite cron", "901: Promote Build cron", "227: Daily Controllers"):
                        continue
                    per[j["stage"]][post] += j["dur"] / 60
                    tot[post] += j["dur"] / 60
                era = {"split": split, "pre_runs": runs_n[False], "post_runs": runs_n[True],
                       "pre_min_per_run": tot[False] / runs_n[False], "post_min_per_run": tot[True] / runs_n[True],
                       "stages": sorted(({"stage": k, "pre": v[False] / runs_n[False], "post": v[True] / runs_n[True]}
                                         for k, v in per.items()), key=lambda x: -max(x["pre"], x["post"]))[:12]}
        # ---- PR runs repeated on a commit that already had a run (labeled/reopened re-triggers, manual re-runs)
        retrigger = None
        if suite == "MATS":
            seen, rep = set(), []
            for r in sorted((x for x in sr if x["workflow"] == "600: PR Checks"), key=lambda x: x["id"]):
                key = (r["pr"], r["sha"])
                if key in seen:
                    rep.append(r)
                seen.add(key)
            retrigger = {"runs": len(rep), "runner_min": sum(r["runner"] for r in rep) / 60,
                         "of_runs": sum(r["workflow"] == "600: PR Checks" for r in sr),
                         # event action of every PR run since 600 started setting it in the run title (None before that)
                         "by_action": {str(a): sum(r["action"] == a for r in sr if r["workflow"] == "600: PR Checks")
                                       for a in sorted({r["action"] for r in sr if r["workflow"] == "600: PR Checks"}, key=str)},
                         "by_conclusion": {c: sum(r["conclusion"] == c for r in rep) for c in sorted({r["conclusion"] for r in rep})}}
        out["suites"][suite] = {
            "era": era, "retrigger": retrigger,
            "overhead": overhead,
            "queue_incidents": [{"day": d, **v} for d, v in sorted(qi.items())],
            "queue_tail": {"jobs_30m": sum(v["jobs"] for v in qi.values()), "queue_h_30m": sum(v["queue_h"] for v in qi.values())},
            "summary": {
                "runs": len(sr), "runner_min": sum(r["runner"] for r in sr) / 60,
                "queue_min": sum(r["queue"] for r in sr) / 60,
                "wall_p50_min": pct([r["wall"] for r in dur_runs], .5) / 60,
                "wall_p90_min": pct([r["wall"] for r in dur_runs], .9) / 60,
                "success_rate": sum(r["conclusion"] == "success" for r in dur_runs) / len(dur_runs),
                "runner_min_per_run_p50": pct([r["runner"] for r in dur_runs], .5) / 60,
            },
            "workflows": by_wf, "weekly": weekly,
            "daily": [{"day": d, **v} for d, v in sorted(daily.items())],
            "jobs": jobs_out[:60], "stages": stages_out[:25], "classes": classes_out,
            "critical_path": crit_out[:15], "steps": steps_out, "waste": waste,
            "runs": [{k: r[k] for k in ("id", "workflow", "event", "conclusion", "day", "wall", "runner", "queue",
                                         "wasted", "attempts", "pr")} for r in sr],
        }

    if args.history:
        write_history(args.history, run_rows, job_rows)
    with open(args.out, "w") as f:
        json.dump(out, f)
    print(f"wrote {args.out}: {len(run_rows)} runs, {len(job_rows)} jobs")


if __name__ == "__main__":
    sys.exit(main())
