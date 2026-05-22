# Block-Stream Translators

The block stream is the modern source-of-truth output of a Hedera node — a sequence of
self-describing transactional units that carry the transaction body, its result, the state
changes it produced, and any trace data. The legacy record stream is a separate output format the
network still produces for backwards compatibility.

To assert that a block stream is consistent with the record stream the same transactions would
have produced, the test framework *translates* block items back into legacy record stream entries
and diffs the two. The translation lives under
`src/main/java/com/hedera/services/bdd/junit/support/translators/`.

This document explains the architecture so the translation layer can be extended when a new
transaction type is added.

## High-level flow

```
Block stream items
       │
       ▼
RoleFreeBlockUnitSplit  ───►  groups items into BlockTransactionalUnit(s)
       │
       ▼
BlockTransactionalUnitTranslator
       │   for each BlockTransactionParts in the unit, dispatches to:
       ▼
BlockTransactionPartsTranslator  (per-functionality implementation)
       │   collaborates with BaseTranslator + remainingStateChanges
       ▼
SingleTransactionRecord  (legacy-shape record + sidecars)
       │
       ▼
TransactionRecordParityValidator  ──►  RcDiff vs. recorded stream
```

## Key types

### `BlockTransactionalUnit` (in `translators/inputs/`)

A logical group of items that together represent one transactional unit — the main transaction
plus any preceding/following child records, its result, state changes, trace data, and metadata.

### `BlockTransactionParts` (in `translators/inputs/`)

The pieces of a single transaction within a unit: the transaction body, its
`TransactionOutput`, `TransactionResult`, and `TransactionParts` (a parsed view of the signed
transaction).

### `RoleFreeBlockUnitSplit`

Splits a flat list of `BlockItem`s into a list of `BlockTransactionalUnit`s without caring about
node roles. The split logic understands batch transactions, schedule executions, and the
"singleton state" items (e.g., `STATE_ID_TRANSACTION_RECEIPTS`) that need to live with the unit
that produced them.

### `BlockTransactionalUnitTranslator`

Iterates the transactions inside a unit. For each `BlockTransactionParts` it determines the
`HederaFunctionality` and dispatches to the registered `BlockTransactionPartsTranslator` for that
functionality. State changes are passed in as a *mutable* list because translators consume them
incrementally (see the contract below).

### `BlockTransactionPartsTranslator` (the interface every per-op translator implements)

```java
@FunctionalInterface
public interface BlockTransactionPartsTranslator {
    SingleTransactionRecord translate(
        @NonNull BlockTransactionParts parts,
        @NonNull BaseTranslator baseTranslator,
        @NonNull List<StateChange> remainingStateChanges,
        @Nullable List<TraceData> tracesSoFar,
        @NonNull List<ScopedTraceData> followingUnitTraces,
        @Nullable HookId executingHookId,
        @Nullable HookMetadata hookMetadata);
}
```

The contract for handling `remainingStateChanges` is strict:

> If the translator needs a state change with a particular `StateChange#stateId()` to complete its
> translation it **must**:
> 1. Use the *first* occurrence of that state change from the list.
> 2. Remove that state change from the list before returning.

This lets a single state-changes list flow through all translators in a unit without one
translator stealing data another translator needs.

### `BaseTranslator`

A shared helper that holds the cumulative knowledge needed to translate any transaction:
exchange-rate sets, entity-id allocations, contract slot usage, bloom filters, log conversion
utilities, hook resolution, scheduled-transaction id derivation, and so on. Per-op translators
ask `baseTranslator` for whatever they don't carry themselves.

## Per-functionality translators (`translators/impl/`)

There is one class per `HederaFunctionality` that requires explicit translation. As of writing
the set is:

```
AirdropRemovalTranslator        AtomicBatchTranslator           ContractCallTranslator
ContractCreateTranslator        ContractDeleteTranslator        ContractUpdateTranslator
CryptoCreateTranslator          CryptoTransferTranslator        CryptoUpdateTranslator
EthereumTransactionTranslator   FileCreateTranslator            FileUpdateTranslator
NoExplicitSideEffectsTranslator NodeCreateTranslator            RegisteredNodeCreateTranslator
ScheduleCreateTranslator        ScheduleDeleteTranslator        ScheduleSignTranslator
SubmitMessageTranslator         TokenAirdropTranslator          TokenAssociateTranslator
TokenBurnTranslator             TokenCreateTranslator           TokenDissociateTranslator
TokenMintTranslator             TokenUpdateTranslator           TokenWipeTranslator
TopicCreateTranslator           UtilPrngTranslator
```

`NoExplicitSideEffectsTranslator` is the catch-all for functionalities that don't change state
in a way the legacy record stream represented (queries, freeze, etc.).

## Adding a translator for a new transaction type

1. Add the functionality to the dispatch map in `BlockTransactionalUnitTranslator`.
2. Implement a `BlockTransactionPartsTranslator` under `translators/impl/`.
3. Use `BaseTranslator` for cross-cutting concerns (entity ids, exchange rates, hook metadata).
4. When the translator pulls a `StateChange` out of `remainingStateChanges`, **remove** it so the
   next translator in the unit doesn't double-count.
5. Add a parity test (typically alongside the closest existing test under `suites/records/` or
   the per-functionality suite) and let `TransactionRecordParityValidator` exercise it.

## Where translation is consumed

`TransactionRecordParityValidator` (see [`VALIDATORS.md`](VALIDATORS.md)) calls
`BlockTransactionalUnitTranslator.translate(...)` on the block stream produced by a subprocess
network, then uses `RcDiff` to compare the resulting `SingleTransactionRecord`s against the
recorded stream the same nodes wrote.

## See also

- [VALIDATORS.md](VALIDATORS.md) — `TransactionRecordParityValidator` is the main consumer.
- [RCDIFF.md](RCDIFF.md) — the diff engine the parity validator uses.
- `translators/inputs/` — input record-types (`BlockTransactionalUnit`, `BlockTransactionParts`,
  `HookMetadata`, `TransactionParts`).
