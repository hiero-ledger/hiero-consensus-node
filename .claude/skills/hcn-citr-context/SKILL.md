---
name: hcn-citr-context
description: The CITR control loop as it lives in THIS repo — the NNN-<class>-<slug> numbering scheme, the Chewie call path (controllers acquire via 859 for SDPT/SDLT and hand 831/833 an allocation-id read back via 863; MDLT's 835 still acquires its own; 103's remote target acquires too), the release path through 225, the MDLT kickoff/monitor/publish/tag set (203/204/205/206/224), the wire contract fields HCN parses out of a Chewie allocation response, and the MATS/XTS/promotion surface — where XTS (226), the daily controllers (227) and the namespace prune (107) are dispatch workflows that report to Chewie, triggered today by the restored 900/901/903 crons (to be deprecated when Chewie owns workflow dispatching). Load this before touching any numbered workflow, the Chewie call chain, `.github/chewie.yaml`, or anything that assumes what a CITR workflow does.
---

# CITR in hiero-consensus-node

This is the native, local version of what used to be reconstructed from GitHub each time. It documents the CITR workflow set as it actually sits on `main`.

## Primary sources — read these first, they are maintained in-repo

Three files in this repo are the authoritative reference and are kept up to date by whoever changes the workflows. Prefer them over this skill wherever they disagree — this skill is a guide to reading them and the traps around them, not a replacement:

- **[`.github/workflows/docs/chewie.md`](/.github/workflows/docs/chewie.md)** — the Chewie integration: repository config keys, required secrets, per-test resource config, the allocation flow (split into an SDPT/SDLT path and an MDLT path), and a "Migration Notes" section listing what was removed.
- **[`.github/workflows/docs/workflow-manifest.md`](/.github/workflows/docs/workflow-manifest.md)** — the current-file ↔ deprecated-file ↔ deprecated-name table for every workflow, plus a "Numbering conventions" section (sub-blocks inside 800-899, where the next batch of numbers starts).
- **[`.github/workflows/docs/citr-test-config.md`](/.github/workflows/docs/citr-test-config.md)** — the suite catalogue (MATS, XTS, SDCT, SDPT, SDLT, MDLT, Shortgevity) and per-suite configuration.
- **[`.github/workflows/docs/required-chewie-changes.md`](/.github/workflows/docs/required-chewie-changes.md)** — the dispatch contract: every workflow Chewie dispatches (`226`, `227`, `107`, and planned `204`/`205`/`206`), when, with which inputs, what HCN reports back, and the Chewie-side changes this repo depends on.

Verified against `origin/main` at `8d98da626a` (2026-09-30) **plus** the changes on branch `27342-citr-workflows-must-be-triggerable-via-chewie` (226/227/107 dispatch workflows reporting to Chewie, triggered by the 900/901/903 crons until Chewie owns workflow dispatching; 103 on Chewie; pods scheduled from the Chewie 3.x allocation labels). If that branch hasn't merged on your checkout, §6 describes the future, not `main`. Re-verify anything below against current `.github/workflows/` before trusting it — this surface moves fast.

Note: CITR support scripts live under **`.github/workflows/support/`** (`support/chewie/`, `support/citr/`), not a repo-root `support/` directory. Paths below that say `support/...` are relative to `.github/workflows/`.

## 1. The numbering scheme

`NNN-<class>-<slug>.yaml`, `name: "NNN: [CLASS] Title"`. Classes: `user` (workflow_dispatch), `flow` (event entry point), `disp` (dispatch-driven controller), `call` (reusable `workflow_call` callee), `cron` (scheduled). Ranges per `workflow-manifest.md`: `0xx` user/dry-runs, `1xx` operational (including non-CITR `disp` controllers such as `106-disp-xts-optional-tests`), `2xx` CITR, `3xx` triggered/release, `4xx`–`5xx` reserved (empty), `6xx` test helpers, `7xx` AI helpers, `8xx` reusable callees, `9xx` crons.

Within `8xx` the manifest documents conventional sub-blocks: `800-809` MATS driver and shared blocks, `810-814` MATS-specific callees, `815-830` XTS driver/panels/callees, `831-835` CITR SDPT/SDLT/MDLT, `836-849` consumer-neutral per-suite leaves, `850-853` release, `854-864` utilities and Chewie. **The contiguous space below 850 is exhausted — the next batch of leaf workflows starts at 870.**

On the branch: 162 `.yaml` files directly under `.github/workflows/`, 108 numbered, 8 of those deprecated stubs once `302` and `900`–`904` are restored.

**Duplicate numbers still exist.** A deprecated workflow keeps its number, takes a `Deprecated:` prefix on `name:`, and is reduced to a `print-deprecated` stub — so a number is not unique on disk until you check which file is live:

| Number | Live file                                  | Deprecated file                                                                    |
|--------|--------------------------------------------|------------------------------------------------------------------------------------|
| 802    | `802-call-compile-and-spotless-check.yaml` | `802-extract-jdk-version.yaml` (superseded by `854-call-extract-jdk-version.yaml`) |
| 855    | `855-call-extract-citr-vars.yaml`          | `855-extract-citr-vars.yaml`                                                       |
| 857    | `857-call-workflow-unit-tests.yaml`        | `857-call-solo-ge044.yaml` (superseded by `856-call-solo-ge044.yaml`)              |

Lone tombstones with no live file at that number: `050` (→ `102`), `080` (→ `701`), `200` (→ `103`), `805`/`808` (the monolithic HAPI/Otter callees, replaced by the `810`/`811`/`827`/`828` panels over the `836`–`849` leaves). `302` and `900`–`904` are live again: they were deprecated earlier on the branch and restored, and `302`/`900`/`901`/`903` are to be deprecated when Chewie owns workflow dispatching. **Select workflows by filename, not by number**, and check `name:` for `Deprecated:` before trusting a match.

**The manifest drops deprecated workflows from its table** (only the old names of *live* workflows appear in its deprecated columns) — so a number missing from the manifest may still exist on disk as a stub.

**MQPT is gone, not deprecated** (`200`/`210`/`220`/`602`/`830` under the old numbering were deleted outright). **`207-user-release-chewie-allocation.yaml` no longer exists** — the release path is `225` (§2).

## 2. The Chewie call path

All SDPT, SDLT, and MDLT Kubernetes resources come from Chewie — scheduled and adhoc alike. There is no non-Chewie fallback. But **SDPT/SDLT and MDLT differ in who owns the allocation** (rationale: issue #27203):

### SDPT/SDLT — the controller acquires, the test workflow only reads

```
201/202 (adhoc) or 221/222 (scheduled)
  ├─ 862-call-get-chewie-properties     .github/chewie.yaml → default_duration/default_timeout/default_mdlt_length
  ├─ 858-call-get-chewie-jwt            identity key → JWT
  ├─ 861-call-get-test-config           support/chewie/<type>-config.json → CN/aux shape
  ├─ 860-call-validate-chewie-jwt
  ├─ acquire-kubernetes-resources → 859-call-create-chewie-request   POST + poll; emits allocation-id
  ├─ 831 / 833  (with: allocation-id — the only Chewie input)
  │    ├─ 858 + 860                     fresh JWT for the read
  │    └─ acquire-kubernetes-resources → 863-call-get-chewie-allocation   GET by id; same outputs 859 emits
  └─ release-chewie-allocation (221/222 only)  dispatches 225 on a passing run
```

`831`/`833` no longer accept `duration-minutes`/`chewie-request-timeout` and no longer call `859`.

### MDLT — 835 still acquires its own allocation

```
203 (adhoc) or 224 (Chewie-dispatched once SDPT+SDLT passed)  — 862; no pass-tag gate (Chewie gates MDLT)
  └─ 835-call-multi-day-longevity-test   858 → 861(mdlt) → 860 → 859; outputs allocation-id
       smoke run (~3 min) → launches the production run (mdlt-length) on the cluster, then EXITS
then, out of band, keyed only by allocation-id:
  204-disp-mdlt-monitor           status (green/red/yellow/black) + prunes BN block data >59 min old
  205-disp-mdlt-publish-results   logs / optional FSTS_Insight PDFs
  206-disp-mdlt-tag-result        inputs build-tag + result(success|failure); signs mdlt-pass-/mdlt-fail-<build>
  225-disp-release-chewie-allocation   optional early teardown
```

### 103 (Solo Tests Adhoc) — opt-in remote target

`cluster-target` defaults to `kind` (an ephemeral cluster on the runner, no Chewie). `remote` is honored only for `jumpstart`, `wrb-streaming`, `e2e-block-stream-cutover` — that list lives **only** in `get-chewie-jwt`'s `if:`, and every other Chewie job (`861` with `test-type: solo-adhoc`, `862`, `860`, `859`) needs it, so all skip together on Kind runs. The three scenario jobs `need` `acquire-kubernetes-resources` and check results explicitly (a skipped need would otherwise skip them on Kind). On remote, the namespace, Teleport `kubernetes-cluster` (the allocation's `fqdn`), and `KUBE_CONTEXT=hashgraph.teleport.sh-<fqdn>` all come from `859`'s outputs; duration is a fixed 240 min. `release-chewie-allocation` dispatches `225` whenever an allocation id exists, **regardless of result**. On remote, each job's `Render Chewie Scheduling` step renders the allocation's groups into `RUNNER_TEMP` copies of the remote values files under `hedera-node/test-clients/scripts/solo/` (consensus on `cn`; haproxy, envoy, MinIO and the jumpstart mirror on `aux`) and points the scripts at them via `REMOTE_CLUSTER_NETWORK_VALUES`/`REMOTE_NETWORK_VALUES_TEMPLATE`/`REMOTE_MIRROR_VALUES_TEMPLATE`. The scenarios' `start_remote_toleration_patcher` (two copies: `remote-cluster-helpers.sh` and inline in `solo-wrb-jumpstart.sh`) pins every other non-consensus workload, block node included, to the `aux` group, and needs `AUX_LABELS`/`AUX_TOLERATIONS` in the environment. The rendered mirror values must stay identical to the patcher's patch, or the patcher rolls the mirror Deployments mid-`solo mirror node add`. A comment block above `get-chewie-jwt` documents how to make another scenario remote-capable.

`835`'s own result reflects only the **kickoff** (smoke passed, production launched). The multi-day verdict arrives via `206`, which per its header comment is meant to be dispatched by Chewie when the run completes; `204` says "short-term user-triggered; longer term Chewie dispatches it." Chewie dispatches `224` once SDPT and SDLT have both passed for the build (judged from its own suite results); `227` does not dispatch it. `204`/`205` resolve cluster/namespace from the allocation by calling `parse-chewie-allocation.sh` directly, not via `863`.

### Release — `225-disp-release-chewie-allocation.yaml`

`workflow_dispatch`, one required input `chewie-allocation-id`. Jobs: `858` (fresh JWT, from `CHEWIE_REPO_IDENTITY_KEY`) → `860` → `release-allocation` on `hl-cn-chewie-lin-sm`, which masks the JWT, rejects any id that isn't a positive integer, prints a summary via `parse-chewie-allocation.sh -f summary` (no `-g`/`-x` — groups are discovered from the response), then calls `support/chewie/release-chewie-allocation.sh` → `DELETE /api/v1/compute/allocation/:id` with `Authorization: Bearer <jwt>`.

Status handling in `release-chewie-allocation.sh`: `204` released; `410` already terminal → `::notice`, success no-op; `404` not found (also what an allocation owned by **another repo** looks like) → fail; anything else → fail.

Who calls it: `221`/`222` dispatch it via `step-security/workflow-dispatch` **only when the test result is `success`**, with `continue-on-error: true` — best effort. `103` dispatches it after every remote run, pass or fail. A fresh JWT is fetched inside `225` because the acquire-time JWT may be hours stale. **Adhoc `201`/`202` never self-release, and failed scheduled runs are not released either** — Chewie's reaper / `workflow_run.completed` webhook is the only release path for those. MDLT is released manually through `225` or at expiry.

### Authentication — the JWT is base64-encoded twice

`858-call-get-chewie-jwt.yaml` POSTs to `api/v1/auth/token`:

```bash
-H "Authorization: ${{ secrets.chewie-key }}"          # bare API key, NO "Bearer " prefix — token-exchange endpoint only
...
chewie_jwt_b64=$(printf '%s' "${chewie_jwt}" | base64 -w 0)
double_encoded_jwt=$(printf '%s' "${chewie_jwt_b64}" | base64 -w 0)
```

Outputs `jwt` (double-encoded) and `expiration`. Every consumer decodes both layers via `support/chewie/decode-b64-jwt.sh` (two sequential `base64 -d`, distinct errors per layer, written to **stderr** with `return 1`). **Decoding once yields base64, not a token.** Every call after the exchange uses `Authorization: Bearer ${CHEWIE_JWT}`. `parse-chewie-allocation.sh` and `release-chewie-allocation.sh` read the token from env `CHEWIE_JWT_B64` (kept out of the process list) and `::add-mask::` the decoded form, since masking is exact-string.

Secrets: repo secrets `CHEWIE_HOST` (includes the scheme) and `CHEWIE_REPO_IDENTITY_KEY`, passed into `831`/`833`/`835` as `chewie-host` / `chewie-id-key`, and used directly by the controllers, `103`, `225`, and `226`.

### The request body

`support/chewie/build-compute-request.sh`, driven from `859`'s inputs:

```
-d <duration-seconds> -q <cn-qty> -c <cn-cpu> -m <cn-memory-mb> -g <cn-group-name (default "cn-nodes")>
-a <aux-qty> -p <aux-cpu> -w <aux-memory-mb> -x <aux-group-name (default "aux-nodes")>
-i <run-id> -n <run-number> -t <run-attempt> -o <owner> -r <repo> -j <job> -e <request-timeout (0 → 3600)>
```

`859` takes `duration-minutes` and multiplies by 60 before passing `-d`. Any zero-valued required numeric option is rejected, so a legitimately-zero field can't be expressed.

Per-test-type shapes in `support/chewie/<type>-config.json`, read by `861` (valid `test-type`: `sdpt`, `sdlt`, `mdlt`, `solo-adhoc` — checked by a **substring** match, so a partial name like `sd` also passes):

| Type         | CN qty/CPU/mem (MB) | Aux qty/CPU/mem (MB) |
|--------------|---------------------|----------------------|
| `sdpt`       | 9 / 39 / 256000     | 1 / 39 / 256000      |
| `sdlt`       | 8 / 39 / 256000     | 1 / 39 / 256000      |
| `mdlt`       | 8 / 39 / 256000     | 1 / 39 / 256000      |
| `solo-adhoc` | 4 / 8 / 16384       | 1 / 8 / 16384        |

`solo-adhoc` (for `103`) is sized for functional runs, not perf; whether Chewie 2.11's matching accepts a request that small was not verified when it was added.

`support/chewie/` holds exactly: `apply-allocation-scheduling.sh` (§3), `build-compute-request.sh`, `decode-b64-jwt.sh`, `parse-chewie-allocation.sh`, `release-chewie-allocation.sh`, and the four `*-config.json`.

### Allocation durations actually requested

`chewie.md` says scheduled controllers "always use the `chewie.yaml` default." **They don't** — they compute it and then pass a hardcoded value to `859`/`835`:

| Controller | `duration-minutes` passed           | Source                                                                   |
|------------|-------------------------------------|--------------------------------------------------------------------------|
| `221`      | `1320` (22h)                        | hardcoded, "20 hours + 2 for post-mortem"                                |
| `222`      | `1080` (18h)                        | hardcoded, "16 hours + 2 for post-mortem"                                |
| `224`      | `8640` (6d)                         | hardcoded, 5-day run + log-review buffer                                 |
| `201`/`202`| input, else `default_duration / 60` | verified in `verify-citr-vars`                                           |
| `203`      | `fromJSON(inputs.duration-minutes)` | **raw input** (default `"8640"`) — bypasses its own computed fallback; a blanked input makes `fromJSON('')` fail |
| `103`      | `240` (4h)                          | hardcoded; covers the longest scenario job timeout (210 min)             |

The request timeout always comes from `default_timeout`. Chewie bounds duration to 3600–1209600 s and request timeout to 60–604800 s server-side.

## 3. Polling contract and response parsing

`859` accepts 200 or 202 on create, reads `.id` as the allocation id (fatal if missing/`null`), then polls `GET /api/v1/compute/allocation/:id` every 2 s. Only two statuses are branched on: `approved` breaks the loop; `pending` continues until `request-timeout` seconds elapse. **Everything else — `denied`, `cancelled`, `released`, `expired`, `expired_released`, or anything unrecognized — falls through to the same fatal `else`**, even though the comment above it still enumerates only six statuses (no `expired_released`; see `hcn-chewie-context`).

After approval, `859` no longer parses the poll response itself — it calls `support/chewie/parse-chewie-allocation.sh -g <cn> -x <aux> -f outputs`, which re-GETs the allocation (requires HTTP **200** exactly) and emits outputs. `863` is a thin wrapper around the same script, so `859` and `863` emit identical keys: `namespace`, `fqdn`, `cn-/aux-group-name`, `cn-/aux-quantity`, `cn-/aux-tolerations`, `cn-/aux-labels`, `request-expiration` (plus `allocation-id` from `859`). There is no network id, owner or role: Chewie 3.x publishes none of them.

By exact `jq` path in `parse-chewie-allocation.sh`:

- `.status`, `.namespace`, `.cluster_fqdn`, `.expires_at`
- `(.instances // [])[] | select(.group==$g) | .spec.quantity` — still nested under `.spec`
- `(.instances // [])[] | select(.group==$g) | .labels` and `.tolerations` (compact JSON, passed through untouched)

When the caller names its groups (`-g`/`-x`), a group that is missing or has no labels is fatal — that covers an unapproved allocation (no `instances`) and one approved before the 3.x upgrade (groups named by operator role). A response without `instances` is otherwise tolerated, so `225` can summarize an already-released allocation.

Without `-g`/`-x` (as `225` calls it), groups are discovered from `.instances[].group` and each group's own name becomes its output-key prefix instead of `cn`/`aux`.

**Pod scheduling.** Every pod spec in the CITR values templates (`support/citr/*.yaml`, 20 specs) and in `103`'s remote values files carries a `chewie-group: cn|aux` placeholder in its `nodeSelector`. `support/chewie/apply-allocation-scheduling.sh <files>` (env `CN_LABELS`/`CN_TOLERATIONS`/`AUX_LABELS`/`AUX_TOLERATIONS`, i.e. the callee outputs) replaces each placeholder with that group's labels, keeps other selector keys (the block node's `kubernetes.io/hostname`), and appends the group's tolerations. It refuses empty labels and any leftover placeholder, and installs mikefarah yq v4 if the runner lacks it. `831`/`833`/`835` call it in their `*-tests-start` jobs with `|| exit 1` (the steps run `set +e`), and pick the block-node host with `kubectl get nodes -l <cn labels>`. An unrendered placeholder matches no node, so it fails closed (Pending). Report paths, `version_run.txt` and step summaries identify a run by allocation id (`832`'s `allocation-id` input).

## 4. The `.github/chewie.yaml` duality

Two independent readers of the same file:

1. **Chewie's own server** generates `.github/chewie.yaml` at installation. Per `hcn-chewie-context`, nothing on its allocation path reads `default_duration`/`default_timeout` back.
2. **This repo's `862-call-get-chewie-properties.yaml`** reads it client-side with `yq`, and emits `request-duration`, `request-timeout`, and `mdlt-length`.

`862` fallbacks on a missing/`null` key (each also logs `::error::` but does not fail): `default_duration` → `3600`, `default_timeout` → `300`, `default_mdlt_length` → `432000`. A missing file is fatal. `default_mdlt_length` is an HCN-only key — Chewie's template doesn't render it.

Current `.github/chewie.yaml`: `branches: ["*"]`, `default_duration: 72000` (20h), `default_timeout: 3600` (1h), `default_mdlt_length: 432000` (5d, the production-run length — **not** an allocation lifetime), `workflows` commented out (no allowlist). The file's own comment says "keep `default_duration` long enough to outlive" the MDLT run, which 72000 s does not — MDLT only works because `224`/`203` pass 8640 minutes explicitly instead of the default.

## 5. Namespace cleanup

Chewie creates the namespace on approval and reclaims it on expiry (or on `225`). The cleanup work:

- **In-namespace pruning lives in `107-disp-clean-citr-namespaces.yaml`**, a faithful port of `main`'s `903` with `workflow_dispatch` only (no inputs) and a single-run concurrency group. **`903` is now a thin hourly cron that dispatches `107`** (to be deprecated when Chewie owns workflow dispatching); without one of them, long SDLT/MDLT allocations fill their disks. (`903` had been extended to Chewie namespaces on purpose in `1aa1d6c153`, #27064.) `204` separately prunes block-node data for MDLT.
- **`902` is the only thing that deletes leftover legacy `solo-*-nN` namespaces**, and only for the boxes enabled on its schedule.

- **`902-cron-auto-namespace-delete.yaml`** (daily `0 23 * * *`, unchanged from `main`) — the one cron that deleted namespaces, matching `(solo)-(sdpt|sdlt|mdlt)-n([1-9]|1[0-2])$`. Chewie's `chewie-<repo>-r<id>` namespaces can't match. On `schedule`, only `Dallas_n1`/`Dallas_n2` default to true; everything else needs manual dispatch with its box ticked. **Latent bug:** `Dallas_n10`–`n13` and `Chicago_n14` are wired from `github.event_inputs.*` (typo for `github.event.inputs`), so they're always empty — and `n13`/`n14` couldn't match the regex anyway.
- **`107` (as `main`'s `903` did, hourly)** — **does not delete namespaces.** Runs `support/citr/cronClean.sh` against every namespace matching `solo[\-].*[\-]n[0-9]|chewie` — **this now includes Chewie-allocated namespaces** — deleting stream files older than 59 minutes in each `network-node*` pod and running `mc rm --older-than 0d1h0s` against the `solo-streams`/`solo-backups` MinIO buckets.

Cluster access is via Teleport, not a kubeconfig: `teleport-actions/auth-k8s` (plus `auth`/`setup`) against `hashgraph.teleport.sh:443`, for `k8s.pft.dal.lat.ope.eng.hashgraph.io` / `k8s.pft.chi.lat.ope.eng.hashgraph.io`.

## 6. MATS, XTS, and promotion

| Concern   | Path                                                                                                                                                                                                                                             |
|-----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| MATS      | `600-flow-pull-request-checks` → `800-call-mats-tests` → `801`–`804`, `809`, `822`, `823`, plus panels `810` (HAPI → `836`–`843`) and `811` (Otter → `847` fast). `600` also runs `857-call-workflow-unit-tests` (gomplate template tests). Dry run `000`. |
| XTS       | **`900` (every 3 h) or Chewie dispatches `226-disp-citr-xts-controller`** (on `main`, `inputs.ref` = candidate SHA) → `815-call-xts-tests` (→ `802`, `806`, `807`, `817`–`821`, panels `827` HAPI → `844`–`846`, `828` Otter → `848` full / `849` chaos), plus `816` and a fire-and-forget dispatch of `106-disp-xts-optional-tests` → `824` → `825`/`826`. Dry run `001`. |
| Promotion | **`901`** (Tue–Sat 01:00: newest `xts-pass` → `build-NNNNN` from repo var `XTS_BUILD_PROMOTION_INDEX`), or later **Chewie's promotion engine** (every 15 min, a `passing_xts` commit, App-token tag, Slack report), **dispatches `227-disp-daily-controllers`** (on `main`, sole input `ref` = the build tag) → `221`, `222` (dispatched *on* the tag, no inputs), `223` (`ref` and `build-tag` = the tag). Chewie dispatches `224` separately, once SDPT and SDLT have both passed for the build (from its own suite results). |

The crons came back as triggers of the dispatch workflows, not as their own implementations (to be deprecated when Chewie owns workflow dispatching): `301` → `302` (tag `xts-candidate`) → `900` cron every 3 h, which dispatches `226` for the candidate unless it already carries an `xts-pass-*`/`build-*` tag, is off `main`, or has a `226` run queued or in progress (matched on `226`'s `run-name`, which includes the SHA) → `901` cron Tue–Sat 01:00 (newest `xts-pass` → `build-NNNNN`, delete all `xts-pass-*`, dispatch `227`). `903` dispatches `107` hourly. **Disable a cron when Chewie takes over its dispatch**; `901` and Chewie's promotion engine number builds from different counters and must never both run.

### 226 in detail

- **Input guard:** `ref` must match `^[0-9a-f]{40}$`. `855`/`854`/`815` check the candidate out at `fetch-depth: 1`, which can't resolve a short SHA, so anything else fails `fetch-xts-candidate` (reported as an *environment* failure).
- **Eligibility:** the only rule is `git tag --contains <sha>` matching `build-.{5}` (already in a build, on this commit or a descendant), plus the existing on-`main` check. A rejected candidate sets `xts-proceed=false` — the run is **not** cancelled — but `xts-tag-commit` is still emitted so it can be reported.
- **`tag-for-promotion` is required again.** It always tags `xts-pass-<epoch>` (no adhoc tags); `901` promotes the newest one and `900` skips commits that carry one, so a failed tag fails the run (it is back in `report-success`/`report-failure`). `901` deletes the `xts-pass-*` tags when it promotes.
- **`run-name` includes the candidate:** `226: [DISP] CITR XTS Controller (<sha>)` — `900` matches on it.
- **Reporting to Chewie:** `report-xts-result` calls `864` (below) with `suite-type: xts`, `if: always()` whenever a commit resolved. Disposition: `xts-proceed != true` → `not_run`; else `success`/`failure`/`cancelled` → `passed`/`failed`/`cancelled`.
- One run per candidate: `concurrency: xts-<sha>`.

### Suite-result reporting — `864-call-report-suite-result.yaml`

Every CITR suite reports to Chewie's `POST /api/v1/suites/results` through `864`. Inputs: `suite-type` (validated: `mats`/`xts`/`sdpt`/`sdlt`/`sdct`/`mdlt`), `disposition` (validated against Chewie's vocabulary: `running`/`waiting_for_cluster` non-terminal; `passed`/`failed`/`performance_issue`/`isolated_issue`/`cancelled`/`not_run` terminal), one of `commit` (full SHA) or `build-number` (`build-00404`/`00404`/`404` → sent as integer `404`), and optional `branch`.

- **Reporting is required, not best-effort.** Chewie is the source of record for suite results; a failed report (including a Chewie outage) fails the caller's run. Git result tags are the optional, secondary record being retired: the SDPT/SDLT/MDLT tagging jobs (`221`/`222` `tag-*-result`, `206` `tag-mdlt-result`) are job-level `continue-on-error` and excluded from `report-success`/`report-failure` conditions and Rootly alert flags — their result only appears as an informational Slack/Rootly field. `226`'s `tag-for-promotion` is the exception while the crons run (§6).
- **One job, token exchanged inline** (858's logic, bare key on `api/v1/auth/token`) rather than via `858`/`860`, so the token is always fresh at report time, even after a multi-hour suite.
- `start_time` comes from the GitHub API (`actions/runs/{run_id}/attempts/{attempt}` → `run_started_at` — `github.run_id` is the caller's run inside a reusable workflow), so **every caller must grant `actions: read`** or the call is rejected at startup. `end_time` is sent only for terminal dispositions.
- Callers map their own job results to a disposition; it differs per suite:

| Caller | Suite | Subject | Notes |
|--------|-------|---------|-------|
| `300` | `mats` | `github.sha` + `github.ref_name` | `running` when MATS starts (`report-mats-running` shares `mats-tests`' `needs`), then the result on `needs: mats-tests` + the running job — reported as soon as MATS finishes. PR MATS (`600`) is not reported. |
| `226` | `xts` | candidate SHA | `running` when XTS starts (`report-xts-running` shares `xts-execution`'s `needs` and `if`); then the result, see above |
| `221`/`222` | `sdpt`/`sdlt` | `verify-tag` build number (skipped if empty) | `running` when the test starts (shares its `needs`, so after the allocation is acquired); the result after `release-chewie-allocation`, from `831`/`833` `outputs.result` and job result |
| `224` | `mdlt` | build number | successful kickoff → **`running`** (835 only launches the multi-day run) |
| `204` | `mdlt` | `inputs.build-tag` | each status check: `running` / `passed` / `failed` / `cancelled`, from `monitor-mdlt`'s `state` output (a failed `verify-allocation` counts as `cancelled`) |
| `206` | `mdlt` | `inputs.build-tag` | the verdict input: `success` → `passed`, `failure` → `failed`, once `verify-build` succeeds — the authoritative MDLT result |

Each `report-*-running` job shares its test job's `needs`, so both start together; every terminal report job also `needs` the running job, so `running` always lands before the result, and the test jobs never wait on either report. MDLT reports many times per run; Chewie has to take the latest report per build (or commit), not count rows. `206` (Chewie-dispatched with the verdict) reports that verdict as `passed`/`failed` once `verify-build` succeeds — its run conclusion is not the verdict (a `failure` verdict still yields a green run), so the explicit report is the authoritative MDLT result. SDCT (`223`) reporting is a separate effort. **The endpoint exists only in Chewie 3.x (≥ v3.3.0); against 2.11 every report 404s and fails the run** — including MATS (`300`) on every push to `main`. So 3.x must be in production before this branch merges — and a release that actually schedules (carrying #817/#825, never `v3.8.0`), deployed in the order in `required-chewie-changes.md` item 9.

Result tags are the integration surface, not workflow outputs: `221`/`201` tag `sdpt-pass-<build>`/`sdpt-fail-<build>`, `222`/`202` tag `sdlt-pass-`/`sdlt-fail-`, `206` tags `mdlt-pass-`/`mdlt-fail-` (build tag regex `build-(.{5})`), GPG-signed via `step-security/ghaction-import-gpg`. MDLT controllers no longer check pass tags — Chewie gates MDLT on SDPT/SDLT results, so nothing in this repo reads the result tags. `223-disp-sdct-controller.yaml` has zero Chewie references — SDCT allocation still runs out of band.

## 7. Traps

- **Runner labels are per-repository.** Chewie helpers here run on `hl-cn-chewie-lin-sm`. Suite runners include `hl-cn-sdpt-lin-{sm,lg}`, `hl-cn-sdlt-lin-{sm,lg}`, `hl-cn-mdlt-lin-{sm,lg}`, `hl-cn-sdct-lin-sm`, `hl-cn-hapi-lin-{lg,xl}`, `hl-cn-hapi-bn-lin-xl`, `hl-cn-hapi-wraps-lin-lg`, `hl-cn-otter-{fast,full,chaos}-lin`, `hl-cn-default-lin-{ss,sm,md,lg}`, and more. A `runs-on` copied from Chewie's own repo (`swirldslabs-chewie-linux-medium`) won't schedule here, and neither will one copied between workflows without checking.
- **`jq -r` on a missing key yields the string `"null"`**, so a renamed response field surfaces as a corrupt value downstream, not a failed step (§3).
- **`863` does not check `status == approved` directly.** It fails only because a non-approved allocation has no `instances`, which trips the named-group guard (§3).
- **`859`/`863` declare the `chewie-token` secret as "The Chewie API key"** — it is actually the double-encoded JWT from `858`, not the identity key.
- **`decode-b64-jwt.sh` reports errors on stderr with a non-zero exit**, so callers' `[[ "${CHEWIE_JWT}" == Error* ]]` checks never fire; the step fails on the substitution under `bash -e` instead.
- **The scheduled controllers ignore `default_duration`** (§2 table), despite what `chewie.md` says.
- **The status vocabulary Chewie can return is seven values, not the six `859`'s comment lists.** See `hcn-chewie-context`.
- **Chewie's `docs/dev/citr_build_promotion.md` §4 ("Chewie does not dispatch… the tag is the handoff", `push: tags: ['build-*']`) is wrong** and being corrected — Chewie dispatches `226`/`227`. Don't redesign the controllers around tag triggers on its say-so.
- **`221`/`222` declare no `workflow_dispatch` inputs.** Dispatching them with any `inputs` is rejected ("Unexpected inputs provided"); they read the build number from `github.ref` (`build-(.{5})`), so they must be dispatched *on* the build tag.
- **The workflow-dispatch API only accepts a branch or tag as the ref a workflow runs on, never a SHA.** That's why Chewie dispatches `226` on `main` with the SHA as an input, and why `226` dispatches `106` on the candidate's branch.
- **`857-call-workflow-unit-tests` checks out `needs.fetch-xts-candidate.outputs.xts-tag-commit` without having that `needs`** — always empty, so it checks out the default ref. Harmless, but a leftover from 900.

## 8. When a workflow change here needs a check on the Chewie side

Load `hcn-chewie-context` before changing: the shape of `build-compute-request.sh`'s output (Chewie's request DTO), anything parsed in `parse-chewie-allocation.sh` (response DTO and status vocabulary), `release-chewie-allocation.sh`'s status-code handling (Chewie's DELETE contract), the JWT exchange in `858`/`860` (auth model), or anything `apply-allocation-scheduling.sh` renders (Chewie's per-group label/taint scheme).
