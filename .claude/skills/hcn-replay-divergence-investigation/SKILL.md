---
name: hcn-replay-divergence-investigation
description: Investigate a failed Transaction Replay Diff run of the Hedera state validator (validate-replay.sh, blocks-to-pces, replay-pces, apply-blocks, diff) and find why the replayed state diverges from production, why the replay hangs, or why a step crashes. Use when someone shares a failed replay workflow run URL, a state1-diff.json/state2-diff.json, an unusual stale-parent count, a stuck replay-pces step, or asks to reproduce a replay locally and find the first divergent transaction.
---

# Replay divergence investigation

Goal: go from a failed replay run to the **first divergent transaction**, the **exact field** that differs, the **code path** that produced it, and a **proven** root cause with a proposed fix. Every claim must be backed by tool output or source you have read.

In commands below, `$SKILL_DIR` is this skill's directory (`.claude/skills/hcn-replay-divergence-investigation` in this repository). Python commands use `python3`; on Windows Git Bash use `python` if `python3` is the Microsoft Store stub.

Before starting, read:
- [references/environment.md](references/environment.md): the local setup for the user's OS (macOS, Linux, Windows/WSL). It covers the steps where most of the time gets lost.
- [references/known-causes.md](references/known-causes.md): the catalog of causes found so far. Check it early, but **confirm, don't assume**; new failure modes keep appearing.

## Pipeline being validated

1. `blocks-to-pces`: rebuild unsigned PCES event files from production block files (+ `stale-parents.txt`).
2. `replay-pces`: replay them from the origin snapshot through consensus + handle, producing re-minted blocks and a state.
3. `apply-blocks` on the re-minted blocks, then `diff` against the target snapshot.

Two repositories are involved:
- **`hashgraph/hedera-state-validator`** runs the CI workflows. Its `.github/scripts/validate-replay.sh` runs steps 1–5: download snapshots, the three steps above, diff. `run-replay.sh` downloads that script from the repo's `main` for each run.
- **This repository** (`hiero-ledger/hiero-consensus-node`) holds the validator source (`hedera-state-validator/`). The jar CI uses is built from a `validator_<release>` branch, so read code there, not on `main`.

Expected (benign) differences are the fields the script passes to `--ignore-field`: `legacyRunningEventHash` and the block-hash chain. Everything else is a real divergence, **including `BlockRecordService.RUNNING_HASHES`** (it matches in a correct replay).

## Method

Follow in order. Note which step you're on and keep a running list of verified facts vs hypotheses.

### 1. Read the failed run

```bash
"$SKILL_DIR/scripts/ci-params.sh" <run-url> > params.env     # or: <run-id> [job-id]
```

This writes `export` lines for `ORIGIN_ROUND`, `TARGET_ROUND`, `BLOCK_STREAM_DIR`, `SNAPSHOT_BUCKET`, `NODE_ID`, `CHAIN_ID`, plus commented lines with the jar URI, the diff-file location, the step that failed and the error lines. Repo access needs `gh` with `repo` scope (and SSO authorized for the org).

`BILLING_PROJECT` (and, if needed, `CLOUDSDK_BILLING_QUOTA_PROJECT`) are written as `"<ask-the-user>"` placeholders. **Ask the user which GCP project to use** before anything touches GCS. Never copy CI's project or guess one: the user's account may not be allowed to use it, and billing is the user's decision. See `references/environment.md` → GCS access.

Classify the failure:
- **Crash in a step** (exception, non-zero exit): read the exception first. It's often a jar/script mismatch or a new data shape. Compare the jar's CLI (`java -jar <jar> <command> --help`) with the script.
- **Step 3 never finishes / "Target round … never reached"**: consensus is starved, usually because reconstructed events don't link. Check the stale-parent count from step 2 (normally 0 or a handful).
- **`RESULT: FAILURE — states differ`**: continue with step 2 below.

Also check the **same workflow on other networks and recent runs**:

```bash
gh workflow list --repo hashgraph/hedera-state-validator | grep -i replay
gh run list --repo hashgraph/hedera-state-validator --workflow flow-replay-testing-<mainnet|testnet|previewnet>.yaml -L 10
```

- One network failing repeatedly points to network-specific input: config, version, data.
- A single failure among passes points to something data-dependent in that window, e.g. a specific transaction type near the origin.

### 2. Read the CI diff before replaying

Failed runs upload `state1-diff.json` (expected/target) and `state2-diff.json` (replay) to a CI artifacts bucket. `ci-params.sh` prints that location from the run's own log (`# diff files …`). If it doesn't, **ask the user where the diff files for this run are**; don't guess a bucket. The objects are stored gzip-encoded: download them with `gcloud storage cp` (which decompresses). `gcloud storage cat` returns the gzip bytes; `diff-fields.py` handles those too.

```bash
gcloud storage cp '<diff-location-from-ci-params>/*' ci-diff/
python3 "$SKILL_DIR/scripts/diff-fields.py" ci-diff/state1-diff.json ci-diff/state2-diff.json
```

The script labels each record with the likely state, inferred from its fields (e.g. `RUNNING_HASHES`, `ACCOUNTS`, `STAKING_INFOS`). The diff itself only carries a numeric path `p`.

Balance deltas between accounts often reveal the story, e.g. one transfer that happened only on one side. Use the public mirror node to see production's side of an account in the window:

```bash
"$SKILL_DIR/scripts/mirror-scan.sh" mainnet 0.0.<account> <from-epoch-sec> <to-epoch-sec> [--all]
```

### 3. Check what production actually ran, and what built the jar

In this repository, fetch the branches first: `git fetch origin validator_<release> release/<release> --tags`.

- **Production:** the snapshot's `VERSION` file has `VERSION=` and `COMMIT=` lines (`gcloud storage cat <snapshot-uri>/VERSION`). The commit may not be public; then compare with the tag `v<VERSION>` and say so.
- **The jar:** `unzip -p <jar> git.properties` gives `git.commit.id`. Use **that commit** for every `git show` in later steps. It is not necessarily the tip of `origin/validator_<release>`, and it may not even be pushed (`git branch -a --contains <commit>`; `git ls-remote origin | grep <commit>`).
  - CI may rebuild the jar under the same name. Compare `gcloud storage objects describe <jar-uri>` (`creation_time`, `md5_hash`) with the run's start time and your local copy.
- **Does the jar contain everything the network runs?**
  - `git merge-base --is-ancestor <release-tip> <jar-commit>`;
  - `git log <release-tip> --not <jar-commit>`.
- **Which non-tooling files does the validator change?** `git diff --stat <release-tip> <jar-commit> -- hedera-node platform-sdk hapi`. Anything touching execution or config is a suspect. Confirm config suspects in the effective config (step 4).

### 4. Reproduce locally

```bash
"$SKILL_DIR/scripts/run-replay.sh" <new-workdir> params.env <validator.jar>
```

The wrapper checks the OS prerequisites from `environment.md`, keeps each run in its own directory, and tees `replay-run.log`. A full mainnet run takes hours, mostly downloads. If a reproduction of the same run already exists, reuse it and check its artifacts instead. Then:
- confirm the local `state1/2-diff.json` are **byte-identical** to CI's (`cmp <(sort a) <(sort b)`); otherwise you are reproducing a different problem;
- confirm the effective replay config in **`<workdir>/settingsUsed.txt`** (written by replay-pces in the working directory; the copies inside snapshot and `out/` directories are not the replay's config): e.g. `contracts.chainId`, `blockStream.streamMode`;
- confirm the transaction offset is wired: `zgrep -h 'Defined transaction offset' <workdir>/validator*.log*`. The platform log rotates into `validator-*.log.gz`; the line isn't in `replay-run.log`.

Long-running commands (snapshot downloads, `replay-pces`, Gradle) need network and loopback access and can take an hour. Run them where the user can watch and stop them (a Terminal panel tab or their own shell), not in a short-lived sandboxed command.

### 5. Find the first divergence

```bash
java -jar <jar> output-record-compare --original <prod-blocks> --reminted <replay-blocks> \
  --from-round <ORIGIN> --to-round <TARGET> \
  --ignore-states BlockStreamService,PlatformStateService,BlockRecordService --threads 8
```

It takes seconds to a few minutes. Run it **a second time without `BlockRecordService`** in `--ignore-states`: a record-only difference (one that changes no account state) shows up only as a `RUNNING_HASHES` divergence.

**Range mode gives the first divergent *round*, not necessarily the first transaction.** It can't attribute state-only diffs to a transaction, so it may point at a later transaction in the round. Always follow with round mode and take its `FIRST divergent transaction` SUMMARY line:
- `output-record-compare … --round N` (no `--from-round`/`--to-round`) lists every divergent transaction in the round.
- `--round N --dump-tx <txId>` prints both values. Child transactions have a `.n<nonce>` suffix.
- `--all` (range mode) lists every divergent block quickly. Grouping the txIds by payer (`grep -oE 'txId=[0-9]+@'`) shows whether all divergences come from one account or dApp.

Then compare the transaction on both sides with `tx-dump --blocks <dir> --round N --pos P` (a full dump of one transaction; the round listing without `--pos` has no timestamps). `diff` the two dumps. Check transaction order, bodies and `consensusTimestamp` first: if timestamps differ, it's a wiring problem, not execution.

Everything after the first divergence is a consequence. Don't reason from the final state diff once you have it.

### 6. Trace the field to code and list its inputs

Read the code that computes the divergent field at the **jar's build commit** from step 3 (`git show <jar-commit>:<path>`), not your local checkout. Confirm the shipped jar matches the source when it matters:
- `javap -c -p -cp <jar> <class>`;
- or recompile the branch source against the jar and compare method signatures and references.

List every input: config (network property vs node-local!), state singletons, in-memory caches, consensus time, fee schedules, exchange rates, throttle state, running hashes. Check each one, e.g. with `java -jar <jar> <state-dir> introspect -s <Service> -k <STATE_KEY>`, and build a table: input → how checked → same/different.

`introspect` prints bytes as **base64**, while `output-record-compare`/`tx-dump` print **hex**. Convert before comparing: `echo <b64> | base64 -d | od -An -tx1 | tr -d ' \n'`.

If every visible input matches, the cause is in-memory state that a live node has and a node starting from a snapshot doesn't (warm vs cold).

### 7. Prove it

Don't stop at a plausible story. Typical proofs:
- **Predict an exact value** and match it, e.g. decode the chain ID from a transaction's raw `ethereumData` and compare it with the replay's effective `contracts.chainId`.
- **Targeted patch:** recompile one class against the jar, `jar uf` it into a *copy* of the jar, re-run the affected steps in a separate `OUT`, and show the divergence disappears (output-record-compare clean + `states are equivalent`). The [EventHashCheck](scripts/EventHashCheck.java) tool is an example of a quick standalone check against the jar classpath.
- **Measurement before/after**, e.g. unmatched parent references 26,466 → 0.

Production's side of a value isn't always provable from blocks and snapshots alone (e.g. in-memory state of a live node). Then the mirror node, or the production record stream (`.rcd` files) where needed, is the evidence. If you can't get it, mark that part as inferred.

Label temporary patches and instrumentation clearly, and keep them out of the repo.

### 8. Report

Write `REPORT.md` in the run directory with:
- the result table (CI vs local vs verification);
- the first divergent transaction (round, position, txId, type, submitting node);
- the field-level difference;
- the root cause with code references;
- hypotheses ruled out and what ruled them out;
- the fix and where it belongs: node `main` + releases, the `validator_<release>` branch, or the CI/scripts in `hashgraph/hedera-state-validator`;
- what is **verified** vs **inferred**.

If the cause is new, add it to `references/known-causes.md` in the same format.

## Rules

- Verify every API, path and claim against real source or tool output before relying on it. If a class or file you need isn't available, say so; don't reconstruct signatures from memory.
- Distinguish verified facts from hypotheses. When a measurement contradicts a hypothesis, drop it and say so.
- Never delete or overwrite the user's files. Before deleting a run artifact, check that this run created it (timestamps). Download into a new directory per run.
- Don't commit, push or post without the user's explicit request. Follow the target repository's own contribution rules (DCO sign-off, commit title format) when you do.
- Security-relevant findings go to the user first. Don't describe them in tickets, PRs, commit messages or this skill's files: this repository is public. Ask the user how to track them.
