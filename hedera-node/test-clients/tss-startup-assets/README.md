# TSS startup assets

This directory holds the **warm-cache fixtures** the CLPR Hiero-to-Hiero
multi-network HAPI tests load at startup (`*-genesis-network.json.gz`), committed
in gzipped form (~4.5 MB each) so CI gets them for free. Local uncompressed
`.json` variants are gitignored and optional.

## Fixture naming

Fixtures are keyed by network name, and the form depends on the network's size:

- **Single-node networks** (`size = 1`): one fixture named
  `<network>-genesis-network.json.gz` (e.g. `ledgerB_manifest-genesis-network.json.gz`).
- **Multi-node networks** (`size > 1`): one fixture **per node**, named
  `<network>-node<id>-genesis-network.json.gz` (e.g.
  `ledgerA_manifest-node0-...`, `ledgerA_manifest-node1-...`). Each node's
  fixture carries only *its own* TSS private key, so a multi-node network needs
  the full per-node set to warm-start — a single shared file would duplicate one
  key and silently break the other nodes.

The mapping is `MultiNetworkExtension.perNodeFixtureBase(networkName, nodeId, size)`.

## Expected contents

```
tss-startup-assets/
├── README.md                                       (tracked)
├── ledgerA-genesis-network.json.gz                 (tracked; single-node)
├── ledgerB-genesis-network.json.gz                 (tracked; single-node)
├── ledgerA_mtls-genesis-network.json.gz            (tracked; single-node, mTLS suite)
├── ledgerB_mtls-genesis-network.json.gz            (tracked; single-node, mTLS suite)
├── ledgerB_manifest-genesis-network.json.gz        (tracked; single-node, manifest suite)
├── ledgerA_manifest-node0-genesis-network.json.gz  (tracked; per-node, size-2 manifest suite)
├── ledgerA_manifest-node1-genesis-network.json.gz  (tracked; per-node, size-2 manifest suite)
└── <network>[-node<id>]-genesis-network.json       (gitignored; optional ~42 MB local copies)
```

`MultiNetworkExtension.resolvePerNodeCachedFixturePath` prefers the `.gz` form
when both exist, so a stale uncompressed local copy can never silently shadow
the committed source-of-truth.

## Cold runs

A cold run (one without a fixture for some network) needs nothing extra in this
directory: the WRAPS library embeds the public parameters it needs to construct
proofs. The network bootstraps TSS at runtime, constructing its genesis WRAPS
proof in seconds, and the multi-network startup fails with
`did not produce a WRAPS-ready history proof within PT10M` if it never does.

## Warm-cache fixtures — committed in gzipped form

The `*-genesis-network.json.gz` files in this directory are committed to git.
On CI and on any fresh clone, `@MultiNetworkHapiTest.Network(tssPreload = true)`
tests find them automatically and skip the runtime TSS bootstrap. No manual
seeding required.

The install path, per node, before the subprocess JVM starts:

1. `MultiNetworkExtension.installFixture` gunzips the cached fixture into the
   subprocess node's `data/config/genesis-network.json` (the form
   `DiskStartupNetworks` reads).
2. `MultiNetworkExtension.rewriteFixturePortsToRuntime` then patches the ports of
   every node entry in the just-installed fixture to match this run's
   randomly-allocated ports, resetting the IP to loopback. This is **mandatory**:
   the fixture was captured with the ports from its cold-bootstrap run, and every
   warm run auto-allocates fresh ports, so without the rewrite the node's
   in-state service/gossip endpoints would point at dead ports. Everything else —
   TSS keys, rosters, weights, gossip CA cert — is preserved, which is what makes
   the warm start valid.

### Regenerating

These suites need real TSS signatures and CLPR enabled, neither of which is the
default; so pass these overrides to every command below:

```bash
-PsysProp.hapi.spec.test.overrides=tss.forceMockSignatures=false,clpr.enabled=true,hedera.transaction.maxBytes=16384
```

- `tss.forceMockSignatures=false`: with mock signatures the signer is ready
  immediately, so the cold bootstrap never finishes on an idle network (the
  hinTS CRS waits on consensus time that only transactions advance) and block
  proofs never embed the WRAPS proof.
- `clpr.enabled=true`: CLPR is off by default.
- `hedera.transaction.maxBytes=16384`: the suites probe a captured state proof
  with a direct `verifyConfig` call, which the CLPR system contract bounds by
  this limit (unlike CLPR's own dispatches); a state proof is ~15 KB now that it
  carries an 11,368-byte WRAPS proof.

There are two categories of fixture, regenerated differently.

**Single-node fixtures** (`ledgerA`, `ledgerB`, `ledgerA_mtls`, `ledgerB_mtls`,
`ledgerB_manifest`, …). Refresh them when e.g. the TSS library changes its key
or proof formats, or the ledger ID changes:

1. Delete the committed fixture(s) so the cold path runs, e.g.:

   ```bash
   rm ledgerA-genesis-network.json.gz ledgerB-genesis-network.json.gz
   ```
2. Run any `tssPreload = true` test that declares that network — the cold path
   harvests a fresh fixture on its first successful pass. The shortest is fine:

   ```bash
   ./gradlew :test-clients:testSubprocess \
     --tests "*ClprHieroToHieroSuite.oneWayDelivery*" \
     "-PsysProp.hapi.spec.test.overrides=tss.forceMockSignatures=false,clpr.enabled=true,hedera.transaction.maxBytes=16384"
   ```
3. `MultiNetworkExtension.cacheTssFixtureIfMissing` (success path) /
   `harvestFreshFixtureOrThrow` (cold-bootstrap path) write the harvested
   snapshots directly as `*-genesis-network.json.gz` — no manual `gzip` step.
   `git add` them as-is.

**Per-node multi-node manifest fixtures**
(`ledgerA_manifest-node0/1-...`). A regular `--tests` filter cannot regenerate
these: the only test that brings up the size-2 mTLS `ledgerA_manifest` /
`ledgerB_manifest` networks for harvesting is
`ClprHieroToHieroManifestSuite.generateManifestLedgerFixtures`, which is
`@Disabled("Fixture generator")` (so it never runs in a normal suite pass and is
not discoverable by name).

1. Delete the committed manifest fixtures so the cold path runs:

   ```bash
   rm ledgerA_manifest-node*-genesis-network.json.gz ledgerB_manifest-genesis-network.json.gz
   ```
2. Temporarily remove the `@Disabled` annotation from
   `generateManifestLedgerFixtures` (or run with JUnit's disabled-condition
   deactivation, `-PsysProp.junit.jupiter.conditions.deactivate=*`), then run it:

   ```bash
   ./gradlew :test-clients:testSubprocess \
     --tests "*ClprHieroToHieroManifestSuite.generateManifestLedgerFixtures" \
     "-PsysProp.hapi.spec.test.overrides=tss.forceMockSignatures=false,clpr.enabled=true,hedera.transaction.maxBytes=16384"
   ```

   It brings both networks up cold, harvests each node's fixture, and asserts the
   per-node TSS keys are distinct (`assertPerNodeFixturesHaveDistinctKeys`).

3. Restore the `@Disabled` annotation and `git add` the regenerated
   `*-genesis-network.json.gz` files.
