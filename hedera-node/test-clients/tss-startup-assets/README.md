# TSS startup assets

This directory holds committed warm-start fixtures for the CLPR multi-network HAPI tests.
WRAPS proving parameters are bundled in the 3.23.0 library; no artifact download or environment
variable is needed. These fixtures retain the compatible Nova proofs generated after the 3.18.0
migration; pre-3.18.0 proofs cannot be reused.

## Fixture naming

There is **one fixture per network**, regardless of size, named
`<network>-genesis-network.json.gz` (e.g. `ledgerA_manifest-genesis-network.json.gz`).

### Digest-type variants

These fixtures use the release branch's SHA-384 state digest and the 64-byte ledger ID
required by the bundled-parameter WRAPS library. The retired `wrapsProvingKeyHash` is unused.

A `genesis-network.json` carries each node's TSS private key in `nodeTssMetadata`, and a node
can only export *its own* secret. So on export the per-node freeze exports are **merged** into one
file whose `nodeTssMetadata` holds every node's private-key entry (see
`TssFixtures.mergeNodeExports`). One file warm-starts the whole network because at
genesis `TssStartupNetworks` loads only the self node's key (filtered by `selfNodeId`) into
node-local key files, and the public material that enters hashed state is identical for every node.

The mapping is `TssFixtures.fixtureFileName(networkName)`.

## Expected contents

```
tss-startup-assets/
├── README.md                                       (tracked)
├── ledgerA-genesis-network.json.gz                 (tracked)
├── ledgerB-genesis-network.json.gz                 (tracked)
├── ledgerA_mtls-genesis-network.json.gz            (tracked; mTLS suite)
├── ledgerB_mtls-genesis-network.json.gz            (tracked; mTLS suite)
├── ledgerA_manifest-genesis-network.json.gz        (tracked; size-2 manifest suite, merged)
├── ledgerB_manifest-genesis-network.json.gz        (tracked; manifest suite)
├── ledgerA_restart-genesis-network.json.gz         (tracked; restart suite)
├── ledgerB_restart-genesis-network.json.gz         (tracked; restart suite)
└── <network>-genesis-network.json                  (gitignored; optional local copies)
```

`TssFixtures.resolveCachedFixturePath` prefers the `.gz` form when both exist, so a
stale uncompressed local copy can never silently shadow the committed source-of-truth.

## Warm-cache fixtures — committed in gzipped form

The `*-genesis-network.json.gz` files in this directory are committed to git.
On CI and on any fresh clone, `@MultiNetworkHapiTest.Network(tssPreload = true)`
tests find them automatically and skip the WRAPS bootstrap. No manual
seeding required.

The install path, per node, before the subprocess JVM starts:

1. `TssFixtures.installFixture` gunzips the merged fixture into the
   subprocess node's `data/config/genesis-network.json` (the form
   `DiskStartupNetworks` reads).
2. The loader allocates non-overlapping runtime ports and patches the installed fixture's transport endpoints and construction
   roster references. Keys, weights, and proofs remain unchanged: WRAPS binds the address book's
   cryptographic identities and weights, not its transport ports.

### Running a suite directly

A direct `testSubprocess --tests` run bypasses task-specific CLPR defaults. Use
`-PsysProp.hapi.spec.test.overrides=tss.hintsEnabled=true,tss.historyEnabled=true,tss.forceMockSignatures=false,clpr.enabled=true,hedera.transaction.maxBytes=16384`.
Real signatures are required for WRAPS-bearing blocks; CLPR must be enabled; the enlarged transaction limit
allows the suites' direct `verifyConfig` calls carrying a roughly 15 KB state proof.

### Regenerating

Delete the fixture(s) for a network and run a test that declares that network with
`@MultiNetworkHapiTest`. The extension cold-bootstraps TSS, freezes the network, merges every
node's private-key entry into one network fixture, then restarts warm.

For the two-node manifest fixture, temporarily remove `@Disabled("Fixture generator")` from
`ClprHieroToHieroManifestSuite.generateManifestLedgerFixtures` and run:

```bash
./gradlew :test-clients:testSubprocess \
  --tests "*ClprHieroToHieroManifestSuite.generateManifestLedgerFixtures" \
  "-PsysProp.hapi.spec.test.overrides=tss.hintsEnabled=true,tss.historyEnabled=true,tss.forceMockSignatures=false,clpr.enabled=true,hedera.transaction.maxBytes=16384"
```

Restore `@Disabled` afterwards. `TssFixtures.generateNetworkFixture` writes each fixture directly
as `<network>-genesis-network.json.gz`; commit the compressed files as-is. The restart fixtures
are retained from upstream and verified along with the other fixtures by `CommittedTssFixturesTest`.
