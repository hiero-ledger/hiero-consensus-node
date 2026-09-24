# TSS startup assets

This directory holds the binaries the CLPR Hiero-to-Hiero multi-network HAPI
tests load at startup. Two things live here:

- The **WRAPS proving artifacts** (`wraps/`, extracted from the versioned
  `wraps-vX.Y.Z.tar.gz` archive), needed for cold runs (when **warm-cache
  fixtures** are missing). Too large to commit — populated for local runs.
- The **warm-cache fixtures** (`*-genesis-network.json.gz`), committed in
  gzipped form (~4.5 MB each) so CI gets them for free. Local uncompressed
  `.json` variants are gitignored and optional.

## Fixture naming

There is **one fixture per network**, regardless of size, named
`<network>-genesis-network.json.gz` (e.g. `ledgerA_manifest-genesis-network.json.gz`).

A `genesis-network.json` carries each node's TSS private key in `nodeTssMetadata`, and a node
can only export *its own* secret. So on export the per-node freeze exports are **merged** into one
file whose `nodeTssMetadata` holds every node's private-key entry (see
`MultiNetworkExtension.mergeNodeExports`). One file warm-starts the whole network because at
genesis `TssStartupNetworks` loads only the self node's key (filtered by `selfNodeId`) into
node-local key files, and the public material that enters hashed state is identical for every node.

The mapping is `MultiNetworkExtension.fixtureFileName(networkName)`.

## Expected contents

```
tss-startup-assets/
├── README.md                                       (tracked)
├── wraps-v1.0.0.tar.gz                             (gitignored; manual download or CI cache)
├── wraps/                                          (gitignored; extracted from the archive above)
│   └── ... WRAPS proving artifacts ...
├── ledgerA-genesis-network.json.gz                 (tracked)
├── ledgerB-genesis-network.json.gz                 (tracked)
├── ledgerA_mtls-genesis-network.json.gz            (tracked; mTLS suite)
├── ledgerB_mtls-genesis-network.json.gz            (tracked; mTLS suite)
├── ledgerA_manifest-genesis-network.json.gz        (tracked; size-2 manifest suite, merged)
├── ledgerB_manifest-genesis-network.json.gz        (tracked; manifest suite)
└── <network>-genesis-network.json                  (gitignored; optional ~42 MB local copies)
```

`MultiNetworkExtension.resolveCachedFixturePath` prefers the `.gz` form when both exist, so a
stale uncompressed local copy can never silently shadow the committed source-of-truth.

## 1. WRAPS proving artifacts — required for every cold-path run

Just drop the archive `wraps-v1.0.0.tar.gz` into this directory:

```bash
cd hedera-node/test-clients/tss-startup-assets
# (download or copy wraps-v1.0.0.tar.gz here)
```

That is all that is required. The test framework provisions the rest automatically: on the
cold path, `ClprWrapsProvingKeyInstaller.ensureProvisioned` (called from `MultiNetworkExtension`'s cold-path
branch, once per dir before any node forks) **extracts** the sibling `wraps*.tar.gz` into the
`wraps/` subdirectory if the `.bin` files are absent, and **writes** the `wraps/wraps.sha384`
hash file if it is absent. `build.gradle.kts` maps the `hapiTestClprMultinetwork` task to this
`wraps/` path and forwards it to the node, so having only the archive is enough.

The provisioning is idempotent: if the four `.bin` files are already there it does **not**
re-extract, and if `wraps.sha384` is already there it does **not** regenerate it.

### Why the `wraps.sha384` hash file exists

Since the upstream WRAPS proving-key verification landed (`tss.wrapsProvingKeyHash` now
defaults to the archive hash `620cbcf6…`, not empty),
`WrapsProvingKeyVerification.artifactsInstalledAndVerified` gates `wrapsProverReady` on a
`wraps.sha384` file in the artifacts directory whose content equals the configured hash. The
runtime writes it only on its download path, which these subprocess tests disable — so the test
framework computes `SHA-384(wraps-v1.0.0.tar.gz)` (never echoing config, so a wrong archive
still fails verification) and writes it. Without this file the recursive prover never becomes
ready, `POST_AGGREGATION` is deferred every round, and the cold path fails with
`did not produce a WRAPS-ready history proof within PT25M`.

### Doing it by hand (optional)

If you prefer to provision manually (e.g. outside the test framework), extract with `-C` so the
flat archive lands under `wraps/` (its entries are the `.bin` files at the archive root —
running `tar -xzf wraps-v1.0.0.tar.gz` on its own scatters them into `tss-startup-assets/`), and
write the bare hex hash (read with `.trim()`):

```bash
mkdir -p wraps
tar -xzf wraps-v1.0.0.tar.gz -C wraps
shasum -a 384 wraps-v1.0.0.tar.gz | cut -d' ' -f1 | tr -d '\n' > wraps/wraps.sha384
```

Verify the layout:

```bash
ls wraps/     # decider_pp.bin  decider_vp.bin  nova_pp.bin  nova_vp.bin  wraps.sha384
cat wraps/wraps.sha384   # 620cbcf6…  (must equal tss.wrapsProvingKeyHash)
```

`hedera-node/test-clients/build.gradle.kts` forwards `hapi.spec.tssLibWrapsArtifactsPath` to the
subprocess JVM only for the WRAPS-needing tasks (see `prCheckTssLibWrapsArtifactsPaths`), not for
every task. Only `hapiTestClprMultinetwork` (the `@Tag(MULTINETWORK)` selector CI runs) is mapped to
this canonical `wraps/` path; a bare `testSubprocess` and plain tag selectors like `hapiTestCrypto`
are not. So to run a single multi-network test directly via `testSubprocess` (e.g. from the IDE, or
with a `--tests` filter), you must pass the path yourself:

        -Dhapi.spec.tssLibWrapsArtifactsPath="$PWD/hedera-node/test-clients/tss-startup-assets/wraps"

(You can also point it at a different location the same way — the `-D` always wins over the mapping.)

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

1. `MultiNetworkExtension.installFixture` gunzips the merged fixture into the
   subprocess node's `data/config/genesis-network.json` (the form
   `DiskStartupNetworks` reads).
2. The ports in the fixture are **not** patched. Instead
   `MultiNetworkExtension.ensureFixturePortReservations` (run once at
   `startNetworks`) seeds `RESERVATIONS_BY_NAME` from the committed fixtures, so
   `resolveFirstGrpcPort` hands each network back its own capture-time base and
   the warm run comes up on exactly the ports baked into the fixture. (A network
   without a fixture is allocated a range that avoids all of them.) So the fixture
   is installed verbatim — TSS keys, rosters, weights, gossip CA cert, and the
   in-state gossip/service endpoints all match this run.

### Regenerating

All fixtures are (re)generated by `ClprFixtureGeneratorSuite` (`@Tag(MULTINETWORK)`), one
`@Disabled("Fixture generator")` test per fixture set:

- `generateLedgerFixtures` -> `ledgerA`, `ledgerB`
- `generateMtlsLedgerFixtures` -> `ledgerA_mtls`, `ledgerB_mtls`
- `generateManifestLedgerFixtures` -> `ledgerA_manifest` (size 2), `ledgerB_manifest`

Each brings its network(s) up cold (only when the committed fixture is deleted); the
`@MultiNetworkHapiTest` extension then freezes, merges each node's freeze export into one
`<network>-genesis-network.json.gz`, and restarts warm. The generators are `@Disabled` so they
never run in a normal suite pass.

**To regenerate one fixture set:**

1. Delete its committed fixture(s), e.g.:

   ```bash
   rm ledgerA_manifest-genesis-network.json.gz ledgerB_manifest-genesis-network.json.gz
   ```
2. Run its generator. Either delete the method's `@Disabled` line, or pass
   `-PsysProp.junit.jupiter.conditions.deactivate='*'` (a bare `-Djunit...` does **not** work -- this
   build only forwards system properties to the test JVM via the `-PsysProp.` prefix). Also supply the
   WRAPS path, since a bare `testSubprocess` is not in the wraps map (only `hapiTestClprMultinetwork` is):

   ```bash
   ./gradlew :test-clients:testSubprocess \
     --tests "*ClprFixtureGeneratorSuite.generateManifestLedgerFixtures" \
     -Dhapi.spec.tssLibWrapsArtifactsPath="$PWD/hedera-node/test-clients/tss-startup-assets/wraps"
   ```
3. Restore the `@Disabled` and `git add` the regenerated `*-genesis-network.json.gz`.

**To regenerate everything with coordinated ports**, delete all the committed
`*-genesis-network.json.gz` and run the whole suite in one JVM, so
`ensureFixturePortReservations` gives each network a globally-unique, non-overlapping port range
that its warm consumers then reproduce:

```bash
rm *-genesis-network.json.gz
./gradlew :test-clients:testSubprocess --tests "*ClprFixtureGeneratorSuite" \
  -PsysProp.junit.jupiter.conditions.deactivate='*' \
  -Dhapi.spec.tssLibWrapsArtifactsPath="$PWD/hedera-node/test-clients/tss-startup-assets/wraps"
```

`MultiNetworkExtension.harvestFreshFixtureOrThrow` writes each fixture directly as
`<network>-genesis-network.json.gz` (no manual `gzip` step); `git add` them as-is.
