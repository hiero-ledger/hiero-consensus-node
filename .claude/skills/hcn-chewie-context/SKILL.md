---
name: hcn-chewie-context
description: What Chewie (the CI compute-allocation backplane this repo's CITR workflows depend on, github.com/swirldslabs/chewie) currently is, what its wire contract looks like right now — including a status value this repo's workflows don't know about — and which of its several in-flight epics could change that contract out from under this repo. Load this before depending on Chewie's allocation API shape, its auth model, its label/taint scheme, or its config file semantics, or before assuming something about it that might be mid-migration.
---

# Chewie, from the hiero-consensus-node side

This repo's CITR workflows (see the companion skill `hcn-citr-context`) call Chewie for every SDPT/SDLT allocation. This skill is the reverse view: what Chewie is, its current wire contract, and — more importantly — what's still moving. Chewie is a fast-moving internal project; treat anything here as dated evidence and re-verify against `github.com/swirldslabs/chewie` before depending on a specific detail. Verified against Chewie commit `8544f51` (2026-08-20).

## What it is

Chewie is a Go daemon (`chewied`) that leases Kubernetes bare-metal capacity for CI test runs. It serves an HTTPS REST API, persists state in PostgreSQL (production runs YugabyteDB), watches Kubernetes cluster topology via client-go informers, and reacts to GitHub App webhooks. It does not run inside this repo — it's a separate deployment this repo's workflows talk to over HTTPS, authenticated with a repository-scoped JWT.

## The wire contract, as currently implemented

### Status vocabulary — seven values, not six

`pkg/server/chewie/models.go` currently defines:

```
pending, approved, denied, cancelled, released, expired, expired_released
```

**`expired_released` is new** relative to what this repo's own `859-call-create-chewie-request.yaml` documents in its status-vocabulary comment (which lists only the first six). It's produced when an allocation expires with `DeleteNamespaceOnExpiry=false` (an operator/staging setting, not the default) — the namespace and lease are deliberately preserved past expiry for inspection, and the disposition moves from `expired` to `expired_released` only once an operator manually deletes the namespace and the kube informer notices. **This repo's poll loop in `859` doesn't special-case it — any status besides `approved`/`pending` is already treated as fatal there — so this is not a functional bug today, but it means the poll loop's own comment is out of date, and any future code that pattern-matches on "the six statuses" should know there's a seventh.**

### Request/response shape — confirmed unchanged from what this repo's scripts assume

`ComputeAllocationRequest`: `instances` (array of `{group, quantity, resources: {cpu, memory}}`), `duration` (seconds), `request_timeout` (optional, seconds), `workflow: {run: {id, number, attempt}, owner, repository, job}`.

`ComputeAllocationDetail`/response: `namespace`, `cluster_fqdn`, `expires_at`, and `instances[]` where each entry is `{group, spec: {quantity, ...}, labels, tolerations}` — note **`quantity` is nested under `spec`**, matching this repo's `jq '.instances[] | select(.group=="...") | .spec.quantity'`. Nothing on Chewie's side has changed this nesting or field names since this repo's scripts were written against it.

## Auth model

JWT claims (`internal/auth/jwt.go`): `{role, repo_id, key_id}`. Roles are `admin` and `repo`. **A token carries exactly one `repo_id`** — there is no multi-repository grant on a single token today. This repo's per-repository identity key (`CHEWIE_REPO_IDENTITY_KEY`) is exchanged for a JWT scoped to this repo alone; it cannot be reused for another repository's allocations, and there's no way to request a token that spans repos.

The token-exchange endpoint takes the raw API key as a bare `Authorization` header value (**no** `Bearer` prefix) — every other endpoint expects `Authorization: Bearer <jwt>`. This repo's `858-call-get-chewie-jwt.yaml` gets this right; it's an easy detail to get backwards if writing a new caller.

## The `.github/chewie.yaml` config file — written, not (yet) read, on Chewie's side

Chewie's `internal/repoconfig` package renders and commits `.github/chewie.yaml` to a repository at GitHub App installation time (`branches`, `default_duration`, `default_timeout`, `workflows` allowlist). As of the current code, **nothing on Chewie's allocation path reads `default_duration`/`default_timeout` back out of that file** — the package's own doc comments say so explicitly ("NOT wired up"). This repo's `862-call-get-chewie-properties.yaml` reads the same file itself, independently, client-side — see `hcn-citr-context` §4 for the fallback-value mismatch that results. If Chewie ever wires up server-side reading of this file, re-check whether that changes precedence versus what `862` sends explicitly in the request body (`request_timeout`, `duration` derived from `duration-minutes`) — right now the server doesn't look at the file for this at all, so there's no conflict to have.

## What's actively in flight — check before assuming any of this is settled

Chewie has several open epics that would change the wire contract, the label/taint scheme, or the auth model this repo depends on. None of the following has landed as of the pinned commit, but any of them landing is exactly the kind of change that needs a coordinated update on this repo's side (per `hcn-citr-context` §8):

- **Allocation by node, not by network** (epic, children include per-node lease model, per-node capacity matching, node mutation client/RBAC, per-allocation label/taint application). Today Chewie allocates a pre-configured labelled *network* as a whole, not individual nodes — this is the reason the `solo.hashgraph.io/*` label keys pulled out of the response exist as taints at all. Landing this epic changes matching internals but is not expected to change the response shape this repo parses — confirm the instance/label/toleration shape is still what's documented above before relying on it once this lands.
- **Label/taint key migration** to a `citr.hashgraph.io` scheme. **Confirmed still on `solo.hashgraph.io/*`** as of this writing — `role`, `owner`, `network-id` are the exact keys this repo's `859` pulls out of the CN group's labels. This migration is explicitly *not* a big-bang rename in Chewie's own design notes, because these are Kubernetes taints: renaming them would invalidate every toleration and stop pods from scheduling. Expect a transition period where both key families may need to be tolerated, not an atomic cutover.
- **Namespace naming decoupling from the network value** — Chewie currently names namespaces `chewie-<sanitized-repo>-r<allocation-id>`. A change here changes the value substituted into this repo's `%SOLO_NAMESPACE%` template and every `kubectl -n` downstream of it.
- **Suite-aware allocation API** — profiles/config resolved server-side per test suite, plus a possible new suite-aware endpoint shape. Today the request is a flat `instances`/`duration`/`workflow` body built entirely by this repo's own scripts (`build-compute-request.sh`, `<type>-config.json`); if Chewie starts resolving suite profiles server-side, the request shape this repo sends could shrink or change.
- **Identity and grants model** (token → user → grants → role, replacing the single-`repo_id`-per-token JWT claim). If this lands, the "one JWT, one repo" assumption in `858`'s exchange call may no longer be the only shape available — re-check whether a repo identity key still maps 1:1 to a single-repo JWT the same way.
- **Read-only dashboard alpha** — a `chewie-web` binary and UI for build status. Informational for this repo (no wire-contract impact expected), but relevant if anyone asks "is there a UI for this" — yes, but it's alpha and read-only.

## Process notes

Chewie releases via `workflow_dispatch` → semantic-release (`.releaserc`), which computes the next version, bumps its Helm chart, and publishes a container image — there is no automatic notification to this repo when Chewie's wire contract changes. The only way to know is to check Chewie's release notes / CHANGELOG or its own `docs/dev/api_endpoint_reference_implementation.md` before assuming the contract above is still current on a stale skill.
