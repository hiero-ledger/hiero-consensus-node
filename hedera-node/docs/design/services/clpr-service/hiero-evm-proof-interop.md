# Hiero proof interoperability with an EVM verifier

Retrieve the signed Hiero config and endpoint manifest proofs from the configured
Hiero CLI to complete the destination channel.

```sh
yahcli clpr get-ledger-configuration --proof-path config.pb
yahcli clpr get-endpoint-manifest --proof-path manifest.pb
```

Wait for the endpoint manifest to finalize. Compact both block signatures to the hinTS key and
aggregate signature, and put each proof in a version 1 envelope with only the state-proof field.
Both proofs must have the same signing key. Fetch both again if the key rotates between the queries.

Open the destination channel with the compact verifier that advertises
`0x01 || keccak256(raw hinTS verification key)` as its 33-byte
`Channel.trustAnchorId`. The verifier uses the 32-byte hash itself as the
channel's cryptographic anchor. If the channel was opened with the earlier
draft's raw hash ID, open a test channel with the tagged verifier.

The relay copies the full committed `trustAnchorId` into
`ClprBundleRequest.current_trust_anchor_id`. Hiero recognizes the tagged ID
and uses the key hash with its signed SHA-256 snapshot to choose a state proof
or one witness-only batch. The relay needs to decode both forms and submit the
received envelope as-is. Its next poll reports the ID from the updated channel.
Requests with an empty or untagged ID select the native WRAPS-bearing proof.

## Fixture test

This test sends compact bundles from the consensus node's sync producer through the Solidity
service's `submitBundle` entry point. It covers a matching anchor, a short rotation, two witness
batches followed by message delivery, and a SHA-256 witness from the native consensus-node vectors.
It checks that an unknown anchor, a missing witness, and a replayed witness batch leave the channel
unchanged. Requests using the native format still receive the original state proof bytes.

Use a disposable checkout of the Solidity verifier with support for witness-form Hiero bundles. Its
Foundry submodules must be initialized, and its fixtures must include `HieroWitnessSubmitBundle.t.sol`
and the rotation and witness fixture files that test reads.

Run these commands from the consensus-node checkout.

```sh
export CLPR_HIERO_INTEROP_DIR=/absolute/path/to/disposable/contracts/checkout
./gradlew :app:test --rerun --tests '*ClprHieroProofInteropTest'
(
  cd "$CLPR_HIERO_INTEROP_DIR"
  FOUNDRY_PROFILE=test forge test --match-contract HieroJavaProofInteropTest --match-test '^test_java' -vv
)
```

The Gradle test writes versioned payloads under `test/verifiers/hiero/fixtures/java-interop/v1`
and copies the Foundry test into the disposable checkout. It also generates eleven successive key
rotations with the native hinTS library and signs the original CLPR state paths under the last key.
The test supplies those inputs at the snapshot boundary and checks both witness batches with the
native reference consumer. The checked-in `native-hiero-proof-v1.sha256` manifest pins the
regenerated vectors. Foundry starts the service and verifier in a local test EVM, supplies
the initial anchor during channel bootstrap, and checks the anchor and message cursor after each
submission. The native rotation test compares the on-chain IDs with those used in the Java requests.
