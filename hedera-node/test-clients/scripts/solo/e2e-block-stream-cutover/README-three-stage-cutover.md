# Three-stage block-stream cutover

`solo-three-stage-block-stream-cutover.sh` implements the cutover scenario for [#27649](https://github.com/hiero-ledger/hiero-consensus-node/issues/27649).
The 0.79.0, 0.79.1 and 0.80.0 directories describe configuration stages. Every stage uses the
checkout's local CN build until published versions are available. `hedera.config.version`
increases from 0 to 1 to 2 so the same binary exercises upgrade initialization at each hop.

Every stage explicitly preserves Solo's local-network settings: `contracts.chainId=298` and
`nodes.gossipFqdnRestricted=false`. An upgrade replaces `application.properties`; omitting these
settings restores CN defaults (295 and true), breaking TCK Ethereum and gossip-domain tests
independently of the block-stream cutover. Runtime checks assert both values on every CN after
each deployment or upgrade.

| Stage  |                                              Behavior                                               |                                                                  Required verification                                                                   |
|--------|-----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0.79.0 | BOTH / FILE_AND_GRPC, mock signatures, TSS off, wrapped-record streaming and disk writing on, no BN | Runtime properties and local JAR version; hashes file exists and grows on every CN                                                                       |
| 0.79.1 | Jumpstart, live wrapped records, TSS enabled, mock signatures; then add BN v0.44.2                  | Offline jumpstart matches every CN's saved hashes; migration consumes the file; every migration vote matches an offline replay; WRAPS constructs a proof |
| 0.80.0 | BLOCKS / GRPC, cutover enabled, real signatures                                                     | Runtime properties and local JAR version; WRAPS proof construction; BN verifies and persists new blocks; CN restart has no SELF_ISS                      |

The jumpstart flow uses `cutover-jumpstart.sh`:
discover record files through mirror REST, download records and signatures from Solo MinIO,
compress day archives, run Block Node `blocks wrap`, parse `jumpstart.bin`, and inject the values
into a generated stage 2 properties file. Both current-block hashes are injected to require the
CN's disk hash match check. A standard-library Python verifier checks overlap, contiguous entries,
and both hashes on every node before upgrading. A second offline wrap reaches the migration
block and verifies the previous root, subtree hashes, and leaf count on every node. BN v0.44.2
is deployed only after the jumpstart + TSS upgrade and remains at that version through cutover
unless `BLOCK_NODE_UPGRADE_VERSION` explicitly requests a different version.

MN is deployed once at **v0.165.0-rc1** and stays at that version through all three stages.
This release uses protobuf 0.80.0-alpha.2, which preserves the CN's platform-state field
ordering when hashing native blocks. MN v0.164.2 reorders an unknown field and fails TSS
verification after cutover.
Its initial importer configuration enables record downloading and automatic cutover, with
`block.enabled=false`, `block.sourceType=BLOCK_NODE`, and the future BN address under
`block.nodes[0].endpoints[0]`. Before BN exists, records continue to arrive through MinIO.
After CN record production stops, MN's cutover service switches to BN and disables record
loading internally. Restricting the block source to BN prevents preview block files in MinIO
from triggering an early cutover. The scenario does not run `solo mirror node upgrade` at
stage 3; it checks importer and REST readiness after BN persistence verification instead.

After stage 3's BN, mirror, replay and MinIO isolation checks, the script runs the full **SDK TCK** suite
against the final network. The `cutover-tck.sh` helper clones the
TCK and JavaScript SDK versions pinned in `.github/workflows/support/citr/.citr-env`, installs
client/server dependencies, starts the SDK JSON-RPC server, forwards CN gRPC and mirror REST,
REST Java, gRPC and Web3, funds a dev-key account, then runs the suite. Web3 is forwarded from
`svc/mirror-1-web3` to localhost **8545**, the port JS SDK uses for local `/api/v1/contracts/call`
gas estimation when a contract query omits gas. The runner requires the Web3 deployment and
forward to be ready, and cleans up its owned forward with the other TCK resources.
A failing test or timeout fails
the scenario. TCK-only processes stop before final validation, or at exit on failure;
the network remains available for inspection.

During the suite the runner checks the SDK server, CN and mirror listeners. A closed listener
stops the worker processes and fails with an infrastructure error (exit 125), preserving the
console output and SDK log instead of accumulating connection-refused assertion failures.
It does not restart the SDK server mid-suite, since that would discard test sessions.
Mochawesome's parallel registration hook preserves individual test results; the exported report
also includes browser assets, run metadata and port-forward logs. Mirror REST tunnel output is
saved as `generated/three-stage-cutover/mirror-rest-port-forward.log`. A pod restart can terminate
a one-shot `kubectl port-forward` even when the pod later becomes Ready again.

The historical 0.73-to-0.77 e2e cutover script and its ad hoc workflow entry have been retired.
The three-stage scenario retains its ingestion assertions in `cutover-validation.sh`:

- After stage 1 and stage 2, create fresh accounts and require them to appear in MN.
- After stage 3, confirm record-object counts in MinIO are stable, require Ready nodes/BN/MN
  and a fresh account visible in MN while MinIO is still up, then stop MinIO and submit five fresh
  accounts, and require MN to ingest them through BN. Restore MinIO before TCK; exit cleanup
  also restores the original replica count if any step fails. Failure to isolate MinIO fails
  the scenario rather than silently allowing the records path.
- After TCK (or its explicit skip), submit another fresh account, require MN's latest block to
  advance by at least three without regression, and fetch up to 25 blocks of the observed span
  through BN `getBlock`, requiring `SUCCESS` and non-empty items.
- Verify every tracked transaction ID from the stage nudges, including record-period flushes,
  appears in MN with `SUCCESS`, and require the record-object count to remain unchanged across
  the isolation check, TCK and final validation traffic. This ledger covers scenario transactions;
  the TCK runner reports its own test results separately.

`MIRROR_VALIDATION_TX_COUNT` (default 1), `BN_ISOLATION_TX_COUNT` (5), and
`MIRROR_ACCOUNT_WAIT_SECS` (180) control fresh-ingestion checks.
`MN_BLOCK_PROGRESS_MIN_DELTA` (3), `MN_BLOCK_PROGRESS_TIMEOUT_SECS` (180),
`MN_BLOCK_PROGRESS_POLL_SECS` (5), `BN_GET_BLOCK_MAX_PER_CHECK` (25), and
`RECORD_CESSATION_SETTLE_SECS` (15) control the final assertions.

## Running

Build CN artifacts with `./gradlew assemble`. Install Solo (the CI-selected version), Kind,
Docker, kubectl, Helm, curl, unzip, Node/npm, jq, OpenSSL, grpcurl, Python 3, Git, and the JDK
required by the CN and Block Node builds. SDK TCK also needs lsof, pgrep and pnpm; the script installs pnpm
with `npm install -g pnpm` if missing. Clone `hiero-block-node` next to this repository,
or set `BLOCK_NODE_REPO_PATH`. Its Git history must include the pinned `v0.45.0-sha256.1` tag.
The script uses that tools revision in both CI and local runs. If your checkout is on another
revision or has changes, it creates an isolated Git worktree under
`~/.cache/hiero-solo/block-node-tools/`, preserving your original branch and files. Set
`BLOCK_NODE_TOOLS_CACHE_DIR` to relocate the cache, or `BLOCK_NODE_TOOLS_REF` to deliberately
select another compatible tools revision. Install `zstd` or allow the shared helper to use
the Gradle-cached `zstd-jni` fallback.

The tools' HAPI wire definitions and hash algorithm must agree with the CN's. The CN encodes
`BlockHashAlgorithm.SHA2_256` as `0` and `SHA2_384` as `1`, and still hashes with SHA-384
(`blockStream.digestType` defaults to `SHA_384`). Block Node tools older than the enum change
omit the SHA-384 header field, and tools that hash with SHA-256 produce 32-byte hashes; either
changes the serialized block header or every wrapped-block root. `v0.45.0-sha256.1` has the new
enum and keeps the wrap CLI on SHA-384. A completed hash mismatch fails immediately with
both hashes; only unfinished CN file snapshots are retried.

Allocate **32 GiB RAM to Docker Desktop** for the four CNs, BN and MN. A 16 GiB VM has
caused OOM kills, swap pressure, DNS failures and a NotReady Kind node during cutover, even
with adequate disk space. Before touching the cluster the script checks Docker's reported
memory against `DOCKER_MIN_MEMORY_MIB` (default 24576 MiB). Docker reports usable VM memory,
which is slightly less than the configured amount, so a 32 GiB setting passes this guard.
Isolation checks require fresh MN ingestion before stopping MinIO; an HTTP response containing
old blocks alone does not prove the importer is healthy. Restoration retries transient API
failures and waits up to `MINIO_RESTORE_TIMEOUT_SECS` (default 300). A restored replica target
with unready pods is reported separately from a failed scale request. Failure diagnostics
include importer current/previous logs, MinIO pod details, node/pod health, memory PSI, swap,
and OOM counters alongside disk usage.

The mirror chart's development limits also need room for native-image startup and block
processing: the importer uses a 512 MiB request and 1 GiB limit (2 CPUs); gRPC and Web3 each
use a 256 MiB request and 512 MiB limit (1 CPU). Override memory with
`MIRROR_IMPORTER_MEMORY_REQUEST` / `MIRROR_IMPORTER_MEMORY_LIMIT`,
`MIRROR_GRPC_MEMORY_REQUEST` / `MIRROR_GRPC_MEMORY_LIMIT`, and
`MIRROR_WEB3_MEMORY_REQUEST` / `MIRROR_WEB3_MEMORY_LIMIT`. MN remains v0.165.0-rc1.
The REST service uses a 256 MiB / 100m request and a 512 MiB / 2 CPU limit, configurable with
`MIRROR_REST_MEMORY_REQUEST` / `MIRROR_REST_MEMORY_LIMIT`. Failure diagnostics retain
both current and previous REST logs and its pod description to distinguish restart causes.

BN init containers use `docker.io/library/busybox:1.36.1` with `IfNotPresent` so they can reuse
Kind's cached image. Override it with `BLOCK_NODE_INIT_IMAGE` if needed. Before adding BN,
the script checks every Kind node's `/var` filesystem for at least 1024 MiB free (configurable
with `BLOCK_NODE_MIN_FREE_DISK_MIB`). This is a minimum deployment guard, not an estimate of
the storage needed for the entire run. Allow room for consensus state, MinIO, and container images in Docker Desktop's virtual disk. Exhausting that
disk can cause both `ImagePullBackOff` and Kubernetes API-server failures even when the Mac
has free disk space. Failure artifacts include the BN pod description and image-pull events.

Before each CN upgrade, the script adopts the shared and per-node consensus ConfigMaps into
`solo-deployment`. On Helm 4 it also releases data-field ownership held by Solo's `node-fetch`
writes or earlier `helm` Update operations. Helm 4's server-side Apply can then update
`block-nodes.json` and `application.properties` without ownership conflicts. This changes only
ownership metadata, preserves ConfigMap contents and unrelated field managers, and guards the
patch with the resource version to detect concurrent writes. Helm 3 retains the label/annotation
adoption behavior used by CI.

```bash
hedera-node/test-clients/scripts/solo/e2e-block-stream-cutover/solo-three-stage-block-stream-cutover.sh
```

The script recreates its named Kind cluster. It leaves the network and UI forwards running for
inspection; remove the cluster with `kind delete cluster -n solo-three-stage-cutover`.
`SOLO_CLUSTER_NAME`, `SOLO_DEPLOYMENT`, `NODE_ALIASES`, `LOCAL_BUILD_PATH`, and
`GENERATED_DIR` can override the defaults. `DEPLOY_RELEASE_TAG` optionally selects a published
stage 1 baseline; leave it empty to use local JARs. The remaining stages always use local JARs.
`RESTART_REPLAY_CHECK=true` is the default; setting it false skips the replay acceptance check.

SDK TCK runs by default. Set `ENABLE_TCK_TESTS=false` to skip it, or select test files with
`TCK_TEST_FILES="<file1>,<file2>"` (the same paths accepted by TCK's `npm run test:file`).
`TCK_VERSION` and `JS_SDK_VERSION` override the XTS pins. `TCK_REPO_PATH` and
`JS_SDK_REPO_PATH` reuse local checkouts; SDK server setup modifies the SDK checkout's `tck/`
dependency files. `TCK_TEST_TIMEOUT_SECS=3600` limits each suite/file invocation, and
`TCK_INSTALL_TIMEOUT_SECS=900` limits each dependency install. The JSON-RPC server uses port
8544, mirror gRPC 5600 and REST Java 8084; override with `TCK_JSON_RPC_PORT`,
`MIRROR_GRPC_LOCAL_PORT`, and `MIRROR_RESTJAVA_LOCAL_PORT` if necessary.

Mochawesome HTML/JSON reports and the SDK server log survive under
`generated/three-stage-cutover/tck-report/` (override with `TCK_REPORT_EXPORT_DIR`). CI uploads
these reports on success and failure and allows extra time for the full suite.

Generated files live in `generated/three-stage-cutover/`: initial and replay jumpstart binaries,
stage 1 hash snapshots, generated application properties, `submitted-transactions.txt`, per-node migration comparison logs,
and logs/configs from each stage. CI uploads these on failure, alongside the Solo and pod logs.

The XTS input is `enable-three-stage-block-stream-cutover-panel`; the ad hoc scenario is
`three-stage-block-stream-cutover`. Both invoke the same script and build CN artifacts first. The ad hoc `cutover-enable-tck` input
controls the three-stage scenario's TCK step; all explicit ingestion checks still run when false.

## Follow-on issue draft: use published CN releases in the cutover panel

Depends on published v0.79.0 and v0.79.1 artifacts. Related: #27649.

Replace the configuration-only local-build baselines with published CN versions: deploy
v0.79.0 with the stage 1 properties and upgrade to v0.79.1 with the generated stage 2 jumpstart
properties. Retain the checkout's local build for the final 0.80.0 cutover. Expose and document
the two release tags in the reusable XTS and ad hoc workflows. Preserve the freeze/reapply/start
workaround for Solo's property rewrites and the WRAPS core limit injected before genesis. WRAPS proving parameters are bundled in the
cryptography library; no external artifact download or artifact-path environment variable is needed.

Acceptance: XTS and ad hoc runs pass with the published first two stages and the local final
stage; binary version assertions identify the selected releases; all disk growth, jumpstart,
per-node replay comparison, TSS, BN persistence and clean-replay checks remain required;
failure artifacts identify the version and configuration of each stage.
