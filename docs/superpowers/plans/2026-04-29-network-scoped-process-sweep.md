# Network-scoped subprocess sweep + network-qualified node logs

## Problem

In `ClprHieroToHieroSuite` (a `@MultiNetworkHapiTest` with two isolated networks
`ledgerA` and `ledgerB`, each `size=1`), only one `ServicesMain` subprocess is alive
at a time. `MultiNetworkExtension.startNetworks` starts ledgerA successfully, then
when it starts ledgerB, ledgerA is killed abruptly (no clean shutdown in the log) and
the test eventually fails because both ledgers must be live.

### Root cause

`SubProcessNode.startWithConfigVersion` (line 217) calls
`destroyAnySubProcessNodeWithId(metadata.nodeId())` before launching its own
subprocess. The implementation in `ProcessUtils.destroyAnySubProcessNodeWithId`
walks `ProcessHandle.allProcesses()` and `destroyForcibly()`s any java process whose
trailing command-line argument equals the node id. The subprocess command line built
in `ProcessUtils.javaCommandLineFor` ends with `... ServicesMain -local 0`, so the
last arg is just the bare nodeId.

When ledgerB (nodeId=0) starts, the sweep matches ledgerA's still-running
`ServicesMain -local 0` and kills it. The sweep was designed for the original
single-shared-network model where matching on nodeId alone was sufficient — it did
not anticipate two concurrent isolated networks each owning a node 0.

### Secondary problem

The per-node `log4j2.xml` `PatternLayout` injected by
`WorkingDirUtils.updateLog4j2XmlOutputDir` includes only `<n0>` — there is no way to
tell from the combined logs which ledger a given `<n0>` line belongs to.

## Approach

Make the subprocess sweep network-scoped, and use the same network name to
disambiguate the log pattern. One change kills both birds.

1. Carry the network name on `NodeMetadata` so it is available wherever node
   metadata flows.
2. Pass the network name through `recreateWorkingDir` into
   `updateLog4j2XmlOutputDir` and emit `<ledgerA-n0>` instead of `<n0>` in the
   per-node `PatternLayout`.
3. Add a JVM system property `-Dhedera.test.networkName=<name>` to the subprocess
   command line in `ProcessUtils.javaCommandLineFor`, sourced from `NodeMetadata`.
4. Make `destroyAnySubProcessNodeWithId` network-scoped: rename to
   `destroyAnySubProcessNodeFor(networkName, nodeId)` and require both the
   `-Dhedera.test.networkName=<name>` arg AND a trailing nodeId arg to match before
   killing.

The safety-net behavior is preserved (a stale `ledgerA-node0` from a prior run is
still reaped when ledgerA starts again) but precisely scoped so isolated networks
no longer interfere with each other.

## Implementation steps

### Step 1: Add `networkName` to `NodeMetadata`

File: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/NodeMetadata.java`

- Add `String networkName` as the first field of the `NodeMetadata` record (or
  immediately after `nodeId` — pick whichever fits the `requireNonNull` ordering
  cleanest).
- Update both `withNewPorts` and `withNewAccountId` to thread the new field
  through.

### Step 2: Populate `networkName` from `classicMetadataFor`

File: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/utils/NetworkUtils.java`

Both overloads of `classicMetadataFor` already take `@NonNull String networkName`
but currently only validate it and discard it. Pass `networkName` into the
`NodeMetadata` constructor.

### Step 3: Plumb network name into log4j updater

File: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/utils/WorkingDirUtils.java`

- Change `updateLog4j2XmlOutputDir(Path workingDir, long nodeId)` to also accept
  `String networkName`. Update the `.replace(LOG4J2_DATE_FORMAT, ...)` to emit
  `<networkName-n<nodeId>>` (e.g. `<ledgerA-n0>`).
- `recreateWorkingDir` already has the network *config* (not name) as a parameter.
  Add a `networkName` parameter to it as well, and pass it down to
  `updateLog4j2XmlOutputDir`.

### Step 4: Pass network name from caller

File: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/AbstractLocalNode.java`

In `initWorkingDir`, pass `metadata.networkName()` into `recreateWorkingDir`.

### Step 5: Add `-Dhedera.test.networkName=<name>` system property

File: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/ProcessUtils.java`

In `javaCommandLineFor(NodeMetadata metadata)`, append
`"-Dhedera.test.networkName=" + metadata.networkName()` to the JVM args list (alongside
the other `-D` flags). Order does not matter; any position before `--module` works.

### Step 6: Make sweep network-scoped

Same file (`ProcessUtils.java`).

- Rename `destroyAnySubProcessNodeWithId(long nodeId)` to
  `destroyAnySubProcessNodeFor(String networkName, long nodeId)`.
- Filter requires:
  - `command().contains("java")`
  - `arguments()` contains the literal `"-Dhedera.test.networkName=" + networkName`
  - `arguments()` last element equals `Long.toString(nodeId)`
- Update the import in `SubProcessNode` and the single call site at line 217 to
  `destroyAnySubProcessNodeFor(metadata.networkName(), metadata.nodeId())`.

### Step 7: Verify

- Build: `./gradlew :test-clients:compileJava :test-clients:compileTestJava`
- Run a quick non-multi-network HapiTest to confirm nothing regressed.
- Run `ClprHieroToHieroSuite` (or just check that `jps` shows two concurrent
  `ServicesMain` processes when both ledgers are up).

## Files touched

- `NodeMetadata.java`
- `NetworkUtils.java` (two `classicMetadataFor` overloads)
- `WorkingDirUtils.java` (`recreateWorkingDir`, `updateLog4j2XmlOutputDir`)
- `AbstractLocalNode.java` (`initWorkingDir`)
- `ProcessUtils.java` (`javaCommandLineFor`, sweep rename)
- `SubProcessNode.java` (one call site)

## Out of scope

- Changing the `-local <id>` arg format on the subprocess command line.
  `ServicesMain` argument parsing stays untouched.
- Renaming the working-dir layout. `build/<networkName>-test/node<id>` is already
  network-scoped and fine.
- The `Runtime.getRuntime().addShutdownHook` registration in
  `SubProcessNetwork.newIsolatedNetwork` — orthogonal.
