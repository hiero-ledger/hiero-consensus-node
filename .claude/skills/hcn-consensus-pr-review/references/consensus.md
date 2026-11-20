# Consensus

Checks that only apply to consensus-layer code. The knowledge lives in the
[consensus-layer KB](/platform-sdk/docs/consensus-layer/). This file says how to use it. Don't copy KB content here.

## Finding the KB entries a PR touches

Search the KB for each changed file's path and for the names of the classes it changes. The hits are spread across the
catalogs:

| Catalog | Where it names code |
|---|---|
| `rules/` | the `components:` field |
| `invariants/` | the `verification:` field and the body |
| `decisions/` (ADRs) | the body |
| `scenarios/` | the body |
| `architecture/`, `concepts/`, `glossary.md` | the body |

Read every hit before judging the change. Each catalog's `README.md` and `FORMAT.md` explain its fields.

**Status decides whether an entry is enforced:**

| Catalog | Enforced | Open question, not settled fact | Not enforced |
|---|---|---|---|
| Rules | `holds` | `divergent` | `retired` |
| Invariants | `enforced` | `divergent` | `proposed` |
| ADRs | `accepted` | | `proposed`, `superseded`, `deprecated` |

For an entry with `divergent` status, raise a question if the change touches it. Never a finding.

## Invariants

An invariant holds under any correct implementation. Breaking one is always a defect.

**Blocking finding when** the change matches a mechanism in the invariant's `## Change risk` section, or otherwise makes
the statement false. Name the invariant and the mechanism.

## Rules

A rule holds for the current implementation and may legitimately change.

**When a change breaks a rule:**

- **No sign it was intended:** blocking finding.
- **The PR says it was intended:** still blocking until the PR also updates the rule entry, either retiring it or
  rewriting it.

A sign of intent is the PR description or linked issue saying so, or the PR changing the rule entry itself.

## ADRs

**When a change goes against an accepted ADR:**

- **No sign it was intended:** blocking finding.
- **The PR says it was intended:** still blocking until the PR adds a new ADR that supersedes the old one.

**Example:** reintroducing nGen-style ordering after ADR-008 replaced it with sequence numbers.

## Scenarios

Scenarios record past incidents and edge cases.

- **Reviewer mode:** list each scenario whose code the PR touches in the orientation, as background.
- **Both modes:** check that the change doesn't reintroduce the scenario. If it might, that's a blocking finding. Name
  the scenario and the step of its sequence the change makes possible again.

## Determinism

Some code must produce the same result on every node, given the same events. Examples: consensus order, consensus
timestamps, judges, round decisions. The KB basis is INV-002 and INV-003. Their `verification:` fields, and those of
related invariants, show which code that is.

**Blocking finding when** that code gains a possible source of nondeterminism:

- iteration over a `HashMap`, `HashSet` or other collection with no defined order, where the order affects the result.
  This includes serialization and hashing. Example: #10428, where serializing the entries of a map "is not guaranteed
  to produce a deterministic byte ordering", so the hash wasn't either.
- the wall clock, or anything else local to the node
- a value each node computes for itself, such as an event's sequence number or `nGen`, used where nodes must agree.
  Example: #18882, "ngen is not deterministic, this would cause an ISS."
- unseeded or node-local randomness
- floating-point arithmetic in a computed result
- a result that depends on thread timing

Name the source and the result it can affect.

### Simulated runs

Turtle and Falcon runs must be reproducible from their seed. A change that breaks this is a correctness finding in a
test fixture ([correctness.md](correctness.md#impact)): it can make a test fail in a way no one can replay.

**Finding when** code that runs in a simulated environment:

- uses `SecureRandom`, an unseeded `Random`, or a seed that isn't derived from the test's seed. Example: #21830, "this
  will make the test non-deterministic, even when executed on Turtle."
- gives nodes their seeds in an order that isn't defined. Example: #13354, nodes built from a set in iteration order,
  so "they will get different starting seeds".
- reads the wall clock (`Instant.now()`, `System.currentTimeMillis()`, `System.nanoTime()`) instead of the injected
  `Time`. Example: #21967, where pipeline metrics mixed `System.nanoTime()`, `Instant.now()` and platform time.

## Data from peers

Events, sync messages, states and signatures received from another node can be wrong or hostile.

**Blocking finding when** the change uses data from a peer before it is validated, or removes or weakens a validation
step, and the PR doesn't explain why the check is no longer needed. Name the data, the check, and what a faulty peer
could do with it.

**Examples:**

- #22327: a state received during reconnect was handed to the state lifecycle manager before its signatures were
  verified. A malicious teacher could have made the node use a wrong state. The author restored the order.
- #22064: a PR removed the signature length check from the internal event validator. The reviewer asked for a bound on
  the length instead.

## The KB going out of date

**Non-blocking finding when:**

- the PR changes behaviour the KB describes, so a KB statement is now false. This covers architecture topics, concepts,
  the glossary, and the text of rules and invariants.
- the PR renames, moves or removes code the KB cites: a file, class, method or config key.

Name the KB file and quote the statement or reference. If the PR doesn't fix it, the item goes in the follow-up block.

The [`consensus-kb-freshness`](/platform-sdk/consensus-kb-freshness/README.md) tool also finds broken references, but it
doesn't run in CI, so the skill checks for them itself.

## Edits to the KB

When the PR edits files under `platform-sdk/docs/consensus-layer/`, check the edits against the KB's own authoring rules.
Read them first; don't restate them here:

- [`CLAUDE.md`](/platform-sdk/docs/consensus-layer/CLAUDE.md): authoring rules
- [`LAYOUT.md`](/platform-sdk/docs/consensus-layer/LAYOUT.md): structure, IDs, the one-home-per-fact rule
- the `FORMAT.md` of each catalog the PR touches: the entry schema

**Non-blocking finding when** an edit breaks one of those rules. These are the rules most often broken:

- a fact copied into a second place instead of linked
- a new entry without its `README.md` index row, or a row without its entry
- a behavioural claim with no anchor to code, or an anchor that points at a line number instead of a symbol
- `last_reviewed` changed by hand, or by an AI tool while editing. Only the freshness checker writes it. Example:
  #26444, where Claude updated it "even after I told it to strictly adhere to the rules".
- a property called an invariant, or a claim called proved, with no source that establishes it. Example: #25966, "I
  thought this was not yet proved and is still a theorem."
- a `status` change not made in the entry, its index row, and related fields together
- future-state design written as current behaviour
- a structural change (new catalog, new ID prefix) without the matching `LAYOUT.md` update

Name the rule, quoting the KB file it comes from. The clarity rules in
[clarity-and-naming.md](clarity-and-naming.md), topic 11, apply to KB edits as well.

## Terminology

Glossary terms are checked in [clarity-and-naming.md](clarity-and-naming.md), topic 8.
