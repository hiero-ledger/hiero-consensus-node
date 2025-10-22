# Hedera State Validator

The **Hedera State Validator** is a comprehensive tool for working with the persisted state of Hedera nodes, providing capabilities to validate state integrity, introspect state contents, export state data,
compact state files, and apply block streams to advance state.

## Validate

[ValidateCommand](src/main/java/com/hedera/statevalidation/ValidateCommand.java) ensures state integrity and validates that Hedera nodes can start from existing state snapshots.
Can also be used for development purposes, such as verifying that the node's state remains intact after refactoring or debugging to investigate the root cause of a corrupted state.

### Usage

1. Download the state files.
2. Run the following command to execute the validation:

```shell
java -jar ./validator-<version>.jar {path-to-state-round} validate {tag} [{tag}...]
```

### Parameters

- `{path-to-state-round}` - Location of the state files (required).
- `{tag}` - Validation that should be run, multiple tags can be specified, separated by spaces (at least one required). Current supported tags:
  - [`internal`](/src/main/java/com/hedera/statevalidation/validator/merkledb/ValidateInternalIndex.java) - Validates the consistency of the indices of internal nodes.
  - [`leaf`](/src/main/java/com/hedera/statevalidation/validator/merkledb/ValidateLeafIndex.java) - Validates the consistency of the indices of leaf nodes.
  - [`hdhm`](/src/main/java/com/hedera/statevalidation/validator/merkledb/ValidateLeafIndexHalfDiskHashMap.java) - Validates the consistency of the indices of leaf nodes in the half-disk hashmap.
  - [`rehash`](/src/main/java/com/hedera/statevalidation/validator/state/Rehash.java) - Runs a full rehash of the state.
  - [`account`](/src/main/java/com/hedera/statevalidation/validator/service/AccountValidator.java) - Ensures all accounts have a positive balance, calculates the total HBAR supply,
    and verifies it totals exactly 50 billion HBAR.
  - [`tokenRelations`](/src/main/java/com/hedera/statevalidation/validator/service/TokenRelationsIntegrity.java) - Verifies that the accounts and tokens for every token relationship exist.

## Introspect

[IntrospectCommand](src/main/java/com/hedera/statevalidation/IntrospectCommand.java) inspects node state structure and provides insights into the contents of state files.

### Usage

1. Download the state files.
2. Run the following command to execute the introspection:

```shell
java -jar ./validator-<version>.jar {path-to-state-round} introspect --service-name=<service-name> \
 --state-key=<state-key> \
 [--key-info=<keyType:keyJson>]
```

### Parameters

- `{path-to-state-round}` - Location of the state files (required).

### Options

- `--service-name` (or `-s`) - Name of the service to introspect (required).
- `--state-key` (or `-k`) - Name of the state to introspect (required).
- `--key-info` (or `-i`) - Key, which used to get information about the values in the virtual map of the service state. If this option is not provided, command introspects a singleton value of the service state.
  Should be specified in a format `keyType:keyJson`, where:
  - `keyType` represents the service key type (`TopicID`, `AccountID`, etc.)
  - `keyJson` represents key value as JSON.

## Analyze

[AnalyzeCommand](src/main/java/com/hedera/statevalidation/AnalyzeCommand.java) analyzes state storage and generates detailed metrics about storage efficiency, including duplicate percentage, item counts,
file counts, wasted space in bytes, and total space usage. These metrics are displayed in the console and also saved to a `state-analysis.log` file.

### Usage

1. Download the state files.
2. Run the following command to execute the introspection:

```shell
java -jar ./validator-<version>.jar {path-to-state-round} analyze [--path-to-kv] [--key-to-path] [--path-to-hash]
```

### Parameters

- `{path-to-state-round}` - Location of the state files (required).

### Options

- `--path-to-kv` (or `-p2kv`) - Analyze path-to-key-value storage.
- `--key-to-path` (or `-k2p`) - Analyze key to path storage.
- `--path-to-hash` (or `-p2h`) - Analyze path-to-hash storage.

If no options are specified, all storage types are analyzed by default.

### Analysis Metrics

The analysis generates comprehensive storage reports that include:

- **Item Count**: Total number of stored items.
- **File Count**: Number of storage files.
- **Storage Size**: Total disk space usage in MB.
- **Waste Percentage**: Percentage of space consumed by duplicate or invalid entries.
- **Duplicate Items**: Number of items that appear multiple times.
- **Path Range**: Minimum and maximum path values in the storage.

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

[ExportCommand](src/main/java/com/hedera/statevalidation/ExportCommand.java) exports the node state into JSON file(s).

### Usage

1. Download the state files.
2. Run the following command to execute the export:

```shell
java -jar [-DmaxObjPerFile=<number>] [-DprettyPrint=true] ./validator-<version>.jar {path-to-state-round} export \
 --out=<output-directory> \
 [--service-name=<service-name> --state-key=<state-key>]
```

### System Properties

- `-DmaxObjPerFile` - Maximum number of objects per exported file. Default = `1000000`.
- `-DprettyPrint=true` - Enable human-readable formatting in result files. Set to `true` to enable. Default = `false`.

### Parameters

- `{path-to-state-round}` - Location of the state files (required).

### Options

- `--out` (or `-o`) - Directory where the exported JSON files are written (required). Must exist before invocation.
- `--service-name` (or `-s`) - Name of the service to export. If omitted along with `--state-key`, exports all states.
- `--state-key` (or `-k`) - Name of the state to export. If omitted along with `--service-name`, exports all states.

### Output Format

Example entry:

```
{"p":742, "k":"{
    \"accountNum\": \"503\"
}", "v":"{
    \"accountId\": {
      \"accountNum\": \"503\"
    },
    \"key\": {
      \"ed25519\": \"CqjiEGTGHquG4qnBZFZbTnqaQUYQbgps0DqMOVoRDpI=\"
    },
    \"expirationSecond\": \"1769026993\",
    \"stakePeriodStart\": \"-1\",
    \"stakeAtStartOfLastRewardedPeriod\": \"-1\",
    \"autoRenewSeconds\": \"8000001\"
}"}
```

where `p` is a path in the virtual map, `k` is a key, and `v` is a value.

### Examples

Export all states to the current directory (assuming the JAR file is located in the round directory):

```shell
java -jar ./validator-<version>.jar . export --out=.
```

Export all states to the current directory, limiting the number of objects per file to 100,000:

```shell
java -jar -DmaxObjPerFile=100000 ./validator-<version>.jar /path/to/round export --out=.
```

Export all accounts to `/tmp/accounts`, limiting the number of objects per file to 100,000:

```shell
java -jar -DmaxObjPerFile=100000 ./validator-<version>.jar /path/to/round export --out=/path/to/result \
  --service-name=TokenService --state-key=ACCOUNTS
```

### Notes

- If the service name and state key are omitted, it will export all the states.
- Service name and state key should both be either omitted or specified.
- If service name and state key are specified, the resulting file is `{service_name}_{state_key}_X.json`, where `X` is an ordinal number in the series of such files.
- If service name and state key are not specified, the resulting file is `exportedState_X.json`, where `X` is an ordinal number in the series of such files.
- The exporter limits the number of objects per file to 1 million; to customize the limit, use VM parameter `-DmaxObjPerFile`.
- Keep in mind that the object count per file—though consistent across multiple runs—is likely to be uneven.
- The order of entries is consistent across runs and ordered by path.

## Sorted Export

[SortedExportCommand](src/main/java/com/hedera/statevalidation/SortedExportCommand.java) exports the node state into sorted JSON file(s), which may be helpful during differential testing.

### Usage

1. Download the state files.
2. Run the following command to execute the sorted export:

```shell
java -jar [-DmaxObjPerFile=<number>] [-DprettyPrint=true] ./validator-<version>.jar {path-to-state-round} sorted-export \
  --out=<output-directory>
  [--service-name=<service-name> --state-key=<state-key>]
```

### Output Format

Example entry:

```
{"k":"{
    \"accountNum\": \"1\"
}", "v":"{
    \"accountId\": {
        \"accountNum\": \"1\"
    },
    \"key\": {
        \"ed25519\": \"CqjiEGTGHquG4qnBZFZbTnqaQUYQbgps0DqMOVoRDpI=\"
    },
    \"expirationSecond\": \"1769026993\",
    \"stakePeriodStart\": \"-1\",
    \"stakeAtStartOfLastRewardedPeriod\": \"-1\",
    \"autoRenewSeconds\": \"8000001\"
}"}
```

where `k` is a key, and `v` is a value.

### System Properties / Parameters / Options / Examples / Notes

**Same as the export command right above, with these differences:**

- Paths are not included in sorted export files.
- The data is sorted by the **byte representation of the key**, which doesn't always map to natural ordering. For example, varint encoding does not preserve numerical ordering under lexicographical byte comparison,
  particularly when values cross boundaries that affect the number of bytes or the leading byte values. However, it will produce a stable ordering across different versions of the state, which is critically important for differential testing.

## Compact

[CompactionCommand](src/main/java/com/hedera/statevalidation/CompactionCommand.java) performs compaction of state files.

### Usage

1. Download the state files.
2. Run the following command to execute the compaction:

```shell
java -jar ./validator-<version>.jar {path-to-state-round} compact
```

### Parameters

- `{path-to-state-round}` - Location of the state files (required).

## Updating State with a Block Stream

[ApplyBlocksCommand](src/main/java/com/hedera/statevalidation/ApplyBlocksCommand.java) advances a given state from the current state to the target state using a set of block files.

### Usage:

```shell
java -jar ./validator-<version>.jar {path-to-state-round} apply-blocks --block-stream-dir=<path-to-block-stream-files> \
 --node-id=<self-id> \
 [--out=<path to output directory>] [--expected-hash=<hash of the target state>] [--target-round=<target round>]
```

### Parameters

- `{path-to-state-round}` - Location of the state files (required).

### Options

- `--block-stream-dir` (or `-d`) - Location of the block stream files (required).
- `--out` (or `-o`) - The location where the resulting snapshot is written. Must not exist prior to invocation. Default = `./out`.
- `--node-id` (or `-id`) - The ID of the node that is being used to recover the state. This node's keys should be available locally.
- `--target-round` (or `-t`) - The last round that should be applied to the state, any higher rounds are ignored. If a target round is specified, the command will not apply rounds beyond it, even if additional block files exist.
- `--expected-hash` (or `-h`) - Expected hash of the resulting state. If specified, the command can validate the hash of the resulting state against it.

### Notes:

- The command checks if the block stream contains the next round relative to the initial round to ensure continuity. It fails if the next round is not found.
- The command also verifies that the corresponding blocks are present. It will fail if a block is missing or if the final round in the stream does not match the target round.
