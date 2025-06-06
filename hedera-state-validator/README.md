# Hedera State Validator

The **Hedera State Validator** is a tool used to _validate_ or _introspect_ the persisted state of a Hedera node.

## Validate

[ValidateCommand](src/main/java/com/hedera/statevalidation/ValidateCommand.java) primary function is to ensure that states are not corrupted and make sure that Hedera nodes can start from existing state snapshots.
Additionally, it can be utilized for development purposes, such as verifying
that the node's state remains intact after refactoring or debugging to investigate the root cause
of a corrupted state.

### Usage

1. Download the state files.
2. Run the following command to execute the validation:

   ```shell
   java -jar ./validator-<version>.jar validate {state path} {tag} [{tag}...]
   ```

   Here, the `state path` (required) is the location of the state files, and `tag` refers to the validation that should be run. Multiple tags can be specified, separated by spaces, but at least one tag is required.

### Validation tags

- [`files`](src/main/java/com/hedera/statevalidation/validators/merkledb/FileLayout.java) - Validates all expected files are present in the state directory.
- [`stateAnalyzer`](/src/main/java/com/hedera/statevalidation/validators/merkledb/StateAnalyzer.java) - Analyzes the state and calculates metrics such as the percentage of duplicates,
  item count, file count, wasted space in bytes, and total space. These metrics are published in a `report.json` file.
- [`internal`](/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateInternalIndex.java) - Validates the consistency of the indices of internal nodes.
- [`leaf`](/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateLeafIndex.java) - Validates the consistency of the indices of leaf nodes.
- [`hdhm`](/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateLeafIndexHalfDiskHashMap.java) - Validates the consistency of the indices of leaf nodes in the half-disk hashmap.
- [`rehash`](/src/main/java/com/hedera/statevalidation/validators/state/Rehash.java) - Runs a full rehash of the state.
- [`account`](/src/main/java/com/hedera/statevalidation/validators/servicesstate/AccountValidator.java) - Ensures all accounts have a positive balance, calculates the total HBAR supply,
  and verifies it totals exactly 50 billion HBAR.
- [`tokenRelations`](/src/main/java/com/hedera/statevalidation/validators/servicesstate/TokenRelationsIntegrity.java) - Verifies that the accounts and tokens for every token relationship exist.
- [`compaction`](/src/main/java/com/hedera/statevalidation/validators/merkledb/Compaction.java) - Not a validation per se, but it allows for the compaction of state files.

## Introspect

[IntrospectCommand](src/main/java/com/hedera/statevalidation/IntrospectCommand.java) allows you to inspect the state of a Hedera node, providing insights into the structure and contents of the state files.

### Usage

1. Download the state files.
2. Run the following command to execute the introspection:

   ```shell
   java -jar ./validator-<version>.jar introspect {serviceName} {stateName} [{keyInfo}]
   ```

   Here, the `serviceName` is the required name of the service to introspect, and `stateName` is the required name of the state to introspect.
   Optionally, you can specify `keyInfo` to get information about the values in the virtual map of the service state in a format `keyType:keyJson`:
   `keyType` represents service key type (`TopicID`, `AccountID`, etc.) and `keyJson` represents key value as json.
   If `keyInfo` is not provided, it introspects singleton value of the service state.
