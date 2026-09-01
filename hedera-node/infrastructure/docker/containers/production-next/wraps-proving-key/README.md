# WRAPS proving-key image

Data-only OCI image carrying the WRAPS proving-key artifacts (HIP-1200 chain-of-trust
recursive proofs). The native `com.hedera.cryptography:hedera-cryptography-wraps` library
loads these artifacts from the directory named by the `TSS_LIB_WRAPS_ARTIFACTS_PATH`
environment variable; without them a node cannot construct WRAPS proofs
(`WRAPSLibraryBridge.isProofSupported()` returns `false`).

The image exists so CI runners can mount the ~2 GB artifact set instead of baking it into
runner images or downloading it per job. It is consumed by the `hapiTestWraps` and
`hapiTestCutover` XTS tasks (see `hedera-node/test-clients/build.gradle.kts`, which forwards
`TSS_LIB_WRAPS_ARTIFACTS_PATH` to every subprocess node).

## Contents

|           File           | Size (bytes) |                                              SHA-384                                               |
|--------------------------|--------------|----------------------------------------------------------------------------------------------------|
| `decider_pp.bin`         | 2327595832   | `5e2816e7bbd0cfbf5bf74ca693e881d784cd33a083d883ea677781744d3a760c7451d76b2a5f9b47fb06300a94f45a99` |
| `decider_vp.bin`         | 1768         | `8a1879d451d745a99834738fa1839d324c85a69e28a96892b9e1b080a3c7d7d1762045bdad388b1ffb346583bc982774` |
| `nova_pp.bin`            | 16842832     | `e2de61ab1ace3500faf5aefce587b42a8c874b62efb82477f3dc02a2109d23ee2426e4cd3c9ddc79bc41830b7a13d642` |
| `nova_vp.bin`            | 65768        | `d6208c538ed5119797ac4c5ee368e2e80c4e059f5a9c3ef6967bef97a7be45f73f1d10880ae8a00ca1cfbd118d359779` |
| `wraps.sha384`           | —            | `ac23d70230b5adec4a115386c85367ef9318dda2706b93a26243a6ba0c8eded47a54cc6fb0b648248a9560788f9bc517` |
| `wraps-artifacts.sha384` | —            | `db965a870a54e3bd351f2ad3969d580c40060f54f0828d210897b1a17c11d3f47f52720aef4bf5644bee58753c76cbb2` |

The image carries two housekeeping files:

- **`wraps.sha384`** — bare hex SHA-384 of the source tarball. The consensus node reads it from the
  mounted artifacts directory to detect that the artifacts are already in place and skip
  re-downloading the archive on every startup (see `WrapsProvingKeyVerification#WRAPS_HASH_FILE_NAME`).

- **`wraps-artifacts.sha384`** — per-file manifest in `sha384sum(1)` format listing the SHA-384 of
  each artifact file (`decider_pp.bin`, etc.). Written by the image build and also by the consensus
  node after extracting from a downloaded tarball. If present, the node checks it lists all required
  artifact files before treating the installation as complete; an absent manifest is accepted for
  backwards compatibility with older images (see
  `WrapsProvingKeyVerification#WRAPS_ARTIFACTS_MANIFEST_FILE_NAME`).

## Provenance

- Source: <https://builds.hedera.com/tss/hiero/wraps/v1.6/wraps-v1.6.0.tar.gz>
- Tarball SHA-384: `ead332b853b0312881ebaaae1a55020014c3b577f9639ad3c136e2d4e573d9c87261c328e8043dcbed497a34fae5b33f`

## Building

The image is published to this repository's ghcr.io namespace
(`ghcr.io/hiero-ledger/hiero-consensus-node/wraps-proving-key`) by dispatching the
`105: [USER] Publish Wraps Proving Key Image` workflow, which downloads the tarball, verifies its
hash, builds with the `Containerfile` here, pushes, and re-verifies the published image by
digest. To build manually instead:

```bash
curl -fSLo wraps-v1.6.0.tar.gz https://builds.hedera.com/tss/hiero/wraps/v1.6/wraps-v1.6.0.tar.gz
shasum -a 384 -c <<< "ead332b853b0312881ebaaae1a55020014c3b577f9639ad3c136e2d4e573d9c87261c328e8043dcbed497a34fae5b33f  wraps-v1.6.0.tar.gz"
mkdir wraps-v1.6.0 && tar xzf wraps-v1.6.0.tar.gz -C wraps-v1.6.0
printf '%s\n' ead332b853b0312881ebaaae1a55020014c3b577f9639ad3c136e2d4e573d9c87261c328e8043dcbed497a34fae5b33f > wraps-v1.6.0/wraps.sha384
podman build -f path/to/this/Containerfile -t <registry>/wraps-proving-key:v1.6.0 wraps-v1.6.0
podman push <registry>/wraps-proving-key:v1.6.0
```

Works identically with `docker build`/`docker push` or `buildah`. Tag per artifact version;
consumers should pin by digest. Assuming all required artifacts are present in the path, the
consensus node compares the `wraps.sha384` hash file against `tss.wrapsProvingKeyHash`: a match
means the mounted artifacts are trusted and the separate tar.gz download is skipped; otherwise it
falls back to downloading and verifying the archive.

## Consuming on Kubernetes runners

Preferred — `image:` volume source (GA and enabled by default since Kubernetes 1.36; beta in
1.33–1.35 behind the `ImageVolume` feature gate, requires containerd >= 2.1):

```yaml
volumes:
  - name: wraps-proving-key
    image:
      reference: <registry>/wraps-proving-key@sha256:<digest>
      pullPolicy: IfNotPresent
containers:
  - volumeMounts:
      - name: wraps-proving-key
        mountPath: /opt/wraps-v1.6.0
        readOnly: true
```

Fallback for older clusters — init container copying into an `emptyDir`:

```yaml
volumes:
  - name: wraps-proving-key
    emptyDir: {}
initContainers:
  - name: copy-wraps-proving-key
    image: <registry>/wraps-proving-key@sha256:<digest>
    command: ["/bin/true"] # FROM scratch has no shell; use the variant below instead
```

A `FROM scratch` image cannot run a copy command itself; for the fallback, either build a
busybox-based variant (`FROM busybox` + `COPY` + `cp` command) or mount the image with the
runtime's image-mount tooling. Prefer the `image:` volume — it needs no copy and the layer is
cached once per node.

Either way, the test job sets:

```
TSS_LIB_WRAPS_ARTIFACTS_PATH=/opt/wraps-v1.6.0
```
