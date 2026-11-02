---
hip: 0000 # Assigned by HIP editor.
title: Block Stream Errata and Amendments
author: Derek Riley (<github-handle>)
working-group: Jasper Potts, Joseph Sinclair, Mark Blackman, Nana, Pranali Deshmukh
discussions-to: <URL of the GitHub Pull Request for this HIP>
type: Standards Track
category: Core
needs-hiero-approval: Yes
status: Draft
created: 2026-10-31
updated: 2026-10-31
requires: 1056
replaces: 639
---

## Abstract

The block stream (HIP-1056) can contain mistakes, and a consensus node can, in rare cases, compute
incorrect state. This HIP defines two new privileged transactions:

- **`BlockAmendment`** corrects the *record* of one or more past blocks when the network executed
  correctly but the block stream output was wrong (items missing, extra, or incorrect). The
  original blocks are never rewritten. Each amendment is a set of additive edits addressed against
  one original block's positions, and each successful transaction carries its own proof from the
  later block M that contains it.
- **`StateErrata`** corrects *state* when the network executed incorrectly. The corrective state
  changes are applied to live state in block M, where the transaction is handled.

Only the treasury (`0.0.2`) or system admin (`0.0.50`) account may pay for either transaction. Both
flow through consensus, so every block stream consumer sees them, whether or not it reads from a
Block Node. This HIP also defines a new `BlockItem` that Block Nodes use to store a block together
with its amendments, and a deterministic replay algorithm. Hiero state is a Merkle tree whose shape
depends on the order of insertions and removals. The replay algorithm therefore pins every state
change to an exact position, so replaying an amended block reproduces the state root hash already
signed into the next block.

## Motivation

Over roughly seven years of the record stream, the network made about 110 distinct record stream
export mistakes. Some happened once; others recurred over months. They are documented in the
Mirror Node repository and the Block Node wrapped-record tooling, and were corrected as
"amendments" in wrapped record blocks (HIP-1427). The block stream has no equivalent first-class,
provable mechanism.

There are two different failure modes, and they need different fixes:

1. **The consensus node executed correctly but emitted the wrong output.** State is correct; the
   block's description of what happened is not. A Block Node that maintains state will compute a
   state root that does not match the one signed into block N+1, which is an ISS at the Block Node.
   Mirror Nodes will show records that do not reconcile.
2. **The consensus node executed incorrectly.** State itself is wrong (for example, an incorrect
   balance, or in the worst case HBAR minted or burnt). The history between the bad block and the
   fix cannot be changed; only the present can be corrected.

HIP-639 attempted to address errata but was never implemented, defined no transaction, and only
covered amendments. This HIP replaces it.

## Rationale

### One question decides the category

> **Did the consensus node apply state correctly?**

|                     |                        **Amendment**                         |                         **Errata**                         |
|---------------------|--------------------------------------------------------------|------------------------------------------------------------|
| Transaction         | `BlockAmendment`                                             | `StateErrata`                                              |
| What went wrong     | Block stream output for block N is wrong; state is correct   | State is wrong                                             |
| What is fixed       | The historical record of block N                             | Live state, from block M onward                            |
| Where the fix lives | Attached to block N (after its footer) by Block Nodes        | State changes inside block M                               |
| Trust anchor        | Proof from block M; state edits also self-verify against N+1 | Block M's normal block proof                               |
| History N..M        | Becomes fully correct once amended                           | Remains wrong; this cannot be undone                       |
| Governance weight   | Low: fixes records, not value                                | Depends on the change; high if value moves between parties |

### Original blocks are immutable

Everything from `BlockHeader` to `BlockFooter`, and the block proof, stays byte-identical forever.
The block hash chain never changes. Corrections are additive and each is independently provable.

### Two transaction types, one authorization rule

Amendments and errata are separate transaction types because their governance weight differs
greatly. Separate types give each its own `HederaFunctionality`, so networks can restrict them
independently through API permissions. They also confine the power to write arbitrary state to the
errata handler alone, and let Mirror Nodes, explorers, and SDKs tell value-changing corrections
apart from record fixes by type. Both types share one authorization rule: only the treasury or
system admin account, whose keys are held by the network's governing body, may pay for them.

### Amendments are addressed in original-block positions

Hiero state is stored in a `VirtualMap`, a binary Merkle tree in which leaf *positions* depend on
the order of mutations:

- **Insert** of a new key places it at the right child of the current first leaf and moves that
  leaf to the left child.
- **Remove** moves the last leaf into the vacated position and moves its sibling up to the parent.
- **Update** of an existing key keeps its position.

Applying the same set of changes in a different order therefore generally produces a different root
hash. A state amendment must say exactly *where* each state change goes, and replayers must apply
state changes in exactly that order.

Every position in an amendment refers to the **original** block, never to a previously amended
version. Inserted content can never be targeted by a later operation. As a result:

- No replayer ever shifts indexes. The amended block is produced by a single pass over the
  original block.
- Amendments compose. Any number of amendments to the same block, submitted at any time, apply in
  consensus order without knowing about each other.
- A large fix can be split across several transactions without invalidating the positions used by
  any other amendment.

### One transaction can amend several blocks

Some export mistakes recurred in many blocks. A `BlockAmendment` transaction therefore carries a
list of amendments, each naming the block it amends. This lets one signed transaction, with one
proof, fix the same mistake across a range of blocks. Each amendment is self-contained: its
positions, its operations, and its voiding flag concern only its own block. A transaction amends
each block at most once, so the amendments of any one block keep a single, well-defined consensus
order. The transaction succeeds or fails as a whole, so a batch of related fixes is never partly
applied.

### A bad amendment is corrected by voiding and restating

An amendment can declare that every earlier amendment of the same block is void. Its operations
then apply to the original block as if no earlier amendment existed. To correct a bad amendment,
the author restates the complete intended set of edits against the original block, without
needing to understand what each earlier amendment changed. Later amendments without the flag
compose on top as usual, so a large restatement can still be split across several transactions:
only the first sets the flag.

### Payloads are serialized bytes

`StateChange` and `BlockItem` cannot be referenced as typed fields from `TransactionBody`.
`block/stream/output/state_changes.proto` imports `services/state/schedule/schedule.proto`, which
imports `services/transaction.proto`, so a typed field would create a protobuf import cycle.
Carrying serialized bytes also preserves exact item bytes, which matters because block hashes are
computed over serialized items.

### Amendments are stored between the footer and the proof

Placing amendment items after `BlockFooter` and before `BlockProof` keeps proofs as the last items
of every block, and makes amendments unambiguous: nothing else is permitted between footer and
proof.

## User stories

1. As an on-duty engineer, I found that block N is missing a transaction result. I want to submit
   an amendment that inserts it at the right place so Mirror Node records reconcile, with a proof
   anyone can check.
2. As a Block Node operator, my node stopped tracking state because block N omitted a state change.
   I want an amendment that inserts the missing change at its exact position so that replaying
   block N reproduces the state root signed into block N+1, and my node can resume.
3. As the network's governing body, a bug credited the wrong account. I want to correct the
   balances with an auditable, signed transaction whose effects are visible in the block stream.
4. As a Mirror Node or other consumer not reading from a Block Node, I want to learn about every
   correction from the block stream itself.
5. As an auditor, I want to verify that an amended block is exactly its original, byte-identical
   contents plus a set of individually proven amendments.

## Specification

### Terminology

- **Block N**: a block being amended. One transaction may amend several blocks.
- **Block M**: the later block containing the `BlockAmendment` or `StateErrata` transaction.
- **Original items**: the items of block N from `BlockHeader` (index 0) through `BlockFooter`
  (index `F`), as covered by block N's proof.
- **Item index**: zero-based position of an original item.
- **State change index**: zero-based position of a `StateChange` within an original `StateChanges`
  item.
- **Gap**: a position *between* elements. Inserts target gaps; replace and remove target elements.

### HAPI changes

#### `HederaFunctionality`

```protobuf
enum HederaFunctionality {
    // ...
    /**
     * Amend the block stream record of one or more past blocks.
     */
    BlockAmendment = 130;

    /**
     * Correct network state by applying state changes.
     */
    StateErrata = 131;
}
```

#### `TransactionBody`

```protobuf
message TransactionBody {
    // ...
    oneof data {
        // ...
        com.hedera.hapi.node.network.BlockAmendmentTransactionBody block_amendment = 93;
        com.hedera.hapi.node.network.StateErrataTransactionBody state_errata = 94;
    }
}
```

#### `NetworkService`

```protobuf
service NetworkService {
    // ...
    rpc blockAmendment (Transaction) returns (TransactionResponse);
    rpc stateErrata (Transaction) returns (TransactionResponse);
}
```

#### `services/block_amendment.proto`

```protobuf
syntax = "proto3";

package com.hedera.hapi.node.network;

option java_package = "com.hederahashgraph.api.proto.java";
// <<<pbj.java_package = "com.hedera.hapi.node.network">>>
option java_multiple_files = true;

/**
 * Amend the block stream record of one or more past blocks. The
 * transaction succeeds or fails as a whole.
 */
message BlockAmendmentTransactionBody {
    /**
     * The amendments, one per amended block. MUST NOT be empty. No two
     * entries may have the same block_number. The order of entries has no
     * meaning, because each entry amends a different block.
     */
    repeated BlockAmendment amendments = 1;
}

/**
 * An amendment of a single past block.
 *
 * Every position in this message refers to the ORIGINAL items of the
 * amended block, from its block header (index 0) through its block footer.
 * Positions never refer to items inserted by this or any other amendment.
 */
message BlockAmendment {
    /**
     * The block being amended. MUST be less than the number of the block
     * containing the transaction.
     */
    uint64 block_number = 1;

    /**
     * Edits to the original block. No two operations may target the same
     * original item, state change, or insertion point. An amendment MUST NOT
     * combine a replace or remove of an item with any state change operation
     * on that same item.
     */
    repeated BlockAmendmentOperation operations = 2;

    /**
     * Whether this amendment voids every earlier amendment of the same
     * block. If true, consumers discard all amendments of this block that
     * precede it in consensus order and apply its operations to the original
     * block. Later amendments without this flag compose on top of it. With no
     * operations, this restores the original block. Affects only the block
     * named by block_number.
     */
    bool void_previous_amendments = 3;
}

message BlockAmendmentOperation {
    oneof operation {
        InsertBlockItems insert_block_items = 1;
        ReplaceBlockItem replace_block_item = 2;
        RemoveBlockItem remove_block_item = 3;
        InsertStateChanges insert_state_changes = 4;
        ReplaceStateChange replace_state_change = 5;
        RemoveStateChange remove_state_change = 6;
    }
}

/**
 * Insert new block items immediately after original item `after_item_index`.
 * To insert after a transactional unit, use the index of the unit's last item.
 * `after_item_index` MUST be less than the index of the block footer.
 */
message InsertBlockItems {
    uint32 after_item_index = 1;
    /** Each entry is a serialized com.hedera.hapi.block.stream.BlockItem. */
    repeated bytes block_items = 2;
}

/** Replace original item `item_index`. */
message ReplaceBlockItem {
    uint32 item_index = 1;
    /** A serialized com.hedera.hapi.block.stream.BlockItem. */
    bytes block_item = 2;
}

/** Remove original item `item_index`. */
message RemoveBlockItem {
    uint32 item_index = 1;
}

/**
 * Insert state changes into the original StateChanges item at `item_index`,
 * immediately before its original state change at `before_state_change_index`.
 * A value equal to the number of original state changes in the item appends
 * at the end.
 */
message InsertStateChanges {
    uint32 item_index = 1;
    uint32 before_state_change_index = 2;
    /** Each entry is a serialized com.hedera.hapi.block.stream.output.StateChange. */
    repeated bytes state_changes = 3;
}

/** Replace one original state change. */
message ReplaceStateChange {
    uint32 item_index = 1;
    uint32 state_change_index = 2;
    /** A serialized com.hedera.hapi.block.stream.output.StateChange. */
    bytes state_change = 3;
}

/** Remove one original state change. */
message RemoveStateChange {
    uint32 item_index = 1;
    uint32 state_change_index = 2;
}
```

#### `services/state_errata.proto`

```protobuf
syntax = "proto3";

package com.hedera.hapi.node.network;

option java_package = "com.hederahashgraph.api.proto.java";
// <<<pbj.java_package = "com.hedera.hapi.node.network">>>
option java_multiple_files = true;

/**
 * Correct network state. The changes apply verbatim, in order, and
 * atomically, in the block containing this transaction.
 */
message StateErrataTransactionBody {
    /** Each entry is a serialized com.hedera.hapi.block.stream.output.StateChange. */
    repeated bytes state_changes = 1;
}
```

#### Response codes

```protobuf
enum ResponseCodeEnum {
    // ...
    /** A block amendment targeted a block not before the block containing it, or a block twice. */
    INVALID_AMENDMENT_BLOCK_NUMBER = 572;
    /** A block amendment operation was unparseable, empty, disallowed, duplicated, or conflicting. */
    INVALID_AMENDMENT_OPERATION = 573;
    /** A transaction amended no block, or one of its amendments had no operation and did not void. */
    EMPTY_BLOCK_AMENDMENT = 574;
    /** A state errata change was unparseable, unsupported, of an unknown state, or of the wrong types. */
    INVALID_ERRATA_STATE_CHANGE = 575;
    /** A state errata contained no state changes. */
    EMPTY_STATE_ERRATA = 576;
}
```

### Authorization, fees, throttles, and size

Both transactions are treated like `Freeze`, the existing privileged system transaction.

|        Aspect         |                                                                      Rule                                                                       |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Payer                 | MUST be the treasury (`0.0.2`) or system admin (`0.0.50`). See the failure codes below.                                                         |
| Ingest                | Privileged: payers at or above the first user entity are rejected at ingest with `NOT_SUPPORTED`.                                               |
| API permissions       | `blockAmendment` and `stateErrata` entries in `api-permission.properties`, default `2-50`. The treasury/system admin rule above always applies. |
| Signatures            | The payer's key. No other keys are required.                                                                                                    |
| Fees                  | None. Both types are fee-exempt, as `Freeze` is; fees for superuser payers are waived in any case.                                              |
| Throttles             | No throttle bucket, as for `Freeze`. The treasury and system admin are throttle-exempt.                                                         |
| Size                  | Governed by the governance transaction size limit (`governanceTransactions.maxTxnSize`, default 133,120 bytes).                                 |
| Scheduling / batching | NOT schedulable. NOT permitted inside an atomic batch (`BATCH_TRANSACTION_IN_BLACKLIST`).                                                       |

Payers other than the treasury and system admin fail as follows:

- A non-system payer (at or above the first user entity) fails at ingest with `NOT_SUPPORTED`.
- A system payer outside the API permission range passes ingest and fails at consensus with
  `UNAUTHORIZED`.
- A system payer inside the API permission range fails at consensus with `AUTHORIZATION_FAILED`.

### Consensus node behavior

#### `BlockAmendment`

The consensus node keeps no historical blocks, so it cannot check that an amendment is *correct*.
It checks that the transaction is *well-formed*, and changes no state.

1. `amendments` MUST NOT be empty, else `EMPTY_BLOCK_AMENDMENT`.
2. No two amendments may have the same `block_number`, else `INVALID_AMENDMENT_BLOCK_NUMBER`.

Every amendment MUST then satisfy these rules:

3. `block_number`, compared as an unsigned 64-bit integer, MUST be less than the number of the
   block stream block being built, else `INVALID_AMENDMENT_BLOCK_NUMBER`. This is the block
   stream block number (from the `BlockStreamInfo` singleton, plus one), which differs from the
   record stream block number while both streams are produced.
4. There MUST be at least one operation, or `void_previous_amendments` MUST be set, else
   `EMPTY_BLOCK_AMENDMENT`.
5. Every payload MUST parse as its declared type, every insert list MUST be non-empty, and every
   state change MUST have its operation set.
6. Inserted or replacement block items MUST be one of `signed_transaction`, `transaction_result`,
   `transaction_output`, `state_changes`, or `trace_data`.
7. No two operations may target the same item, state change, or insertion point.
8. An amendment MUST NOT combine a `replace_block_item` or `remove_block_item` of item `i` with any
   state change operation on item `i`.
9. Item 0, the block header, MUST NOT be replaced, removed, or targeted by a state change
   operation. Items may be inserted after it.

Rules 5 to 9 fail with `INVALID_AMENDMENT_OPERATION`. All rules except rule 3 are checked at
ingest. If any amendment breaks a rule, the whole transaction fails and no block is amended. On
success, the transaction's `SignedTransaction` and `TransactionResult` in block M are the amendment
of record for every block it amends.

#### `StateErrata`

1. There MUST be at least one state change, else `EMPTY_STATE_ERRATA`.
2. Each entry MUST parse as a `StateChange` whose operation is `singleton_update`, `map_update`,
   `map_delete`, `queue_push`, or `queue_pop`, with all required content present. `state_add` and
   `state_remove` are not supported, because states are created and removed only by schema
   migrations.
3. `state_id` MUST identify a state that a registered service currently defines, according to its
   schemas.
4. The operation MUST match the state's kind: singleton, key/value, or queue.
5. Keys and values MUST be of the state's key and value types.

Rule 2 is checked at ingest; rules 2 to 5 fail with `INVALID_ERRATA_STATE_CHANGE`. Every change is
validated before any is applied, so an errata either applies completely or not at all. The changes
are applied, in order, to the transaction's writable state; they are committed and emitted in
block M exactly as for any other transaction.

Errata are applied verbatim. The node performs no derived bookkeeping such as entity counts. An
errata that needs such changes MUST include them explicitly.

Like every transaction, an errata's record is finalized from the net changes in its writable
state. For errata that modify accounts, this has these consequences in the reference
implementation:

- Net HBAR balance changes appear in the record's transfer list, so record stream consumers see
  balance corrections.
- The net HBAR change across all accounts MUST be zero, and every balance MUST stay between zero
  and the maximum, else the errata fails with `FAIL_INVALID`. An errata cannot mint or burn HBAR.
- Staking rewards are settled for modified accounts staked to a node, which adds changes beyond
  those in the errata.
- Every modified account MUST still exist, so an errata cannot remove an account.

Whether errata should keep these semantics is Open Issue 3.

### Amendment semantics

#### Composition

The **amendments of block N** are, from each successful `BlockAmendment` transaction that amends
block N, its one amendment whose `block_number` is N, in the consensus order of those transactions.
Amendments of other blocks in the same transactions play no part in block N.

The **amended view** of block N is its original items combined with its amendments, applied in
consensus order, starting from the latest valid amendment that sets `void_previous_amendments`
(or from the first amendment, if none does). Each operation carries a sort key
`(consensus order of its amendment, position within the amendment's operations)`.

- **Inserts at the same gap** are all kept, in sort-key order.
- **Replace or remove of the same element** from different amendments: the operation with the
  greatest sort key wins.
- **Item-level replace or remove** of item `i` takes precedence over every state change operation
  on item `i`.
- **Voiding**: an amendment that sets `void_previous_amendments` discards every earlier amendment
  of its block, and of no other block. Its operations, and those of later amendments, apply to the
  original block.
- **Bounds**: when a replayer applies an amendment to block N, every index MUST be in range, every
  state change operation MUST target a `StateChanges` item, and neither the header nor the footer
  may be replaced or removed. An amendment that violates this is invalid and MUST be ignored in
  its entirety, including `void_previous_amendments`. A malformed restatement therefore leaves the
  previous amended view in place rather than reverting the block to its original.

#### Replay algorithm

```text
amendedItems(original, amendments):
    F = index of original BlockFooter
    out = []
    for i in 0..F:
        d = winning item-level replace/remove for i
        if d is Remove:
            pass
        else if d is Replace:
            out.append(d.block_item)
        else if original[i] is StateChanges and any state change op targets i:
            sc = original[i].state_changes
            changes = []
            for p in 0..len(sc):                       # inclusive: p == len(sc) is the end gap
                for ins in inserts at (i, gap p), by sort key:
                    changes.extend(ins.state_changes)
                if p < len(sc):
                    e = winning replace/remove for (i, p)
                    if e is Replace: changes.append(e.state_change)
                    else if e is None: changes.append(sc[p])
            out.append(StateChanges(original[i].consensus_timestamp, changes))
        else:
            out.append(original[i])
        if i < F:
            for ins in item inserts after i, by sort key:
                out.extend(ins.block_items)
    return out
```

#### Example

Original block N:

```text
0 BlockHeader
1 RoundHeader
2 EventHeader
3 SignedTransaction (A)
4 TransactionResult (A)
5 StateChanges [c0, c1, c2]
6 SignedTransaction (B)
7 TransactionResult (B)
8 StateChanges [d0, d1]
9 BlockFooter
```

Amendment 1 (earlier): `InsertStateChanges(item 5, before 1, [x])`,
`InsertBlockItems(after 7, [StateChanges [y]])`.
Amendment 2 (later): `InsertStateChanges(item 5, before 1, [z])`, `RemoveStateChange(item 8, 0)`.

Amended view:

```text
BlockHeader, RoundHeader, EventHeader,
SignedTransaction (A), TransactionResult (A),
StateChanges [c0, x, z, c1, c2],
SignedTransaction (B), TransactionResult (B),
StateChanges [y],
StateChanges [d1],
BlockFooter
```

Amendment 2 uses the same positions it would have used if amendment 1 did not exist. The state
replay order is `c0, x, z, c1, c2, y, d1`.

#### State replay and verification

To verify state, a replayer starts from verified state whose root hash equals block N's
`BlockFooter.start_of_block_state_root_hash`. It applies each `StateChange` of the amended view
individually and in order: `map_update` as put, `map_delete` as remove, `queue_push` as push,
`queue_pop` as pop, `singleton_update` as set. It MUST NOT reorder, batch, or de-duplicate changes.
The resulting root hash MUST equal block N+1's `BlockFooter.start_of_block_state_root_hash`.

- If it matches, block N's state changes are verified.
- If it does not, the amendments for block N remain **pending**. A correction may span several
  transactions. A later amendment, or a voiding restatement, can complete it.

Because state amendments verify against a hash that block N+1's proof already signs, they need no
further endorsement to be trusted for state purposes.

### Block stream changes

#### `BlockItem`

```protobuf
message BlockItem {
    oneof item {
        // ...
        /**
         * An amendment to this block. Appears only after the block footer and
         * before the block proof(s). Never included in any block hash.
         */
        AmendmentItem amendment = 21;
    }
}

message AmendmentItem {
    oneof source {
        /** An amendment that reached consensus in a later block, with proof. */
        ProvenAmendment proven = 1;

        /**
         * A state-only amendment of this block applied locally by a Block
         * Node operator, verifiable only by state replay against the next
         * block's start_of_block_state_root_hash.
         */
        com.hedera.hapi.node.network.BlockAmendment local_state_amendment = 2;
    }
}

message ProvenAmendment {
    /** The block (M) that contains the BlockAmendment transaction. */
    uint64 source_block_number = 1;

    /**
     * A proof, from block M, covering the block item holding the
     * BlockAmendment signed transaction and the block item holding its
     * transaction result, whose status MUST be SUCCESS. The transaction MUST
     * amend the block containing this item; consumers apply only that
     * amendment and ignore the transaction's amendments of other blocks.
     */
    StateProof proof = 2;
}
```

#### Amended block layout

```text
BlockHeader                     ┐
... original block items ...    │  original block: never changes
BlockFooter                     ┘
AmendmentItem(s)                   in consensus order of their source transactions
BlockProof(s)                      unchanged; proves header→footer only
```

### Block Node behavior

This section is informative. Detailed Block Node behavior will be specified separately.

- On observing a successful `BlockAmendment` in block M, a Block Node builds a `ProvenAmendment`
  from block M and attaches it to every block the transaction amends.
- A Block Node that maintains state and detects a state root mismatch keeps verifying, storing,
  and streaming blocks, stops its state service, and reports state as unavailable. Peers cannot
  help, because they hold the same block.
- When the amendments for block N verify, the Block Node replays block N's amended view and
  resumes state processing.

### Impact on Mirror Node

- Ingest the new `BlockAmendment` and `StateErrata` transaction types.
- **Amendments**: apply the amended view of each amended block to Mirror Node records (for
  example, a missing transaction result or transfer). Amendments arrive in block M, so Mirror Nodes
  need not read from a Block Node.
- **Errata**: the corrective state changes appear in block M and are processed like any other
  state changes. Mirror Nodes should surface errata transactions for auditability.

### Impact on SDK

Add `BlockAmendmentTransaction` and `StateErrataTransaction`. They are intended for governance
tooling only.

## Backwards Compatibility

- Original blocks, block hashes, and block proofs are unchanged.
- Consumers that do not understand `AmendmentItem` can skip items between `BlockFooter` and
  `BlockProof`. Consumers that assume the item after the footer is a proof must be updated.
- Consumers that do not understand the new transaction types will see unknown transactions in
  block M. Errata state changes are ordinary state changes and are processed correctly.

## Network Optionality

Both transactions are always available but usable only by the treasury and system admin accounts.
Networks can further restrict each one independently through API permissions. Networks that never
submit corrections see no change in behavior. Block Nodes that do not maintain state still store
and serve amendments.

## Security Implications

- **`StateErrata` is the most powerful transaction in the system.** It can rewrite any state,
  including balances and consensus-critical state such as rosters, platform state, and TSS
  material. Its safety rests entirely on control of the treasury and system admin keys and the
  governance process around their use. Every errata is public, signed, and visible as state
  changes in block M.
- **Type safety.** An errata key or value of the wrong type would only fail when state is
  serialized, after the transaction completed, on every node at once. The consensus node therefore
  validates every change against the registered schema definition of its state before applying any.
- **Amendments cannot change state or any block hash.** A malicious amendment could misrepresent
  history to consumers. It would be attributable to a privileged key, provable against block M, and
  correctable by a voiding restatement. State amendments additionally self-verify against an already signed state root.
- **Resource use.** Corrections are fee-exempt and throttle-exempt, but only privileged payers can
  submit them, and ingest rejects all non-system payers.
- **Locally applied amendments** (`local_state_amendment`) are only as trustworthy as state replay
  verification. A Block Node MUST NOT serve one that has not verified.

## How to Teach This

Start from one question: *did the consensus node apply state correctly?*

- **Yes**: the record is wrong. Submit a **`BlockAmendment`** describing the edits to each original
  block; one transaction can amend several blocks. Positions always refer to the original block.
  If an earlier amendment of a block was wrong, submit a new amendment of that block with
  `void_previous_amendments` set that restates every intended edit to it.
- **No**: state is wrong. Submit a **`StateErrata`** with the corrective state changes. History
  between the mistake and the fix stays as it was.

Include the worked example above in operator runbooks, together with the rule that state changes
must be placed at exact positions because state hashes depend on mutation order.

## Reference Implementation

The consensus node implementation is in
[hiero-consensus-node](https://github.com/hiero-ledger/hiero-consensus-node):

- **HAPI**: `services/block_amendment.proto`, `services/state_errata.proto`, and the
  `AmendmentItem` block item.
- **Handlers**: `BlockAmendmentHandler` and `StateErrataHandler` in
  `com.hedera.node.app.corrections`. State definitions for errata validation come from the
  registered service schemas.
- **Reference replayer**: `BlockAmendmentReplayer` in the test clients implements the composition
  and replay algorithm above.
- **Tests**:
  - Unit tests of both handlers and of the replayer, including the worked example.
  - A real-`VirtualMap` test showing that the same changes in a different order hash differently,
    that an amendment at the original position reproduces the original root hash, and that every
    other position does not.
  - HAPI tests covering authorization, validation, batching, and errata atomicity. Two of them
    extract real amendments from block M, as a Block Node would. One shows that a voiding
    correction restores block N's exact original state change sequence. The other shows that one
    transaction restores dropped state changes in two different blocks. The end-of-run stream
    validation replays every block, including errata state changes, and verifies the signed state
    root hashes.

## Rejected Ideas

- **A single transaction type with an amendment/errata `oneof`.** Rejected in favor of two types:
  their governance weight differs greatly, separate types allow separate API permissions, only the
  errata handler needs to write arbitrary state, and consumers can tell them apart by type.
- **Sequential (shifting) coordinates.** Each amendment would address the block as amended by all
  earlier amendments. Rejected because positions become order-dependent and fragile: voiding or
  failing one amendment invalidates the positions of every later one.
- **Latest amendment always supersedes all earlier ones.** Rejected because large fixes exceed one
  transaction. Voiding is opt-in instead, so a restatement can be split across transactions.
- **Several amendments of the same block in one transaction.** Rejected because it would need an
  order within the transaction, and voiding rules between its entries. The same edits fit in one
  amendment of that block.
- **Revoking specific earlier amendments by transaction id.** Rejected because correcting a bad
  amendment would require understanding exactly what every earlier amendment changed, and which to
  keep. Voiding and restating against the original block is self-contained.
- **Content-hash anchors** ("insert after the item with hash H"). Rejected because identical items
  can repeat within a block, and anchors become ambiguous.
- **Addressing by transactional unit.** Rejected because every replayer would need identical rules
  for unit boundaries, and any disagreement changes the state hash. "After unit k" is expressed as
  "after the index of unit k's last item".
- **Typed `StateChange` / `BlockItem` fields in the transaction body.** Rejected because of the
  protobuf import cycle and the need for byte-exact items.
- **State amendments payable by any account below `0.0.1000` without endorsement.** Rejected in
  favor of one privileged payer rule. State amendments still self-verify.
- **Rewriting the original block.** Rejected because it breaks the hash chain and every existing
  proof.
- **Amendment items intermixed with, or after, block proofs.** Rejected because proofs should always
  be the last items of a block. Stored blocks are compressed, so appending saves little.
- **Block Node auto-recovery by reconnecting to a consensus node.** Deferred. It is expensive and
  needs Block Node to consensus node requests, which do not exist today and raise
  denial-of-service concerns.
- **Consensus node computing the fix itself.** The consensus node cannot know what its mistake was.
- **Consensus node self-validation before closing a block.** The bug it would catch could equally
  exist in the validation code, so it adds latency without real protection.
- **Updating HIP-639.** It is incomplete and was never implemented.

## Open Issues

1. Should errata be denied for consensus-critical states (platform state, rosters, TSS/hinTS/history,
   `BlockStreamInfo`, running hashes)? The initial implementation allows all states.
2. In-memory caches backed by state (throttles, fees, exchange rates, nodes, staking) do not refresh
   after an errata. Should they refresh, or is a restart required?
3. Should errata keep the record finalization semantics described under "Consensus node behavior"
   (zero-sum HBAR, transfer lists, staking reward settlement, no account removal)? The alternative
   is to skip finalization for errata, which would allow minting and burning HBAR and account
   removal, but would leave balance corrections out of record transfer lists. Entity counts are
   not adjusted in either case.
4. Exact proof format for `ProvenAmendment`: one multi-leaf proof or one proof per leaf.
5. Confirm that individual, in-order state change replay reproduces the consensus node's state hash
   in every case, including keys that appear more than once in one `StateChanges` item. (The
   existing test-client validator replays changes grouped by service.)
6. Should the consensus node track amendments in state, to detect duplicate or conflicting
   submissions?
7. Per-block limits for large correction backlogs, given that corrections are throttle-exempt.
   The number of blocks one `BlockAmendment` may amend is currently bounded only by the
   transaction size limit.
8. Propagation of locally applied state amendments beyond Tier 1 Block Nodes, and authentication of
   the Block Node admin API.
9. Where Block Node state-service status is reported.
10. Handling of a block that fails verification because the consensus node hashed an item it never
    sent.
11. Pre-consensus event stream retention window, and the runbook for collecting it when a
    transaction never made it into a block.
12. Whether any errata of non-state block items exists, or all such corrections are amendments.
13. Lower bound on `block_number` (for example, the first block-stream block after the
    wrapped-record cutover), and how this relates to HIP-1427 amendments.
14. Applicability to other Hiero deployments, especially those where state changes never leave
    Tier 1 Block Nodes.

## References

- [HIP-1056: Block Streams](https://hips.hedera.com/hip/hip-1056)
- [HIP-1427: Wrapped Record Blocks](https://hips.hedera.com/hip/hip-1427)
- [HIP-639](https://hips.hedera.com/hip/hip-639)
- `VirtualMap` add/remove path semantics:
  `platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java`
- `BlockFooter.start_of_block_state_root_hash`:
  `hapi/hedera-protobuf-java-api/src/main/proto/block/stream/output/block_footer.proto`

## Copyright/license

This document is licensed under the Apache License, Version 2.0 —
see [LICENSE](../LICENSE) or <https://www.apache.org/licenses/LICENSE-2.0>.
