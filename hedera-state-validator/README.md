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
   java -jar ./validator-<version>.jar {path-to-state-round} validate {tag} [{tag}...]
   ```

   Here, the `state path` (required) is the location of the state files, and `tag` refers to the validation that should be run. Multiple tags can be specified, separated by spaces, but at least one tag is required.

### Validation tags

- [`files`](src/main/java/com/hedera/statevalidation/validators/merkledb/FileLayout.java) - Validates all expected files are present in the state directory.
- [`internal`](/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateInternalIndex.java) - Validates the consistency of the indices of internal nodes.
- [`leaf`](/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateLeafIndex.java) - Validates the consistency of the indices of leaf nodes.
- [`hdhm`](/src/main/java/com/hedera/statevalidation/validators/merkledb/ValidateLeafIndexHalfDiskHashMap.java) - Validates the consistency of the indices of leaf nodes in the half-disk hashmap.
- [`rehash`](/src/main/java/com/hedera/statevalidation/validators/state/Rehash.java) - Runs a full rehash of the state.
- [`account`](/src/main/java/com/hedera/statevalidation/validators/servicesstate/AccountValidator.java) - Ensures all accounts have a positive balance, calculates the total HBAR supply,
  and verifies it totals exactly 50 billion HBAR.
- [`tokenRelations`](/src/main/java/com/hedera/statevalidation/validators/servicesstate/TokenRelationsIntegrity.java) - Verifies that the accounts and tokens for every token relationship exist.

## Introspect

[IntrospectCommand](src/main/java/com/hedera/statevalidation/IntrospectCommand.java) allows you to inspect the state of a Hedera node, providing insights into the structure and contents of the state files.

### Usage

1. Download the state files.
2. Run the following command to execute the introspection:

   ```shell
   java -jar ./validator-<version>.jar {path-to-state-round} introspect {service_name} {state_key} [{key_info}]
   ```

   Here, the `serviceName` is the required name of the service to introspect, and `stateName` is the required name of the state to introspect.
   Optionally, you can specify `keyInfo` to get information about the values in the virtual map of the service state in a format `keyType:keyJson`:
   `keyType` represents service key type (`TopicID`, `AccountID`, etc.) and `keyJson` represents key value as json.
   If `keyInfo` is not provided, it introspects singleton value of the service state.

## Analyze

[AnalyzeCommand](src/main/java/com/hedera/statevalidation/AnalyzeCommand.java) allows you to analyze the state and generate detailed metrics about storage efficiency, including duplicate percentage, item counts, file counts, wasted space in bytes, and total space usage. These metrics are displayed in the console and also saved to a `state-analysis.log` file.

### Usage

1. Download the state files.
2. Run the following command to execute the introspection:

   ```shell
   java -jar ./validator-<version>.jar {path-to-state-round} analyze [--path-to-kv] [--key-to-path] [--path-to-hash]
   ```

### Analysis Options

- `--path-to-kv` (or `-p2kv`) - Analyze path-to-key-value storage.
- `--key-to-path` (or `-k2p`) - Analyze key to path storage.
- `--path-to-hash` (or `-p2h`) - Analyze path-to-hash storage.

If no options are specified, both storage types are analyzed by default.

### Analysis Metrics

The analysis generates comprehensive storage reports that include:

- **Item Count**: Total number of stored items
- **File Count**: Number of storage files
- **Storage Size**: Total disk space usage in MB
- **Waste Percentage**: Percentage of space consumed by duplicate or invalid entries
- **Duplicate Items**: Number of items that appear multiple times
- **Path Range**: Minimum and maximum path values in the storage

The results are displayed in the console and saved to a `state-analysis.log` file.

### Sample Output

```terminaloutput
Report for node: 0

Path-to-Hash Storage:
  Key Range: 0 to 1484
  Size: 0 MB
  Files: 1
  Items: 0
  Waste: 0.00%

Key-to-Path Storage:
  Key Range: 0 to 33554431
  Size: 0 MB
  Files: 1
  Items: 743
  Waste: 0.00%

Path-to-KeyValue Storage:
  Key Range: 742 to 1484
  Size: 0 MB
  Files: 1
  Items: 743
  Waste: 0.00%
```

## Export

[ExportCommand](src/main/java/com/hedera/statevalidation/ExportCommand.java) allows you to export the state of a Hedera node into JSON file(s).

### Usage

1. Download the state files.
2. Run the following command to execute the export:

   ```shell
   java -jar [-DmaxObjPerFile=X] [-DprettyPrint=true] ./validator-<version>.jar {path-to-state-round} export {path-to-result-dir} [{service_name}] [{state_key}]
   ```

- `-DmaxObjPerFile` option allows customizing the upper limit of objects per file.
- `-DprettyPrint=true` enables human-readable result files.

Example entry:

```json
{"p":970084,"k":"{
  "accountId": {
    "accountNum": "18147"
  },
  "tokenId": {
    "tokenNum": "202004"
  }
}", "v":{
  "tokenId": {
    "tokenNum": "202004"
  },
  "accountId": {
    "accountNum": "18147"
  },
  "kycGranted": true,
  "automaticAssociation": true,
  "previousToken": {
    "tokenNum": "201052"
  }
}}
```

where `p` is a path in the virtual map, `k` is a key, and `v` is a value.

Examples:

Export all states to the current directory (assuming the JAR file is located in the round directory):

```shell
java -jar ./validator-0.65.0.jar . export .
```

Export all states to the current directory, limiting the number of objects per file to 100,000:

```shell
java -jar -DmaxObjPerFile=100000 ./validator-0.65.0.jar /path/to/round export .
```

Export all accounts to `/tmp/accounts`, limiting the number of objects per file to 100,000:

```shell
java -jar -DmaxObjPerFile=100000 ./validator-0.65.0.jar /path/to/round export /path/to/result AccountService ACCOUNTS
```

Notes:
- If the service name and state key are omitted, it will export all the states.
- Service name and state key should both be either omitted or specified.
- If service name/state key is specified, the resulting file is `{service_name}_{state_key}_X.json`, where `X` is an ordinal number in the series of such files.
- If service name/state key is not specified, the resulting file is `exportedState_X.json`, where `X` is an ordinal number in the series of such files.
- The exporter limits the number of objects per file to 1 million; to customize the limit, use VM parameter `-DmaxObjPerFile`.
- Keep in mind that the object count per file—though consistent across multiple runs—is likely to be uneven.
- Order of entries is consistent across runs and ordered by path.

## Sorted Export

[SortedExportCommand](src/main/java/com/hedera/statevalidation/SortedExportCommand.java) allows you to export the state of a Hedera node into JSON file(s) in a sorted way, which may be helpful during differential testing.

### Usage

1. Download the state files.
2. Run the following command to execute the sorted export:

   ```shell
   java -jar [-DmaxObjPerFile=X] [-DprettyPrint=true] ./validator-<version>.jar {path-to-state-round} sorted-export {path-to-result-dir} [{service_name}] [{state_key}]
   ```

- `-DmaxObjPerFile` option allows customizing the upper limit of objects per file.
- `-DprettyPrint=true` enables human-readable result files.

Example entry:

```json
{"k":"{
  "accountNum": "1"
}", "v":{
  "accountId": {
    "accountNum": "1"
  },
  "key": {
    "ed25519": "CqjiEGTGHquG4qnBZFZbTnqaQUYQbgps0DqMOVoRDpI="
  },
  "expirationSecond": "1762348512",
  "stakePeriodStart": "-1",
  "stakeAtStartOfLastRewardedPeriod": "-1",
  "autoRenewSeconds": "8000001"
}}
```

where `k` is a key, and `v` is a value.

Examples and Notes (same as export command, with these differences):
- Paths are not included in sorted export files.
- The data is sorted by the **byte representation of the key**, which doesn't always map to natural ordering. For example, varint encoding does not preserve numerical ordering under lexicographical byte comparison, particularly when values cross boundaries that affect the number of bytes or the leading byte values. However, it will produce a stable ordering across different versions of the state, which is critically important for differential testing.

## Compact

[CompactionCommand](src/main/java/com/hedera/statevalidation/CompactionCommand.java) allows you to perform compaction of state files.

### Usage

1. Download the state files.
2. Run the following command to execute the compaction:

   ```shell
   java -jar ./validator-<version>.jar {path-to-state-round} compact
   ```

## Updating State with a Block Stream

The `apply-blocks` command uses a set of block files to advance a given state from the current state to the target state.

### Usage:

```bash

java -jar ./validator-0.65.0.jar "<path to original state>" apply-blocks "<path to a directory with block stream files>" \
 -i=<self-id> [-o="<path to output directory>"] [-h="<hash of the target state>"] [-t="<target round>"]
```

Notes:

- The command checks if the block stream contains the next round relative to the initial round to ensure continuity. It fails if the next round is not found.
- If a target round is specified, the command will not apply rounds beyond it, even if additional block files exist.
  The command also verifies that the corresponding blocks are present. It will fail if a block is missing or if the final round in the stream does not match the target round.
- The command can validate the hash of the resulting state against a provided hash (see the `-h `parameter).
- If the `-o` parameter is specified, the command uses the provided path as the output directory for the resulting snapshot. If not specified, the default output directory is `./out`.
