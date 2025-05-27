# Hedera State Validator

The **Hedera State Validator** is a tool used to validate the persisted state of a Hedera node.
Its primary function is to ensure that backups are not corrupted and can be used to restore
the node's state. Additionally, it can be utilized for development purposes, such as verifying
that the node's state remains intact after refactoring or debugging to investigate the root cause
of a corrupted state.

## Validations

- [`stateAnalyzer`](validator/src/main/java/com/hedera/statevalidation/validators/merkledb/StateAnalyzer.java) - Analyzes the state and calculates metrics such as the percentage of duplicates,
  item count, file count, wasted space in bytes, and total space. These metrics are published in a `report.json` file.
- [`internal`](validator/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateInternalIndex.java) - Validates the consistency of the indices of internal nodes.
- [`leaf`](validator/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateLeafIndex.java) - Validates the consistency of the indices of leaf nodes.
- [`hdhm`](validator/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateLeafIndexHalfDiskHashMap.java) - Validates the consistency of the indices of leaf nodes in the half-disk hashmap.
- [`rehash`](validator/src/main/java/com/hedera/statevalidation/validators/state/Rehash.java) - Runs a full rehash of the state.
- [`account`](validator/src/main/java/com/hedera/statevalidation/validators/servicesstate/AccountValidator.java) - Ensures all accounts have a positive balance, calculates the total HBAR supply,
  and verifies it totals exactly 50 billion HBAR.
- [`tokenRelations`](validator/src/main/java/com/hedera/statevalidation/validators/servicesstate/TokenRelationsIntegrity.java) - Verifies that the accounts and tokens for every token relationship exist.
- [`compaction`](validator/src/main/java/com/hedera/statevalidation/validators/merkledb/Compaction.java) - Not a validation per se, but it allows for the compaction of state files.
