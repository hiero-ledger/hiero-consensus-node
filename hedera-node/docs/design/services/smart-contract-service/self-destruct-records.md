# Externalizing EVM self-destruct deletions

[Issue #27845](https://github.com/hiero-ledger/hiero-consensus-node/issues/27845) describes a gap in
legacy record streams: EVM execution can mark a contract account deleted without emitting a
`ContractDelete` transaction. A record consumer can observe its creation and transfers but retain
`deleted=false`.

## Record contract

Each contract actually deleted at the end of an EVM transaction produces one successful synthetic
`ContractDelete` child. Its body contains the canonical numeric `contractID` and numeric beneficiary
`transferAccountID`; its receipt contains the same contract ID. `permanentRemoval` remains false
because the account is marked deleted, not removed from state.

This is an externalization-only child. It does not dispatch the HAPI deletion handler, require an
admin signature, charge a deletion fee, or perform another transfer. HBAR transfers and staking
rewards remain accounted for by their existing records. Creation records precede deletion records.
Multiple deletions are ordered by their unsigned EVM addresses.

The synthetic child is emitted in every stream mode. This keeps child-record capacity and transaction
outcomes independent of stream mode. Block streams continue to contain the authoritative account
state changes. A block-to-record translator uses the numeric ID in the deletion body, including when
the creation translator has already consumed the account's final state change for a contract created
and destroyed in one transaction.

## Rollout and compatibility

The network property `contracts.selfDestructs.externalization.enabled` defaults to `true`. Operators
can disable it through a coordinated network configuration update if downstream consumers need more
time to support the new children. It applies uniformly to RECORDS, BLOCKS, and BOTH. Disabling it
preserves EVM account/alias deletion but emits no synthetic deletion child and consumes no additional
child-record capacity. It also disables the deletion child's committed-beneficiary correction for
legacy staking redirection; that behavior returns to the pre-feature transaction-builder bookkeeping.

Enabling the feature changes both the stream shape and the capacity required by existing transactions.
With `consensus.handle.maxFollowingRecords=50` and no other child records, 25 internal CREATE plus
SELFDESTRUCT pairs fit exactly: 25 creation children and 25 deletion children. A transaction with 26
pairs now fails with `MAX_CHILD_RECORDS_EXCEEDED`, although it could succeed with externalization
disabled. Other child records reduce the available capacity further. Under pre-EIP-6780 semantics,
deleting pre-existing contracts also consumes one new child slot per distinct contract; 51 deletions
alone exceed the same limit. These failures roll back execution rather than silently omit deletions.

Consumers must accept the additional `ContractDelete` children, their ordering, and their zero
fee/transfer payloads before rollout. Keep the gate disabled on a network that has not completed this
coordination. Forward mirror-import compatibility is tracked in
[#27848](https://github.com/hiero-ledger/hiero-consensus-node/issues/27848); consensus stream assertions
and block-to-record translation tests do not replace running the mirror importer.

## Execution and rollback

The finalized EVM self-destruct set determines which accounts are deleted. With EIP-6780 semantics,
a pre-existing contract's successful `SELFDESTRUCT` only sweeps its HBAR and produces no deletion
child. Earlier EVM versions retain their existing deletion semantics.

Successful self-destruct beneficiaries are captured as numeric account IDs in each world updater.
These entries merge into the parent only on a successful frame commit and are discarded on revert.
This is separate from the transaction builder's staking bookkeeping, which can retain entries from
reverted attempts. In particular, a reverted second self-destruct cannot overwrite the beneficiary
reported for an earlier committed one.
The removable deletion child also carries this committed beneficiary into the existing staking
finalizer, overriding any stale parent entry without introducing another reward or balance transfer.

Deletion and child allocation occur inside the transaction commit's resource-exhaustion boundary.
The child counts toward the normal child-record limit. Exhausting that limit rolls back all pending
EVM changes, including earlier deletions. The child is removable, so a subsequent enclosing rollback,
including a failed atomic batch, also removes it.

Finalization also requires a committed beneficiary for every address in the deletion set. An invariant
failure is logged and becomes a handled `CONTRACT_EXECUTION_EXCEPTION`; it rolls back all pending
deletions instead of guessing a beneficiary or escaping as an unhandled exception.

Gas refunds are calculated after finalization using the final result. Commit-time resource exhaustion
reports the full gas limit, so rollback fee replay must retain the full gas charge. This also corrects
the pre-existing mismatch for other commit-time exhaustion, where a refund recorded before the failure
could reduce the actual charge despite the full-gas result. Ordinary EVM reverts that finalize normally
continue to receive their unused-gas refund.

## Historical mirror reconciliation

New records repair future ingestion; they do not retroactively correct a mirror database. Historical
reconciliation requires coordination with mirror-node operators:

1. Select an authoritative consensus-state snapshot and record its consensus timestamp and provenance.
2. Export the numeric IDs of accounts whose `smartContract` and `deleted` fields are both true.
   Use numeric identity rather than an EVM alias, which may have been removed and reused.
3. Compare the export against the mirror's entity state at the same cutoff and apply an idempotent
   correction for mismatches. Preserve newer observations when processing a historical snapshot.
4. Treat the snapshot as evidence of deletion by the cutoff, not evidence of the exact deletion
   transaction, timestamp, or beneficiary. Recover those details only from verified historical
   execution or replay if the migration requires them.
5. Validate the corrected entities and subsequent record ingestion before completing the migration.

Empty runtime bytecode, a bytecode containing `SELFDESTRUCT`, or an HBAR balance of zero is not
sufficient evidence to mark an account deleted. The snapshot export and mirror-side migration are
operational follow-up work; this change performs no historical state migration.
Reconciliation, including deciding whether consensus-side tooling is needed, is tracked in
[#27849](https://github.com/hiero-ledger/hiero-consensus-node/issues/27849).
