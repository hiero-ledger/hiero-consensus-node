# ADR: No Coordination Between PCES Writer and Signed State Writer for Snapshot PCES Copy

## Status

Accepted

## Context

A signed state captures the ledger at a single point in time. By itself, it does not contain the
events higher in the hashgraph that are required to reach consensus on that state. Those events live
in the **Preconsensus Event Stream (PCES)**, written to disk by the PCES module in its own location.

To make a signed state directory **self-contained**, the signed state writer performs a
**best-effort copy** of the relevant PCES files into the signed state directory after the merkle
state is written. A self-contained snapshot is useful in two scenarios:

1. **Bootstrapping test networks.** Hashgraph routinely uses uploaded mainnet signed state
   directories to stand up test networks for validating upcoming version upgrades and migrations.
2. **Debugging an ISS (Inconsistent State Signature).** When the network diverges or halts, having
   the PCES alongside the state allows engineers to reconstruct what happened.

The PCES writer and the signed state writer operate independently. There is no coordination between
them: when the signed state writer attempts to copy a PCES file, that file may still be open and
actively being written by the PCES writer. The copy then fails for that file.

This has been observed empirically in tests:

- When the signed state writer copies PCES files **after** the merkle state is written, copy
  failures are not seen.
- When PCES files are copied **first**, copy failures occur intermittently.

The working theory is that under low event-creation rates and/or low TPS, a PCES file may remain
open for an extended window — long enough to still be open at the moment the signed state is being
written. If the copy step runs while the file is mid-write, it fails.

The team considered introducing explicit coordination (e.g., having the signed state writer wait
for the PCES writer to close the current file, or signaling between the two modules) but elected
not to.

## Decision

**Do not introduce coordination between the PCES writer and the signed state writer.** The PCES
copy into the signed state directory remains a best-effort operation. Occasional missing PCES files
in a signed state directory are accepted as tolerable.

The current ordering — copying PCES files **after** the merkle state is written — is preserved
because empirical evidence shows it makes copy failures rare in practice.

## Consequences

### Positive

- **No new cross-module dependency.** The PCES module and the signed state writer remain
  independent, keeping module boundaries clean.
- **No implementation cost.** No engineering time spent designing, building, and maintaining a
  coordination mechanism.
- **No performance risk.** Coordination would likely require synchronization or blocking that could
  impact low-latency environments. Avoiding it preserves current performance characteristics.

### Negative

- **PCES files may occasionally be missing from a signed state directory.** This is acceptable
  given the actual recovery paths:
  - For **bootstrapping test networks**: if a given state directory is missing PCES files, the
    next available state directory can be used instead.
  - For **ISS debugging**: if PCES files are missing from the relevant state directory, the node
    operator can be contacted directly, since PCES files are also written to the PCES module's
    own location on disk.
- **The condition is more likely under low load** (low event creation / low TPS), which is when
  PCES files stay open the longest. Production mainnet load makes this rare, but lower-traffic
  environments may see it more often.

### Neutral

- The chosen file-write ordering (merkle state first, then PCES copy) is a soft convention enforced
  by current code, not a guarantee. If that ordering is changed in the future, copy failure rates
  are expected to increase. This ADR documents the rationale for keeping the current order.

## Alternatives Considered

### 1. Coordinate file closure between PCES writer and signed state writer

The signed state writer would block (or be signaled) until the PCES writer closed any open file
before attempting the copy.

**Rejected because:**

- Introduces a new dependency between two modules that are otherwise independent.
- Requires non-trivial design and implementation effort.
- Risks adding latency to the signed state write path, which is performance-sensitive in
  low-latency environments.
- Solves a problem (occasional missing PCES files) whose actual impact is low.

### 2. Retry the copy until it succeeds

The signed state writer would retry failed copies until the PCES file is closed.

**Rejected because:**

- Still couples the two writers via timing assumptions.
- Adds latency and unbounded wait risk in the signed state write path.
- Provides marginal benefit over accepting the occasional miss.

### 3. Status quo — accept best-effort copy

Selected. See **Decision** above.

## References

- Related: [`pces-disaster-recovery.md`](../core/pces-disaster-recovery.md) — describes the recovery
  procedure that consumes PCES files alongside a signed state.
- Related: [`signed-state-snapshot-spec.md`](../core/signed-state-snapshot-spec.md) — describes the
  on-disk layout of a signed state directory, including its PCES sub-tree.

## Authors / Deciders

- Artur Biesiadowski (@abies)
- Timo Brandstätter (@timo0)
- Michael Heinrichs (@netopyr)
- Kelly Greco (@poulok)
- Maximiliano Tartaglia (@mxtartaglia-sl)
