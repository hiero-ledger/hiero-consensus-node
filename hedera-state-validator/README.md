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
   java -jar ./validator-<version>.jar {path-to-state-round} introspect {serviceName} {stateName} [{keyInfo}]
   ```

   Here, the `serviceName` is the required name of the service to introspect, and `stateName` is the required name of the state to introspect.
   Optionally, you can specify `keyInfo` to get information about the values in the virtual map of the service state in a format `keyType:keyJson`:
   `keyType` represents service key type (`TopicID`, `AccountID`, etc.) and `keyJson` represents key value as json.
   If `keyInfo` is not provided, it introspects singleton value of the service state.

## Export

[ExportCommand](src/main/java/com/hedera/statevalidation/ExportCommand.java) allows you to export the state of a Hedera node into a JSON file(s).

### Usage

1. Download the state files
2. Run the following command to execute the export:

   ```shell
   java -jar [-DmaxObjPerFile=X] [-Dsorted=true] [-DprettyPrint=true] ./validator-<version>.jar {path-to-state-round} export {path-to-result-dir} [{service_name}] [{state_key}]
   ```

`-DmaxObjPerFile` option allows customizing the upper limit of objects per file
`-Dsorted=true` enables a special mode which exports the data in a sorted way, may be helpful during differential testing
`-DprettyPrint=true` enables human-readable result files

Example entry:

For an unsorted file

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

For a sorted file:

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

Examples:

Export all states to the current directory, jar file is located in the round directory:

```shell
java -jar ./validator-0.65.0.jar . export .
```

Export all states to the current directory, limits the number of objects per file to 100,000:

```shell
java -jar -DmaxObjPerFile=100000 ./validator-0.65.0.jar /path/to/round export .
```

Export all accounts to `/tmp/accounts`, limits the number of objects per file to 100,000:

```shell
java -jar -DmaxObjPerFile=100000 ./validator-0.65.0.jar /path/to/round export /path/to/result TokenService ACCOUNTS
```

Notes:
- service name and state name should be both either omitted or specified
- if service name / state name is specified the resulting file is `{service_name}_{state_key}_X.json` where `X` is an ordinal number in the series of such files
- if service name / state name is not specified the resulting file is `exportedState_X.json`, where `X` is an ordinal number in the series of such files
- if you export all the states, the exporter limits the number of objects per file to 1 million, to customize the limit use VM parameter `-DmaxObjPerFile`
- if you export a single state keep in mind that the object count per file though consistent across multiple runs is likely to be uneven, some files may be even empty
- order of entries is consistent across runs and ordered by path, unless `-Dsorted=true` is specified
- in case of `-Dsorted=true` the data is sorted by the **byte representation of the key** which doesn't always map to the natural ordering. For example, varint encoding does not preserve numerical ordering under
lexicographical byte comparison, particularly when values cross boundaries that affect the number of bytes or the leading byte values. However, it will produce a stable ordering across different versions of the state,
and that is critically important for the differential testing
