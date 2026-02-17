---
name: analyze-hip
description: Deep-analyze a Hedera Improvement Proposal (HIP) by number. Fetches from official sources, cross-references the codebase, identifies affected workflows, dependencies, pitfalls, and open questions.
---

Perform a thorough analysis of a Hedera Improvement Proposal (HIP). The HIP number is required.

**Argument:** $ARGUMENTS (required HIP number, e.g. "1137")

If no HIP number is provided, ask the user for one. Do not proceed without it.

---

## Phase 1: Gather HIP Content from Official Sources

### 1a. Check the official HIP site (hips.hedera.com)

Use `WebSearch` with the query `site:hips.hedera.com hip-<NUMBER>` to find the HIP on the official site.
If a result is found, use `WebFetch` on the URL (try both patterns below, as the site may return 403):
- `https://hips.hedera.com/hip/hip-<NUMBER>`
- `https://hips.hedera.com/HIP/hip-<NUMBER>.html`

If WebFetch fails (403), note that the HIP exists on the site but content could not be fetched directly, and rely on the GitHub repo content instead.

Record the HIP's **status** from the search results if visible (Draft, Review, Last Call, Approved, Accepted, Final, Active, etc.).

### 1b. Check the hiero-improvement-proposals GitHub repo

Fetch the HIP markdown file from the repo's `main` branch:
```
gh api repos/hiero-ledger/hiero-improvement-proposals/contents/HIP/hip-<NUMBER>.md --jq '.content' | base64 -d
```

If the file exists on `main`, this is the **canonical version**. Parse the YAML front matter to extract:
- `status`, `type`, `category`, `title`, `author`, `working-group`
- `needs-hiero-approval`, `needs-hedera-review`, `hedera-acceptance-decision`
- `created`, `updated`, `requires`, `replaces`, `superseded-by`, `release`

Read the **full body** — all sections: Abstract, Motivation, Rationale, User Stories, Specification (including protobuf definitions, API changes, new transaction types), Backwards Compatibility, Security Implications, Rejected Ideas, Reference Implementation, etc.

### 1c. Search for open or recent PRs related to this HIP

```
gh search prs --repo hiero-ledger/hiero-improvement-proposals "HIP-<NUMBER>" --json number,title,state,url
```

Also search for PRs that update this HIP:
```
gh search prs --repo hiero-ledger/hiero-improvement-proposals "hip-<NUMBER>" --json number,title,state,url
```

If there are **open PRs**, fetch their diff to understand pending changes:
```
gh pr diff <PR_NUMBER> --repo hiero-ledger/hiero-improvement-proposals
```

### 1d. Cross-reference and compare

- If the HIP is present on **both** hips.hedera.com and the GitHub repo `main` branch, note both sources agree on content (the site is generated from the repo, so they should match).
- If there is an **open PR** modifying this HIP, compare the PR changes against the current `main` version. List all differences in detail: status changes, specification changes, new sections, removed sections, wording changes.
- If the HIP is **not on `main`** but exists in an open PR, use the PR content as the primary source.

Present a summary:
- **Title**: HIP title
- **Status**: Current status (and pending status if a PR changes it)
- **Type/Category**: e.g., Standards Track / Core
- **Authors**: List authors
- **Approval status**: Hiero TSC / Hedera review status
- **Source**: Where the content was found (main branch, open PR, both)

---

## Phase 2: Deep Analysis of the HIP Specification

Read the full HIP content carefully and produce a structured analysis:

### 2a. Summary
Write a concise summary (3-5 paragraphs) of what the HIP proposes, why, and how.

### 2b. Technical Specification Breakdown
Break down the specification into discrete implementation items:
- **New transaction types** (e.g., new `HederaFunctionality` enum values)
- **New or modified protobuf messages** (message definitions, field changes)
- **New state/store requirements** (new singleton states, virtual maps, queues)
- **New or modified queries**
- **Fee schedule changes**
- **Config property changes** (new properties, defaults)
- **System contract changes** (if applicable)
- **Mirror node impacts**
- **SDK impacts**

### 2c. Backwards Compatibility
Analyze the HIP's backwards compatibility section. Identify:
- Breaking changes to existing APIs
- Migration requirements
- State migration (schema versioning implications)

---

## Phase 3: Codebase Impact Analysis

This is the core of the analysis. Thoroughly search the Hiero Consensus Node codebase to map the HIP's requirements against the existing code.

### 3a. Search for existing references
Search for the HIP number in the codebase:
```
Grep for: HIP-<NUMBER>, hip-<NUMBER>, hip<NUMBER>
```
This reveals if implementation work has already started or if the HIP is referenced in comments/TODOs.

### 3b. Identify affected services and modules
Based on the HIP's specification, determine which services and modules will be affected:
- Map new transaction types to the appropriate service module (e.g., token-service, consensus-service, contract-service, etc.)
- Check if a new service module is needed
- Identify which existing handlers, stores, and schemas will need modification

### 3c. Trace affected code paths
For each major change the HIP introduces, trace the code paths that will be affected using the transaction processing pipeline:

1. **Protobuf / HAPI layer**: Search `hapi/hedera-protobuf-java-api/src/main/proto/` for related `.proto` files. Identify which proto files need new messages, fields, or enum values.

2. **Ingest Workflow**: Check `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/ingest/` — will new throttle buckets be needed? New transaction type validation?

3. **Pre-Handle**: Check if new key requirements or signature verification logic is needed.

4. **Handle Workflow**: This is where most implementation happens. Identify:
   - Which `TransactionHandler` implementations need to be created or modified
   - Which stores need new methods or new stores entirely
   - Which state schemas need new fields or new state definitions
   - Fee calculation requirements

5. **Query Workflow**: If new queries are introduced, trace the query handler path.

6. **Dispatcher & Wiring**:
   - `TransactionDispatcher.java` — new `HederaFunctionality` cases
   - `HandleWorkflowModule.java` — Dagger wiring
   - Service `*Handlers` aggregation classes
   - Service `*Impl` classes — schema registration, fee calculator registration

7. **Configuration**: Search `hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/` for config classes that may need new properties or feature flags.

8. **Throttling**: Check `hedera-node/hedera-app/src/main/java/com/hedera/node/app/throttle/` for throttle definitions if new transaction types need throttling.

9. **Fees**: Check fee calculation infrastructure and whether new fee schedules are needed.

### 3d. Dependency graph
Map out the module dependency graph for the changes:
- Which modules will have new `requires` in `module-info.java`?
- Are there circular dependency risks?
- Does the change respect the architecture rule that platform modules never depend on application modules?

### 3e. State and schema impact
If new state is introduced:
- What is the current version in `version.txt`?
- What schema class name should be used? (e.g., version `0.72.0` → `V0720XxxSchema`)
- Which existing schemas in the affected service should be reviewed for migration compatibility?
- Search for existing schema classes in the service to understand the pattern.

### 3f. Test impact
Identify what testing will be needed:
- New unit tests for handlers, stores, schemas
- New EET/HapiSpec tests — search `hedera-node/test-clients/` for similar test patterns
- Integration tests if multi-component interaction is involved

---

## Phase 4: Pitfalls and Risk Assessment

Based on the analysis above, identify potential pitfalls:

### 4a. Implementation pitfalls
- **Incomplete handler registration**: List every file that must be updated when adding a new transaction handler (dispatcher, module wiring, handlers class, fee calculators, throttle config).
- **State migration risks**: If new state is added, what happens during upgrade? Is there a risk of state corruption?
- **Backwards compatibility traps**: Edge cases where existing clients might break.
- **Performance concerns**: Large state changes, expensive computations, throttle considerations.
- **Security considerations**: New attack surfaces, fee manipulation risks, access control gaps.
- **Race conditions**: Concurrent transaction handling issues.

### 4b. Specification ambiguities
- Identify any parts of the HIP specification that are vague, contradictory, or leave room for interpretation.
- Flag any "SHOULD" vs "MUST" language that could lead to inconsistent implementations.
- Note any missing error codes or edge case handling in the specification.

### 4c. Dependencies on other HIPs
- Check the `requires` field and also search the HIP text for references to other HIPs.
- Verify whether dependent HIPs are implemented, in progress, or still in proposal stage.
- Flag any blocking dependencies.

---

## Phase 5: Output Summary

Present the final analysis in this structure:

### 1. HIP Overview
- Title, status, type/category, authors, approval status
- One-paragraph summary

### 2. What Changes
- Bulleted list of all concrete changes (new transactions, messages, state, config, etc.)

### 3. Affected Codebase Areas
- Table mapping each change to the specific modules, files, and code paths affected

### 4. Implementation Checklist
- Ordered list of implementation steps with file paths

### 5. Pitfalls & Cautions
- Numbered list of risks, gotchas, and things to be careful about

### 6. Open Questions & Needed Clarifications
- Numbered list of ambiguities or missing details that need resolution before or during implementation

### 7. Related HIPs & Dependencies
- List of related HIPs with their status and whether they block this implementation
