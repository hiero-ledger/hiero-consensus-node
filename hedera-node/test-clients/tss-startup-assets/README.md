# TSS startup assets

This directory holds the binaries the CLPR Hiero-to-Hiero multi-network HAPI
tests load at startup. Two things live here:

- The **WRAPS proving artifacts** (`wraps-vX.Y.Z/`), needed for cold runs (when
  **warm-cache fixtures** are missing).
  Too large to commit — populated by hand only for local runs.
- The **warm-cache fixtures** (`*-genesis-network.json.gz`), committed in
  gzipped form (~4.5 MB each) so CI gets them for free. Local uncompressed
  `.json` variants are gitignored and optional.

## Expected contents

```
tss-startup-assets/
├── README.md                          (tracked)
├── wraps-v1.0.0.tar.gz                (gitignored; manual download or CI cache)
├── wraps-v1.0.0/                      (gitignored; extracted from the archive above)
│   └── ... WRAPS proving artifacts ...
├── ledgerA-genesis-network.json.gz    (tracked; ~4.5 MB committed)
├── ledgerB-genesis-network.json.gz    (tracked; ~4.5 MB committed)
├── ledgerA-genesis-network.json       (gitignored; optional ~42 MB local copy)
└── ledgerB-genesis-network.json       (gitignored; optional ~42 MB local copy)
```

`MultiNetworkExtension.resolveCachedFixturePath` prefers the `.gz` form when
both exist, so a stale uncompressed local copy can never silently shadow the
committed source-of-truth.

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

`MultiNetworkExtension.installFixture` gunzips them on the fly into the
subprocess node's `data/config/genesis-network.json` (the form `DiskStartupNetworks`
reads).

### Regenerating

If the fixtures need refreshing (e.g. WRAPS protocol bump, ledger ID change):

1. Delete the existing committed fixtures so the cold path runs:

   ```bash
   rm ledgerA-genesis-network.json.gz ledgerB-genesis-network.json.gz
   ```
2. Run any `tssPreload = true` test — the cold path will harvest fresh fixtures
   on its first successful pass. The shortest one is fine:

   ```bash
   ./gradlew :test-clients:testSubprocess \
     --tests "*ClprHieroToHieroSuite.oneWayDelivery*"
   ```
3. `MultiNetworkExtension.harvestFreshFixtureOrThrow` / `cacheTssFixtureIfMissing`
   write the harvested snapshots directly as `*-genesis-network.json.gz` — no
   manual `gzip` step. `git add` them as-is.
