---
name: hcn-chewie-context
description: What Chewie (the CI compute-allocation backplane this repo's CITR workflows depend on, github.com/swirldslabs/chewie) is, what its wire contract looks like on the 2.11 line this repo was built against, and how 3.x (main, v3.8.0) breaks it — per-node allocation, CITR-scheme scheduling labels with no owner/network-id, grants-based auth, suite-aware endpoints, a build/promotion service. Load this before depending on Chewie's allocation API shape, its auth model, its label/taint scheme, or its config file semantics, or before assuming something about it that might be mid-migration.
---

# Chewie, from the hiero-consensus-node side

This repo's CITR workflows (see the companion skill `hcn-citr-context`) call Chewie for every SDPT/SDLT/MDLT allocation. This skill is the reverse view: what Chewie is, its wire contract, and what's moving. Chewie is a fast-moving internal project; treat anything here as dated evidence and re-verify against `github.com/swirldslabs/chewie` before depending on a specific detail.

Verified 2026-09-30 against `origin/main` `27397fd439ba868ce16a3a37f6a6b715e9ee89b1` (`v3.8.0`) and `origin/release/2.11` `cbeab042a61f263ae884c794b59b0d1695c21c65` (one docs-only commit past `v2.11.5`, no newer 2.x tag).

## Which line is in production — unknown, and it matters a lot

- **`release/2.11`** (`v2.11.5`, 2026-09-08) is the line this repo's workflows were written against. Network-based allocation, `solo.hashgraph.io/*` labels in the response.
- **`main`** is at **`v3.8.0`** (2026-09-30) — seven minor releases (`v3.2.0`–`v3.8.0`, 2026-09-11 → 09-30) past the `v3.1.0` this skill last tracked. The Helm chart on `main` is `3.8.0`.

**Nothing in the Chewie repo says which line is deployed.** There are no deploy manifests, environment values, or "production runs vX" notes — the only production facts in its docs are about the database (YugabyteDB `15.12-YB-2025.2.2.2-b0`). Two pieces of evidence point toward 2.11 still being live, but neither proves it:

1. This repo's own parser (`.github/workflows/support/chewie/parse-chewie-allocation.sh` on HCN `main`) still reads `solo.hashgraph.io/{role,owner,network-id}`. Against a 3.8.0 daemon those keys are absent (see below). Branch `27342-citr-workflows-must-be-triggerable-via-chewie` migrates HCN, but it hasn't merged.
2. Chewie's own `.claude/skills/chewie-consensus-node-context/SKILL.md` on `main` says the 3.x label change "must not deploy ahead of" the node-mark applier running during provisioning. That applier isn't on `main` yet (see "In flight").

Confirm the deployed version with whoever operates Chewie before trusting either section below as "live".

## The wire contract — what's stable across both lines

- **Endpoints HCN uses:** `POST /api/v1/auth/token`, `POST /api/v1/compute/allocation`, `GET`/`HEAD`/`DELETE /api/v1/compute/allocation/:id`. Same paths on both lines. `POST /api/v1/auth/token/renew` also exists on both lines; HCN doesn't call it.
- **Token exchange:** the bare `Authorization` header value with **no `Bearer` prefix**. The server does one `base64.StdEncoding.DecodeString` on it, so the caller must send the key already base64-encoded, which `858` does. Every other endpoint uses `Authorization: Bearer <jwt>`. `LoginResponse{token, role, expires_at}` is unchanged.
- **Status vocabulary** (`pkg/server/chewie/models.go`, identical on both lines): `pending, approved, denied, cancelled, released, expired, expired_released`. `expired_released` means the allocation expired with `DeleteNamespaceOnExpiry=false`, and its lease and namespace have since been reclaimed. HCN's poll loop treats anything other than `approved`/`pending` as fatal, so the seventh status needs no handling. Only the six-value comment in `859` is stale.
- **Request body:** `instances[] {group?, quantity, resources {cpu, memory}}`, `duration` (3600–1209600 s), `request_timeout` (optional, 60–604800 s, default 3600), `workflow {run {id, number, attempt}, owner, repository, job}`. **3.x adds an optional `workflow.pull_request` (int)**, which is recorded as given and not validated.
- **Response top level:** `id, status, status_url, namespace, cluster_fqdn, expires_at, instances[]`. Every field except `id`/`status`/`status_url` is present only when the status is `approved`. Each `instances[]` entry is `{group, spec {quantity, resources}, labels, tolerations}`, and **`quantity` is still nested under `spec`**.
- **Group names:** a caller-supplied `group` is echoed back. If it's omitted, 3.x defaults to `consensus-nodes`/`auxiliary-nodes` by role. HCN always supplies its own names (`-g`/`-x`), so this doesn't affect it.
- **Namespace name:** `chewie-<sanitized-repo>-r<allocation-id>` (`internal/compute/namespace.go` `GenerateNamespaceName`), unchanged. In 3.x, namespace *occupancy* is tracked independently of the name (#587), but the name format is the same.
- **`.github/chewie.yaml`:** Chewie still writes it at install time and **still doesn't read `default_duration`/`default_timeout` on the allocation path**. `internal/compute/allocation_config.go:71` and `internal/repoconfig/config.go` both say "NOT wired up". Chewie's fallbacks are 3600/3600. HCN's `862` reads the file client-side with fallbacks 3600/300 (see `hcn-citr-context` §4).

## What 3.x changed that breaks this repo (`v3.1.0` → `v3.8.0`)

### Scheduling labels — **breaks HCN's parser** (#353, #606, #643 in v3.8.0)

On `main`, `instances[].labels` is a pod **nodeSelector** and nothing more. It contains exactly:

- `scheduling.citr.hashgraph.io/allocation-id` — the allocation id, on every group
- `test.citr.hashgraph.io/role` — `cn` or `tc` (**not** `consensus-node`/`auxiliary-services`). `tc` is the scheme's name for the auxiliary-services group.

`tolerations` are one `Equal`/`NoSchedule` entry per label, sorted by key. **There is no owner and no network-id in the response anymore.** Chewie allocates individual machines and has no network to name. The `chewie.hashgraph.io/*` namespace annotations were retired in favour of `lifecycle.`/`scheduling.citr.hashgraph.io/*`. Chewie still *reads* the operator's `solo.hashgraph.io/role` and `solo.hashgraph.io/network-id` node labels to select hardware (`internal/compute/matching.go`), but it never publishes them.

Effect on HCN's `parse-chewie-allocation.sh` against a 3.8.0 daemon: the named-group `jq` lookups for `."solo.hashgraph.io/network-id"`/`owner` return the literal string `"null"`, the unnamed-group fallback yields `"unknown"`, and `ROLE` becomes `"unknown"`. The labels and tolerations pass through generically, so pod scheduling keys follow whatever Chewie publishes. Anything downstream that consumes network-id or owner breaks silently. Chewie's own design notes are explicit: **don't ask Chewie to dual-emit the old keys.** A nodeSelector ANDs its entries, so a second spelling would require every node to carry both. HCN has to move to the new keys and drop its dependence on owner and network-id. This is a required, coordinated change. Branch `27342-…` makes it: the parser no longer reads owner, network-id or role, and every CITR pod takes its nodeSelector and tolerations from its group's `labels`/`tolerations` via `support/chewie/apply-allocation-scheduling.sh` (see `hcn-citr-context` §3). Per Chewie's HCN cutover doc (tracked in swirldslabs/chewie#818), once #826 ships a named group's `test.citr.hashgraph.io/role` is its group name, so HCN addresses groups by name and never compares the role to a fixed vocabulary.

**On v3.8.0 as tagged, those labels don't schedule anything.** The allocation response publishes the CITR keys, but provisioning doesn't write those labels and taints onto the claimed nodes yet, so a pod built from the response matches no machine. The fix is on branch `00817-apply-node-marks-on-provisioning` (see "In flight").

### Descriptive labels — additive (#605)

When approved, the response adds `descriptive_labels {telemetry, kubernetes}` at both the top level and per instance (the per-group map is complete, with allocation-wide values merged in). These are for stamping pods and resources and OTel/Loki telemetry, using the `citr.hashgraph.io` family: `test.citr.hashgraph.io/run-id`, `/category`, `latitude.citr.hashgraph.io/cluster`, `consensus.citr.hashgraph.io/build-num`, `lifecycle.citr.hashgraph.io/instance-group`. A dimension Chewie can't determine is **absent**, never empty. **Never put these in a nodeSelector.** The approved response also adds `nodes[]` (the individual machines held: `node_id`, `name`, …) and, for suite-aware allocations, `profile {suite, version}`.

### Per-node allocation — the network model is gone (v3.2.0)

Allocation by node landed: a per-node lease model (#550), per-node selection and capacity matching (#568), node reclaim on release, expiry, and failure (#576), a node mutation client and RBAC (#564), and per-allocation labels and taints (#575). Then **pre-configured labelled networks were removed** (#584). An allocation is now a set of machines picked per role group, not a whole numbered network. `GET /api/v1/compute/availability` and `GET /api/v1/compute/allocation/:id/availability` report capacity and fit per role group. An MDLT five-network weekly rotation model (#614) also landed.

### Suite-aware allocation — new, opt-in (v3.7.0, #551/#569)

`POST /api/v1/compute/suite/allocation` with `{suite: "sdpt"|"sdlt"|"mdlt", profile_version?, overrides?, workflow}`. The server resolves a versioned profile (`internal/compute/profile_catalog.go`) into an ordinary allocation. You poll and release it through the same `/compute/allocation/:id` endpoints. The v1 profiles mirror HCN's `support/chewie/*-config.json`:

| Suite | CN qty/CPU/mem | Aux qty/CPU/mem | Default duration |
|-------|----------------|-----------------|------------------|
| sdpt  | 9 / 39 / 256000 | 1 / 39 / 256000 | 79200 s (22h)   |
| sdlt  | 8 / 39 / 256000 | 1 / 39 / 256000 | 64800 s (18h)   |
| mdlt  | 8 / 39 / 256000 | 1 / 39 / 256000 | 518400 s (6d)   |

Overrides are bounded by per-group ceilings (2× each value). HCN doesn't use this endpoint yet. It still builds low-level requests with `build-compute-request.sh`.

### Identity and grants — Phase A/B live on main, Phase C still deferred

`internal/auth/jwt.go` `Claims` is `{role, key_id}` plus registered claims, with **no `repo_id`**. Authorization is resolved per request from `authn_grants` (scoped `repository`/`organization`/`global`). In `docs/dev/identity_and_grants.md`, Phase A (expand) and Phase B (migrate) are **shipped**. Phase C (drop the legacy `role`/`repository_id` columns) is **not shipped and deliberately deferred**. Since v3.1.0, `reporter` and `viewer` role codes were added (#642, v3.6.0), along with SAML/Okta SSO with JIT provisioning and group-to-grant mapping for `chewie-web` (v3.5.0). A repo-scoped key like `CHEWIE_REPO_IDENTITY_KEY` still resolves to one repository's grant, so `858`/`860`/`859`/`225` should keep authenticating unchanged. The "one token, one repo" guarantee now comes from how the key is provisioned, not from the claim shape. On 2.11 the claims are still `{role, repo_id, key_id}`, and a repo token carries exactly one `repo_id`.

### Builds, suite results, and promotion — a new Chewie service overlapping HCN's tagging (v3.3.0)

Chewie now has a CITR build-number and build-tag service: atomic per-repository build-number issuance, tags created through the GitHub API with webhook confirmation and reconciliation, suite-result reporting (`POST/GET /api/v1/suites/results`), a per-commit lifecycle and promotion engine (gated by `CHEWIE_BUILD_LIFECYCLE_ENABLED`), build queries (`/api/v1/builds…`), and release-cut recording (v3.6.0). `docs/dev/build_number_contract.md` is written around HCN's `901-cron-promote-build-candidate`. This overlaps what HCN does itself today (`221`'s `sdpt-pass/fail-<build>` tags, `901`'s promotion). HCN doesn't call any of these endpoints yet, so check whether the engine is enabled before assuming Chewie owns tagging. The earlier `internal/build/ref_guard.go` (`AssertBuildTagRef`) no longer exists as a file; its validation lives in the tag creator.

### Admin and ops — irrelevant to CI, good to know

`PATCH /api/v1/admin/allocations/:id` extends an approved allocation (#734, admin-only, no self-service). Maintenance-window lifecycle endpoints `/api/v1/admin/maintenance-windows` (#735), and **scheduled maintenance windows are now authoritative for allocation** (#758): capacity blocked by a window shows up as ordinary `pending`/`denied`. A durable per-request allocation history record (#609). GitHub Actions data archival (`chewie-gha-archive`, v3.7.0). Paginated `GET /api/v1/compute/allocations/active` exists on both lines and HCN doesn't use it.

### chewie-web

`chewie-web` is no longer purely read-only: v3.6.0 added a write API for status overrides, manual suite results, release metadata, and operator commentary, scoped by grants. Its docs still call it an alpha. The chart version tracks the daemon in lockstep (`3.8.0`).

## In flight — check before assuming any of this is settled

- **`00817-apply-node-marks-on-provisioning`** (remote branch, 7 commits on top of `v3.8.0`, 2026-10-01, unmerged). It applies node labels and taints during allocation provisioning, inside the provisioning transaction (`80eddfd`). This is the prerequisite for the 3.x scheduling labels working at all. Until it merges and ships, a 3.x deployment hands HCN a nodeSelector no node satisfies.
- **HCN-side migration off `solo.hashgraph.io/*`** — required before HCN can run against 3.x. Done on branch `27342-citr-workflows-must-be-triggerable-via-chewie`, not yet on `main`. It must ship the same day as a Chewie release carrying #606, #643, #817 and #825, in the order in HCN's `required-chewie-changes.md` item 9.
- **Phase C of identity/grants** — deferred until no running binary reads the legacy columns.
- **`.github/chewie.yaml` server-side reads** — still unwired. Re-check whenever `allocation_config.go`'s "NOT wired up" comment disappears.

## Process notes

Chewie releases via `workflow_dispatch` → semantic-release (`.releaserc`), which bumps the Helm chart and publishes an image. Nothing notifies this repo when the wire contract changes. `release/2.x` and `main` are released independently, and fixes may be cherry-picked to both. When re-verifying this skill: `git fetch`, check `git branch -r` for `release/*`, walk `git log <last-verified-tag>..origin/main`, and reread `pkg/server/chewie/models.go`, `internal/compute/scheduling_labels.go`, `internal/compute/provision.go`, `internal/auth/jwt.go`, and `docs/dev/identity_and_grants.md`. Chewie's own `.claude/skills/chewie-consensus-node-context/SKILL.md` tracks the HCN-facing contract from the other side and is a good cross-check.
