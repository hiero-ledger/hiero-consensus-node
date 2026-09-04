---
name: hcn-citr-context
description: The CITR control loop as it lives in THIS repo — the NNN-<class>-<slug> numbering scheme, the fully-migrated Chewie call path (858/859/860/861/862, no more legacy fallback), the wire contract fields HCN parses out of a Chewie allocation response, the three disjoint namespace-cleanup crons, and the MATS/XTS/promotion surface around it. Load this before touching any numbered workflow, the Chewie call chain, `.github/chewie.yaml`, or anything that assumes what a CITR workflow does.
---

# CITR in hiero-consensus-node

This is the native, local version of what used to be reconstructed from GitHub each time. It documents the CITR workflow set as it actually sits in this checkout.

## Primary sources — read these first, they are maintained in-repo

Two files in this repo are the authoritative reference and are kept up to date by whoever changes the workflows. Prefer them over this skill wherever they disagree — this skill is a guide to reading them and the traps around them, not a replacement:

- **[`.github/workflows/docs/chewie.md`](/.github/workflows/docs/chewie.md)** — the Chewie integration: repository config keys, required secrets, per-test resource config, the full allocation flow step-by-step, and a "Migration Notes" section listing exactly what was removed when SDPT/SDLT went Chewie-exclusive.
- **[`.github/workflows/docs/workflow-manifest.md`](/.github/workflows/docs/workflow-manifest.md)** — the complete current-file ↔ deprecated-file ↔ deprecated-name table for every workflow in the repo. If you're trying to figure out what a workflow used to be called, or whether a number is still live, this table is the answer.

Verified against commit `18e56484a5` (2026-08-20). Re-verify anything below against current `.github/workflows/` before trusting it on a stale checkout — this repo's CITR surface has moved fast (in the nine days since a prior audit at `853ef60a5e`, an entire fallback/legacy code path was deleted outright).

## 1. The numbering scheme

`NNN-<class>-<slug>.yaml`, `name: "NNN: [CLASS] Title"`. Classes: `user` (workflow_dispatch), `flow` (event entry point), `disp` (dispatch-driven controller), `call` (reusable `workflow_call` callee), `cron` (scheduled). Ranges per `workflow-manifest.md`: `0xx` dry-runs/utilities, `1xx` operational, `2xx` CITR, `3xx` triggered/release, `4xx` reserved (empty), `6xx` test helpers, `7xx` AI helpers, `8xx` reusable callees, `9xx` crons.

At the pinned commit: 132 `.yaml` files under `.github/workflows/`, 78 numbered.

**The duplicate-number trap still exists, but it has shrunk.** A deprecated workflow keeps its number and gets a `Deprecated:` prefix on `name:`, so a number is not unique on disk until you check which file is actually live. Only three collisions remain:

| Number | Live file                                  | Deprecated file                                                                    |
|--------|--------------------------------------------|------------------------------------------------------------------------------------|
| 802    | `802-call-compile-and-spotless-check.yaml` | `802-extract-jdk-version.yaml` (superseded by `854-call-extract-jdk-version.yaml`) |
| 855    | `855-call-extract-citr-vars.yaml`          | `855-extract-citr-vars.yaml`                                                       |
| 857    | `857-call-workflow-unit-tests.yaml`        | `857-call-solo-ge044.yaml` (superseded by `856-call-solo-ge044.yaml`)              |

`200` and `801` — collisions in an earlier audit — no longer collide: `200-user-adhoc-solo-tests.yaml` is now a lone tombstone with no live counterpart at 200 (the live workflow moved to `103-user-solo-tests-adhoc.yaml`), and `801-call-snyk-scan.yaml` is a lone live file. **Still select workflows by filename, not by number**, and check `name:` for `Deprecated:` before trusting a match — the pattern recurs even as individual collisions get cleaned up.

**MQPT is gone, not deprecated.** Merge Queue Performance Tests (`200`/`210`/`220`/`602`/`830` under the old numbering) were deleted entirely, per `chewie.md`'s Migration Notes — there is no file to find, tombstone or otherwise.

**In progress, not yet functional:** `207-user-release-chewie-allocation.yaml` exists (`workflow_dispatch`, one required input `chewie-allocation-id`) but as of this writing is a 9-line stub with no `jobs:` block — it isn't wired to Chewie's `DELETE /api/v1/compute/allocation/:id` yet. Don't treat it as a working release path until it has a body.

## 2. The Chewie call path — fully migrated, no fallback

SDPT and SDLT acquire **all** Kubernetes resources through Chewie now — both scheduled and adhoc runs. There is no non-Chewie path left to fall back to.

```
201/202 (adhoc) or 221/222 (scheduled)   — resolve duration/timeout, dispatch to 831/833
  └─ 831-call-single-day-performance-test / 833-call-single-day-longevity-test
       ├─ 862-call-get-chewie-properties     reads .github/chewie.yaml default_duration/default_timeout
       ├─ 858-call-get-chewie-jwt            exchanges repo identity key for a JWT
       ├─ 861-call-get-test-config           reads support/chewie/<type>-config.json for CN/aux shape
       ├─ 860-call-validate-chewie-jwt
       └─ 859-call-create-chewie-request     POST + poll GET /api/v1/compute/allocation[/:id]
```

The single `acquire-kubernetes-resources` job (no `-chewie`/`-legacy` suffix, no `normalize` fan-in step, no `||` fallback anywhere in `831`/`833`) calls `859` directly. `chewie.md`'s Migration Notes list everything this replaced: the `test-asset` input and hardcoded asset names (`SDPT1`, `SDLT2`); `834-call-read-chewie-response` and the ~26 `<Asset>-<test-type>.json` fixture stubs (file deleted, fixtures deleted — `support/chewie/` now holds exactly four files: `build-compute-request.sh`, `decode-b64-jwt.sh`, `sdlt-config.json`, `sdpt-config.json`); the `validate-chewie-inputs` pre-flight job (Chewie secrets are `required: true` now); the dual-path fan-in/normalize job; the `delete-namespace` input and its teardown job; and the namespace-collision pre-check that used to self-cancel a run. **831/833 never call `DELETE` on an allocation** — Chewie's own reaper and `workflow_run.completed` webhook handler own that entirely now.

MQPT's controllers/callees (`220`/`830`) are confirmed fully removed, not merely unused.

### Authentication — the JWT is base64-encoded twice, confirmed unchanged

`858-call-get-chewie-jwt.yaml`:

```bash
-H "Authorization: ${{ secrets.chewie-key }}"          # bare API key, NO "Bearer " prefix — token-exchange endpoint only
...
chewie_jwt_b64=$(printf '%s' "${chewie_jwt}" | base64 -w 0)
double_encoded_jwt=$(printf '%s' "${chewie_jwt_b64}" | base64 -w 0)
```

Every consumer decodes both layers via `support/chewie/decode-b64-jwt.sh`, which does exactly two sequential `base64 -d` calls ("outer" then "inner" layer) and errors distinctly on each. `859` and `860` each call it once per JWT use. **Decoding once yields base64, not a token.** Every call *after* the exchange uses `Authorization: Bearer ${CHEWIE_JWT}` — the bare-key form is exclusive to `858`'s exchange step.

### The request body — now supports parameterized group names

`support/chewie/build-compute-request.sh`, driven from `859`'s inputs, builds the two-instance-group request:

```
-d <duration-seconds> -q <cn-qty> -c <cn-cpu> -m <cn-memory-mb> -g <cn-group-name (default "cn-nodes")>
-a <aux-qty> -p <aux-cpu> -w <aux-memory-mb> -x <aux-group-name (default "aux-nodes")>
-i <run-id> -n <run-number> -t <run-attempt> -o <owner> -r <repo> -j <job> -e <request-timeout>
```

Group names were hardcoded `"cn-nodes"`/`"aux-nodes"` in an earlier pass; they're now `-g`/`-x` flags with those same values as defaults — a workflow can request differently-named groups without touching the script. Any zero-valued required numeric option is still rejected, so a legitimately-zero field can't be expressed.

Per-test-type resource shapes live in `support/chewie/<type>-config.json`, read by `861`:

| Type   | CN qty/CPU/mem (MB) | Aux qty/CPU/mem (MB) |
|--------|---------------------|----------------------|
| `sdpt` | 9 / 39 / 256000     | 1 / 39 / 256000      |
| `sdlt` | 8 / 39 / 256000     | 1 / 39 / 256000      |

## 3. The polling contract and response parsing — confirmed unchanged in shape

`859` accepts 200 or 202 on both create and poll, polls `GET /api/v1/compute/allocation/:id` every 2 seconds. The code only branches on two statuses: `approved` breaks the loop; `pending` continues until `request-timeout` (env `REQUEST_TIMEOUT`) seconds elapse. **Everything else — `denied`, `cancelled`, `released`, `expired`, or any value the poll loop doesn't recognize — falls through to the same fatal `else` branch**, even though a comment above the check enumerates all six as if they were each handled. This matters more than it looks: see the companion skill `hcn-chewie-context` for a **seventh** status value that now exists on Chewie's side and is not in that comment.

By exact `jq` path, unchanged from before:

- `.namespace`, `.cluster_fqdn`, `.expires_at`
- `.instances[] | select(.group=="<name>") | .spec.quantity` — still nested under `.spec`, not top-level
- `.instances[] | select(.group=="<name>") | .tolerations`
- `.instances[] | select(.group=="<name>") | .labels`, from which `solo.hashgraph.io/role`, `solo.hashgraph.io/owner`, and `solo.hashgraph.io/network-id` are pulled individually — `owner` and `network-id` are read once, from the CN group's labels only, not per-group

`jq -r` on a missing key yields the literal string `"null"`, not an error — a renamed response field surfaces as a corrupt value downstream, not a failed step.

## 4. The `.github/chewie.yaml` duality — a trap worth knowing about

There are **two independent readers** of the same file, and they don't talk to each other:

1. **Chewie's own server** (the `chewie` repo) generates `.github/chewie.yaml` at installation and — as of Chewie's current code — does not yet read `default_duration`/`default_timeout` back out of it for any allocation-time decision. It's written, not consumed, on that side today.
2. **This repo's own `862-call-get-chewie-properties.yaml`** reads the same file directly (`yq e '.default_duration'` / `'.default_timeout'`) to fill in `duration-minutes`/`chewie-request-timeout` before calling `831`/`833` — entirely client-side, entirely independent of Chewie's service.

The two sides even disagree on fallback values if the read fails: `862`'s hardcoded fallbacks are `default_duration=3600` / `default_timeout=300`, while Chewie's own compile-time fallback (used when rendering the template at install time) is 3600 for both. None of this is wired together — it's two separate places that happen to read the same YAML file for related but distinct purposes. If Chewie's repo config reading ever gets wired up server-side, re-check whether `862` becomes redundant or starts disagreeing with the server.

Current live `.github/chewie.yaml` at repo root: `branches: ["*"]`, `default_duration: 72000` (20h), `default_timeout: 3600` (1h), `workflows` commented out (no allowlist).

## 5. Namespace cleanup — three disjoint mechanisms, only one deletes namespaces

Chewie owns allocation lifecycle end to end now: it creates the namespace on approval and reclaims it on expiry. Two crons in this repo do unrelated cleanup and must not be confused with that:

- **`902-cron-auto-namespace-delete.yaml`** (daily `0 23 * * *`) — the one cron that actually deletes namespaces, matching `(solo)-(sdpt|sdlt|mdlt)-n([1-9]|1[0-2])$` (note: `mdlt`, not `mqpt` — the alternation was updated when MQPT was removed). Anchored at the end with a `-nN` suffix, so **Chewie's own `chewie-<repo>-r<id>` namespaces cannot match this regex** — the two lifecycles are disjoint by naming, not by coordination. On the `schedule` trigger only `Dallas_n1`/`Dallas_n2` default to true (`github.event_name == 'schedule' && 'true' || github.event.inputs.Dallas_n1`); every other network is deleted only on manual dispatch with its box ticked.
- **`903-cron-clean.yaml`** (hourly `0 * * * *`) — **does not delete namespaces despite the name.** It purges files *inside* namespaces via `support/citr/cronClean.sh`: stream directories older than 59 minutes, plus `mc rm --older-than 0d1h0s` against the `solo-streams`/`solo-backups` MinIO buckets. Its matching regex (`solo[-].*[-]n[0-9]`) is looser than `902`'s — no suite-name restriction, no upper bound on network number.

Cluster access is via Teleport, not a kubeconfig: `teleport-actions/auth-k8s` against `hashgraph.teleport.sh:443`, wrapping cluster hosts `k8s.pft.dal.lat.ope.eng.hashgraph.io` / `k8s.pft.chi.lat.ope.eng.hashgraph.io`.

## 6. MATS, XTS, and promotion — confirmed unchanged

| Concern      | Path                                                                                                                                                                                        |
|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| MATS         | `600-flow-pull-request-checks` → `800-call-mats-tests`; dry run `000-user-dry-run-mats-suite`                                                                                               |
| XTS          | `900-cron-extended-test-suite` (`0 */3 * * *`) → `815-call-xts-tests`; preparation `302-disp-prepare-extended-test-suite`; dry run `001-user-dry-run-extended-test-suite`                   |
| Promotion    | `901-cron-promote-build-candidate` (`0 1 * * 2-6`)                                                                                                                                          |
| Constituents | `803`–`809`, `815`–`826` (unit, integration, HAPI, timing-sensitive, hammer, Otter, dependency check, JRS, JSON-RPC relay, TCK, mirror-node, block-node regression, determinism, migration) |

Result tags are the integration surface, not workflow outputs: `221` tags `sdpt-pass-<build>`/`sdpt-fail-<build>` (build tag regex `build-(.{5})`), GPG-signed via `step-security/ghaction-import-gpg`. `223-disp-sdct-controller.yaml` has zero Chewie references — SDCT's allocation still runs out-of-band (Jenkins-orchestrated), only its verdict participates in promotion.

## 7. Traps

- **Runner names are per-repository and there are a lot of them.** Chewie's own jobs run on `hl-cn-chewie-lin-sm`. This repo also has `hl-cn-default-lin-sm`, `hl-cn-sdpt-lin-lg`, `hl-cn-sdlt-lin-lg`, `hl-cn-hapi-lin-xl`, `hl-cn-hammer-lin`, `hl-cn-otter-*`, `hl-cn-compile-app-lin`, `hl-cn-bn-regression-lin-{lg,sm}`, `hl-cn-{docker,gradle}-determinism-lin-*`, `hl-cn-jrs-regression-lin-lg`, and more — a `runs-on` copied from Chewie's own repo (`swirldslabs-chewie-linux-medium`) will never schedule here, and neither will one copied between two workflows in this repo without checking its specific runner label.
- **`jq -r` on a missing key yields the string `"null"`**, so a renamed or restructured response field surfaces as a corrupt value downstream, not a failed step — see §3.
- **The `default_duration`/`default_timeout` reads in `862` are this repo's own client-side logic, not a Chewie feature.** See §4 before assuming a change to `.github/chewie.yaml` semantics is something Chewie's server will honor.
- **The status vocabulary Chewie can return is not the six-value list this repo's comments enumerate.** See the companion skill.

## 8. When a workflow change here needs a check on the Chewie side

Load `hcn-chewie-context` before changing: the shape of `build-compute-request.sh`'s output (Chewie's request DTO), anything parsed out of the response in `859` (Chewie's response DTO and status vocabulary), the JWT exchange in `858`/`860` (Chewie's auth model), or the `solo.hashgraph.io/*` label/taint keys pulled from the response (Chewie's node-labeling scheme, mid-migration per its own roadmap).
