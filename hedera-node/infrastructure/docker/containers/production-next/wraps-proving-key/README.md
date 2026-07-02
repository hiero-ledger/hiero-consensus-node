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

|       File       | Size (bytes) |                                              SHA-384                                               |
|------------------|--------------|----------------------------------------------------------------------------------------------------|
| `decider_pp.bin` | 2170538264   | `8e9fbd68c7f28d56146857b52626185918162a1ecfce25856576465139d1bf4ef638dcbfaf15548d336e56206ae61dca` |
| `decider_vp.bin` | 1768         | `53e72141e4cf68f4b862daa4acc634d4f2220ecc2d44feca90b377fc5e786dad288dbf32722dd3e18217727a20666da2` |
| `nova_pp.bin`    | 8454224      | `18d89cfff6e5abebfa8d1740974ce0bd2b60449c67ef7d7de0d3ab377c4b265ab9a99c01fcd03d33da77c6dc47a8c467` |
| `nova_vp.bin`    | 65768        | `c45e3146f4b3b093c3301ec42171d18f7015c01615d29ca1ab9354d790b5c7ca888f82f26a458366d3b1ff623c8d6c1f` |

The image also carries a `wraps.sha384` hash file containing the bare hex SHA-384 of the source
tarball (the value under **Provenance** below). The consensus node reads it from the mounted
artifacts directory to detect that the artifacts are already in place and skip re-downloading the
archive on every startup (see `WrapsProvingKeyVerification#WRAPS_HASH_FILE_NAME`).

## Provenance

- Source: <https://builds.hedera.com/tss/hiero/wraps/v1.0/wraps-v1.0.0.tar.gz>
- Tarball SHA-384: `620cbcf69098d31a0893081cb76113ee0f72091b3417e601178cdc376c81e5c2407c1827d123df44bccb78ad4bb11fb3`

## Building

The image is published to this repository's ghcr.io namespace
(`ghcr.io/hiero-ledger/hiero-consensus-node/wraps-proving-key`) by dispatching the
`105: [USER] Publish Wraps Proving Key Image` workflow, which downloads the tarball, verifies its
hash, builds with the `Containerfile` here, pushes, and re-verifies the published image by
digest. To build manually instead:

```bash
curl -fSLo wraps-v1.0.0.tar.gz https://builds.hedera.com/tss/hiero/wraps/v1.0/wraps-v1.0.0.tar.gz
shasum -a 384 -c <<< "620cbcf69098d31a0893081cb76113ee0f72091b3417e601178cdc376c81e5c2407c1827d123df44bccb78ad4bb11fb3  wraps-v1.0.0.tar.gz"
mkdir wraps-v1.0.0 && tar xzf wraps-v1.0.0.tar.gz -C wraps-v1.0.0
printf '%s\n' 620cbcf69098d31a0893081cb76113ee0f72091b3417e601178cdc376c81e5c2407c1827d123df44bccb78ad4bb11fb3 > wraps-v1.0.0/wraps.sha384
podman build -f path/to/this/Containerfile -t <registry>/wraps-proving-key:v1.0.0 wraps-v1.0.0
podman push <registry>/wraps-proving-key:v1.0.0
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
        mountPath: /opt/wraps-v1.0.0
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
TSS_LIB_WRAPS_ARTIFACTS_PATH=/opt/wraps-v1.0.0
```
