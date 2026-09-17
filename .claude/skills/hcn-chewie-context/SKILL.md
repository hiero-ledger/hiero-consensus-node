---
name: hcn-chewie-context
description: What Chewie (the CI compute-allocation backplane this repo's CITR workflows depend on, github.com/swirldslabs/chewie) currently is, what its wire contract looks like right now in production — plus a merged-but-undeployed auth overhaul on its dev branch that could ship at any time. Load this before depending on Chewie's allocation API shape, its auth model, its label/taint scheme, or its config file semantics, or before assuming something about it that might be mid-migration.
---

# Chewie, from the hiero-consensus-node side

This repo's CITR workflows (see the companion skill `hcn-citr-context`) call Chewie for every SDPT/SDLT allocation. This skill is the reverse view: what Chewie is, its current wire contract, and — more importantly — what's still moving. Chewie is a fast-moving internal project; treat anything here as dated evidence and re-verify against `github.com/swirldslabs/chewie` before depending on a specific detail.

**Chewie ships on two live branches, and they matter differently to this repo:**

- **`release/2.11`** (tag `v2.11.5`, commit `a54501f41cc5b2898656d4f571cc415fdded7554`, released 2026-09-08) is **production** — what this repo's workflows actually talk to over HTTPS today. Everything under "The wire contract, as currently implemented" below is verified against this branch.
- **`main`** (tag `v3.1.0` plus 3 further commits, HEAD `370e30ca106e3e1461869a513732313d9d78c74a`, 2026-09-08) is the **dev line for an unreleased 3.x**, 19 commits ahead of `release/2.11`'s merge-base. It already contains a merged auth-model overhaul that is **not deployed** — see "Identity and grants model" below. Don't treat anything found only on `main` as live until a 3.x release actually ships.

When re-verifying this skill, check `git branch -r` for the current `release/*` branch and diff it against `main` — don't assume `main` is what's running in production.

## What it is

Chewie is a Go daemon (`chewied`) that leases Kubernetes bare-metal capacity for CI test runs. It serves an HTTPS REST API, persists state in PostgreSQL (production runs YugabyteDB), watches Kubernetes cluster topology via client-go informers, and reacts to GitHub App webhooks. It does not run inside this repo — it's a separate deployment this repo's workflows talk to over HTTPS, authenticated with a repository-scoped JWT.

## The wire contract, as currently implemented (production: `release/2.11`, `v2.11.5`)

### Status vocabulary — seven values, not six, unchanged on `main` too

`pkg/server/chewie/models.go:20-33` (identical on both branches) currently defines:

```
pending, approved, denied, cancelled, released, expired, expired_released
```

**`expired_released` is new** relative to what this repo's own `859-call-create-chewie-request.yaml` documents in its status-vocabulary comment (which lists only the first six). It's produced when an allocation expires with `DeleteNamespaceOnExpiry=false` (an operator/staging setting, not the default) — the namespace and lease are deliberately preserved past expiry for inspection, and the disposition moves from `expired` to `expired_released` only once an operator manually deletes the namespace and the kube informer notices. **This repo's poll loop in `859` doesn't special-case it — any status besides `approved`/`pending` is already treated as fatal there — so this is not a functional bug today, but it means the poll loop's own comment is out of date, and any future code that pattern-matches on "the six statuses" should know there's a seventh.**

### Request/response shape — confirmed unchanged from what this repo's scripts assume, on both branches

`ComputeAllocationRequest`: `instances` (array of `{group, quantity, resources: {cpu, memory}}`), `duration` (seconds), `request_timeout` (optional, seconds), `workflow: {run: {id, number, attempt}, owner, repository, job}`.

`ComputeAllocationDetail`/response: `namespace`, `cluster_fqdn`, `expires_at`, and `instances[]` where each entry is `{group, spec: {quantity, ...}, labels, tolerations}` — note **`quantity` is nested under `spec`**, matching this repo's `jq '.instances[] | select(.group=="...") | .spec.quantity'`. Nothing on Chewie's side has changed this nesting or field names since this repo's scripts were written against it, and it's identical on `main` too.

**Not previously documented here, present on both branches:** `Duration` is bounded `3600`–`1209600` seconds (1h–14d) and `RequestTimeout` is bounded `60`–`604800` seconds (1m–7d) (`models.go:165-171`, both branches) — a request outside these bounds gets rejected server-side. Also present on both branches, informational only: a paginated `GET` active-allocation listing (`internal/compute/active.go`, `ActiveAllocationListResponse`/`ActiveAllocation`). This repo's workflows don't call it — the request/poll/release cycle in `859`/`860`/`225` is the only path this repo uses — but it exists if anyone ever needs to enumerate allocations outside the create-then-poll flow.

## Auth model

**Production (`release/2.11`) — unchanged from before:** JWT claims (`internal/auth/jwt.go`): `{role, repo_id, key_id}`. Roles are `admin` and `repo`; the code comment is explicit that "a repo token may only act on its own repository and must carry a positive repo_id." **A token carries exactly one `repo_id`** — there is no multi-repository grant on a single token today. This repo's per-repository identity key (`CHEWIE_REPO_IDENTITY_KEY`) is exchanged for a JWT scoped to this repo alone; it cannot be reused for another repository's allocations, and there's no way to request a token that spans repos. This is what `858`/`860` talk to right now and it still behaves exactly as documented.

The token-exchange endpoint takes the API key as a bare `Authorization` header value with **no `Bearer` prefix** — every other endpoint expects `Authorization: Bearer <jwt>`. (Precision note, true on both branches and predates even the previous pin: the server does one `base64.StdEncoding.DecodeString` on that header value to recover the raw key, so "bare" means "no `Bearer` prefix," not "literally unencoded" — the caller must still send the key already base64-encoded, which `858-call-get-chewie-jwt.yaml` gets right.) `LoginResponse{token, role, expires_at}` is unchanged on both branches.

### Identity and grants model — **landed on `main`/3.x-dev, merged but NOT deployed to production**

This is the one item that's moved furthest since this skill was last checked, and it's the most important thing to re-verify before trusting anything else here. On `main` (issue #386, merged via PR #574 "Resolve authorization through token, user, and grants"), `internal/auth/jwt.go`'s `Claims` struct **no longer carries `repo_id` at all**. Authorization is instead resolved per-request from a new `authn_grants` table (`internal/auth/authorization.go`, new) — a principal (`authn_identities`) holds zero-or-more grants, each scoped `repository` / `organization` / `global` (`internal/auth/grant_scope.go`, `role_code.go`, both new). `docs/dev/identity_and_grants.md` (new, 656 lines, on `main`) is the authoritative design doc: **Phase A** ("expand" — add the `authn_grants` table) and **Phase B** ("migrate" — dual-write plus a `ResolveAuthorization` path with legacy fallback) are marked **shipped on `main`**; **Phase C** (drop the legacy `role`/`repository_id` columns from `authn_identities`) is explicitly **not shipped, and deliberately deferred**. A pre-migration token still verifies fine on `main` — the old `repo_id` claim, if present, is simply ignored now.

**Practical read for this repo:** a repo-scoped credential provisioned the way `CHEWIE_REPO_IDENTITY_KEY` is today will still resolve to exactly one repo's worth of authorization once this ships — so `858`/`860`/`859`/`225` should keep working unchanged the moment a 3.x release deploys this. What changes is that **the "one JWT, one repo" 1:1 mapping is no longer structurally guaranteed by the claim shape** — it becomes a provisioning-time choice enforced by grants, not a hard constraint of the token. There's also a new admin-only surface for managing identities/grants (`internal/api/v1/admin/grants.go`, `identities_detail.go`, both new, both `main`-only) — irrelevant to this repo's CI workflows, which never call admin endpoints.

**Because Phase A/B are already merged, this is a "could ship any day" risk, not a speculative epic.** A `release/3.x` cut, or a decision to fast-forward `release/2.11` onto `main`, ships this to production with zero further Chewie-side code changes needed. Re-check `internal/auth/jwt.go` on whatever branch is actually deployed before trusting the single-`repo_id` claim shape stated above.

## The `.github/chewie.yaml` config file — written, not (yet) read, on Chewie's side, unchanged on both branches

Chewie's config-rendering package (`internal/repoconfig` on `release/2.11`, reorganized but equivalent on `main`) renders and commits `.github/chewie.yaml` to a repository at GitHub App installation time (`branches`, `default_duration`, `default_timeout`, `workflows` allowlist). As of the current code on **both branches**, **nothing on Chewie's allocation path reads `default_duration`/`default_timeout` back out of that file** — `internal/compute/allocation_config.go:71` on `release/2.11` still comments explicitly that this is "NOT wired up," and `main`'s `internal/installation/service.go`/`query_constants.go` have zero references to either field. This repo's `862-call-get-chewie-properties.yaml` reads the same file itself, independently, client-side — see `hcn-citr-context` §4 for the fallback-value mismatch that results. Re-check this the moment either branch starts referencing these fields server-side.

## What's actively in flight — check before assuming any of this is settled

Chewie has several open epics that would change the wire contract, the label/taint scheme, or the auth model this repo depends on:

- **Allocation by node, not by network** — still in-flight on both branches; no code on `main` implements per-node leasing/matching beyond what's already on `release/2.11`. Today Chewie allocates a pre-configured labelled *network* as a whole, not individual nodes — this is the reason the `solo.hashgraph.io/*` label keys pulled out of the response exist as taints at all. Landing this epic is not expected to change the response shape this repo parses — confirm the instance/label/toleration shape is still what's documented above before relying on it once this lands.
- **Label/taint key migration** to a `citr.hashgraph.io` scheme. **Confirmed still on `solo.hashgraph.io/*` on both branches**, identical in `internal/compute/matching.go:18-20` and `internal/compute/kube_service.go:56` — `role`, `owner`, `network-id` are the exact keys this repo's `859` pulls out of the CN group's labels. This migration is explicitly *not* a big-bang rename in Chewie's own design notes, because these are Kubernetes taints: renaming them would invalidate every toleration and stop pods from scheduling. Expect a transition period where both key families may need to be tolerated, not an atomic cutover.
- **Namespace naming decoupling from the network value** — **unchanged on both branches**: `chewie-<sanitized-repo>-r<allocation-id>`, identical comment text in `internal/compute/namespace.go` on both. A change here changes the value substituted into this repo's `%SOLO_NAMESPACE%` template and every `kubectl -n` downstream of it.
- **Suite-aware allocation API** — still in-flight; no evidence on either branch of a new suite-aware endpoint shape. Today the request is still a flat `instances`/`duration`/`workflow` body built entirely by this repo's own scripts (`build-compute-request.sh`, `<type>-config.json`).
- **Identity and grants model** — **no longer "in flight"; already merged to `main`, awaiting a production release.** See the dedicated section above — this is the epic to watch most closely right now.
- **Read-only dashboard alpha** — a `chewie-web` binary and UI for build status. **Still alpha, still read-only, on both branches** (`docs/dev/chewie_web.md` on both). Chart version tracks the core daemon release lockstep (`2.11.5` in production, `3.1.0` on `main`) — that versioning tells you nothing about the dashboard's own maturity.

## Other things that landed on `main`/3.x-dev, not yet in production

None of these exist on `release/2.11` (confirmed absent via direct tree lookup). They're not relevant to this repo's current CI flow, but know they exist before assuming Chewie's capability surface is what it was:

- **`internal/compute/extension.go`** — `ExtendAllocation`, an **admin-only** operation to push an approved allocation's expiry later. Explicitly documented as having no self-service path, deliberately. Not callable by a repo-scoped token.
- **`internal/compute/maintenance.go`** — admin-gated maintenance-window lifecycle management (replacing hand-run SQL from the admin guide). Infrastructure-wide, no per-repository scoping concept. Could affect capacity availability during a maintenance window, but that would surface as ordinary allocation `pending`/`denied` behavior, not a new wire-contract concern.
- **`internal/build/ref_guard.go`** — `AssertBuildTagRef`, a compensating control validating a build-tag ref string before any future code writes `refs/tags/build-*` to a repo. Has **no callers yet** — dead code today, guarding a capability that doesn't exist yet either.
- **`internal/auth/audit_event_type.go`, `deactivation_reason.go`, `revocation_reason.go`** — vocabulary/enum support for the identity/grants audit trail. No independent relevance beyond that model landing.

## Process notes

Chewie releases via `workflow_dispatch` → semantic-release (`.releaserc`), which computes the next version, bumps its Helm chart, and publishes a container image — there is no automatic notification to this repo when Chewie's wire contract changes. `release/2.x` and `main` (heading toward `3.x`) are versioned and released independently by the same mechanism; a fix can land on both (cherry-picked) while a feature epic lands on `main` only. The only way to know what's actually deployed is to check which branch/tag is running in production (`release/2.11` / `v2.11.5` as of 2026-09-08) — never assume `main`'s HEAD is live — plus Chewie's release notes / CHANGELOG or its own `docs/dev/api_endpoint_reference_implementation.md` before assuming the contract above is still current on a stale skill.
