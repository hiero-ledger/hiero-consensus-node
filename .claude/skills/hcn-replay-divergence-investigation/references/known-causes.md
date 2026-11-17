# Known causes of replay failures

Each entry: **symptom → how to confirm → cause → fix/status**. Statuses are as of 2026-11-16; always re-check the branches before relying on them. When you find a new cause, add it here in the same format.

## Divergence (states differ)

### Validator branch missing release backports
- **Symptom:** a state-only divergence in throttle or fee state, e.g. `CongestionThrottleService.THROTTLE_USAGE_SNAPSHOTS.evmOpsDurationThrottle.used`, while status, gas and fee all match. First seen at a contract call.
- **Confirm:** compare the network's release (snapshot `VERSION`/`COMMIT`, release tag/branch) with `origin/validator_<release>`; `git log <release> --not origin/validator_<release>`. Check `settingsUsed.txt` for the effective defaults (e.g. `contracts.ops.duration.opsDurationByOpCode`).
- **Cause (0.78.1):** the ops-duration backports (`eba1ec01e1` opcode table, `6c34b2e8ff` bursting, `41b1149564`) were on the release but not on the validator branch.
- **Status:** `validator_0_78` now contains `release/0.78`. Re-check for every new release.

### Wrong EVM chain ID for the network
- **Symptom:** an `ETHEREUM_TRANSACTION` is `SUCCESS` in production but `WRONG_CHAIN_ID` in the replay; entity counts, balances and receipts differ afterwards.
- **Confirm:** `settingsUsed.txt` → `contracts.chainId`. Decode the transaction's `ethereumData`: for type 2, `02 f9.. 82<chainId>` (295 mainnet, 296 testnet, 297 previewnet).
- **Cause:** `contracts.chainId` is node-local config, not in state (file 0.0.121 doesn't set it).
- **Status:** fixed: the workflow passes `CHAIN_ID` from `.github/config/<Network>.json`.

### PRNG output differs near the start of the window (known issue)
- **Symptom:**
  - The **first** divergence is a `UTIL_PRNG` output, or a contract call using the PRNG system contract, among the first few transactions after the replay origin.
  - Later PRNG-dependent transactions in the same window may diverge too, usually all in one dApp. The final diff then shows balances of that dApp's users and the stakes of the nodes they stake to.
- **Action:** this is a known issue, **tracked internally**. Classify the run as this known issue and tell the user. Don't investigate the mechanism further, and don't describe it in tickets, PRs, commit messages or this file. Ask the user for the internal reference if one is needed.

### Cold-start singleton: stake period at 00:00 UTC
- **Symptom:** node-fee / staking-reward distribution differs at the first round after midnight UTC.
- **Confirm:** the window crosses 00:00 UTC; the validator branch's `HandleWorkflow.handleRound` doesn't call `stakePeriodManager.setCurrentStakePeriodFor(...)`.
- **Status:** fixed on `validator_0_78` / `validator_0_79`. Look for similar lazily-initialized state when a divergence sits at a boundary.

### Consensus timestamps short by a constant
- **Symptom:** `tx-dump` shows the same transactions in the same order, but consensus timestamps are off by a constant number of nanos.
- **Confirm:** the replay log lacks `Defined transaction offset (nanos)`.
- **Cause:** `transactionOffsetNanos` not wired in the replay platform build.
- **Status:** fixed in `ReplayPcesWorkflow`.

## Replay stuck, or target round never reached

### Wrong transaction digest after the event-hash cutover (validator 0.79)
- **Symptom:**
  - step 2 logs `Event hash cutover detected` and tens of thousands of "genuine stale parents", spread evenly across creators;
  - step 3 floods `Shadowgraph … Missing non-expired other parent`, with the tips set growing to tens of thousands;
  - CI ends with `Target round … was never reached during replay`.
- **Confirm:** rebuild events from a few hundred blocks and count parent references that match no rebuilt event (`scripts/EventHashCheck.java`). It should be ~0 after a short warm-up.
- **Cause:** `BlockStreamEventBuilder.hashEvent` hashed *transactions* with the event digest type (SHA-256 post-cutover). The platform (`PbjStreamHasher`) always hashes transactions with SHA-384, so every event carrying transactions got a wrong hash.
- **Status:** fix prepared for `validator_0_79` (`transactionDigest = DigestType.SHA_384.buildDigest()`, plus `BlockStreamEventBuilderTest` against `PbjStreamHasher`). Check whether it has landed.

### Finalization wait on the empty trailing block (validator 0.79)
- **Symptom:** step 3 reaches the end, then `Timed out after PT30S waiting for block finalization … first unfinalized: …/<last>.blk.gz`, and that file is 0 bytes.
- **Cause:** `ReplayPcesWorkflow.firstUnfinalized` required a `.mf` marker for the zero-size block opened for the next round when the input ran out.
- **Status:** fix prepared for `validator_0_79` (ignore zero-size block files; `ReplayPcesWorkflowTest`). Check whether it has landed.

## Crash, or a step can't find its input

### Jar and script disagree
- **Symptoms seen:**
  - `Unknown options: '--state-dir'` (jar CLI vs script);
  - `Invalid stale-parent hash … expected 96 hex chars, got 64` (SHA-256 hashes vs a SHA-384-only loader);
  - `UnknownFieldException: … field number 7` (jar older than the block format).
- **Confirm:** `java -jar <jar> <command> --help`, and the jar's build commit: `unzip -p <jar> git.properties` → `git.commit.id`. That commit may be ahead of `origin/validator_<release>`, or not pushed at all. CI may rebuild the jar under the same name several times a day; compare `gcloud storage objects describe <jar-uri>` (`creation_time`, `md5_hash`) with the run time.
- **Fix:** align the jar build and the `validate-replay.sh` revision.

### Block output directory name (`blockStreams` vs `blockstream`)
- **Symptom:** step 4: `No block output found under ./out/replay/blockstream`, although step 3 succeeded and wrote `./out/replay/blockStreams/block-0.0.3`.
- **Cause:** the 0.79 jar writes to `<out>/blockStreams` and ignores `-DblockStream.blockFileDir`. On macOS (case-insensitive APFS) this is invisible.
- **Workaround:** `STEPS=4-5 BLOCK_OUTPUT_DIR=./out/replay/blockStreams/block-0.0.3`. **Status:** open.

### Leftover platform PCES database
- **Symptom:** step 3: `Upper bound must never decrease, file data/saved/preconsensus-events/…`.
- **Cause:** the script on `main` doesn't set `-Dpaths.savedStateDir`; `./data/saved` from an earlier run in the same directory is replayed too.
- **Fix:** run in a fresh directory, or remove `./data` created by your previous run. **Status:** open.

### Empty snapshot directory treated as downloaded
- **Symptom:** step 1 says `already present … skipping download`, then later steps fail on an empty or partial state.
- **Cause:** a failed or interrupted download leaves `./<round>`.
- **Fix:** delete the partial directory and re-run. Compare sizes with `gcloud storage du -s`. **Status:** open.

### Freeze inside the window
- **Symptom:** replay stops with `FREEZE_COMPLETE`.
- **Fix:** choose a window that doesn't span a freeze/upgrade.
