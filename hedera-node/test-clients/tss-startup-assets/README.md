# TSS startup assets

This directory holds the binaries the CLPR Hiero-to-Hiero multi-network HAPI
tests load at startup. Two things live here:

- The **WRAPS proving artifacts** (`wraps-vX.Y.Z/`), needed for cold runs (when
  **warm-cache fixtures** are missing).
  Too large to commit — populated by hand only for local runs.
- The **warm-cache fixtures** (`*-genesis-network.json.gz`), committed in
  gzipped form (~4.5 MB each) so CI gets them for free. Local uncompressed
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
├── wraps-v1.0.0.tar.gz                             (gitignored; manual download or CI cache)
├── wraps-v1.0.0/                                   (gitignored; extracted from the archive above)
│   └── ... WRAPS proving artifacts ...
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

## 1. WRAPS proving artifacts — required for every cold-path run

Download `wraps-v1.0.0.tar.gz` into this directory and extract it **into a
`wraps-v1.0.0/` subdirectory**:

```bash
cd hedera-node/test-clients/tss-startup-assets
mkdir -p wraps-v1.0.0
tar -xzf wraps-v1.0.0.tar.gz -C wraps-v1.0.0
```

> ⚠️ The tarball is **flat** — its entries are the `.bin` files at the archive root
> (`decider_pp.bin`, `decider_vp.bin`, `nova_pp.bin`, `nova_vp.bin`), **not** a
> `wraps-v1.0.0/` directory. Do **not** run `tar -xzf wraps-v1.0.0.tar.gz` on its own:
> that scatters the `.bin` files directly into `tss-startup-assets/`, and the build's
> auto-detect (which looks for the `wraps-v1.0.0/` directory) won't find them. Always
> extract with `-C wraps-v1.0.0`.

Verify the layout:

```bash
ls wraps-v1.0.0/     # decider_pp.bin  decider_vp.bin  nova_pp.bin  nova_vp.bin
```

`hedera-node/test-clients/build.gradle.kts` auto-detects the `wraps-v1.0.0/` directory
and forwards `-Dhapi.spec.tssLibWrapsArtifactsPath=<abs path to wraps-v1.0.0>` to the
subprocess JVM. To use a different path instead, pass
`-Dhapi.spec.tssLibWrapsArtifactsPath=...` on the Gradle command line — it overrides the
auto-detect.

If the artifacts are missing, cold-path nodes log
`WRAPS enabled but this node cannot build recursive proofs (TSS_LIB_WRAPS_ARTIFACTS_PATH='')`
and the multi-network startup eventually fails with
`did not produce a WRAPS-ready history proof within PT25M`.

## 2. Warm-cache fixtures — committed in gzipped form

The `*-genesis-network.json.gz` files in this directory are committed to git.
On CI and on any fresh clone, `@MultiNetworkHapiTest.Network(tssPreload = true)`
tests find them automatically and skip the ~8-minute WRAPS bootstrap. No manual
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

There are two categories of fixture, regenerated differently.

**Single-node fixtures** (`ledgerA`, `ledgerB`, `ledgerA_mtls`, `ledgerB_mtls`,
`ledgerB_manifest`, …). Refresh them when e.g. the WRAPS protocol bumps or the
ledger ID changes:

1. Delete the committed fixture(s) so the cold path runs, e.g.:

   ```bash
   rm ledgerA-genesis-network.json.gz ledgerB-genesis-network.json.gz
   ```
2. Run any `tssPreload = true` test that declares that network — the cold path
   harvests a fresh fixture on its first successful pass. The shortest is fine:

   ```bash
   ./gradlew :test-clients:testSubprocess \
     --tests "*ClprHieroToHieroSuite.oneWayDelivery*"
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
   deactivation, `-Djunit.jupiter.conditions.deactivate=*`), then run it:

   ```bash
   ./gradlew :test-clients:testSubprocess \
     --tests "*ClprHieroToHieroManifestSuite.generateManifestLedgerFixtures"
   ```

   It brings both networks up cold, harvests each node's fixture, and asserts the
   per-node TSS keys are distinct (`assertPerNodeFixturesHaveDistinctKeys`).

3. Restore the `@Disabled` annotation and `git add` the regenerated
   `*-genesis-network.json.gz` files.
