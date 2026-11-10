# Hiero proof interoperability with an EVM verifier

Complete the destination channel with the compact Solidity Hiero verifier and its signed
Hiero config and endpoint manifest proofs. From the configured Hiero CLI, retrieve the proofs:

```sh
yahcli clpr get-ledger-configuration --proof-path config.pb
yahcli clpr get-endpoint-manifest --proof-path manifest.pb
```

Wait for the endpoint manifest to finalize. Compact both block signatures to the hinTS key and
aggregate signature, and put each proof in a version 1 envelope with only the state-proof field.
Both proofs must have the same signing key. Fetch both again if the key rotates between the queries.

Configure the streaming EVM relay with `HieroCompact` for the source's CAIP-2 chain ID.

```yaml
peerProofTypes:
  "hiero:localnet": HieroCompact
```

The relay advertises `CLPR_BUNDLE_FORMAT_HIERO_PROOF_V1` in field 5 (`bundle_format`) of its opening
`ClprBundleRequest` and supplies the destination's committed `trustAnchorId` as
`current_trust_anchor_id`. The source uses its signed SHA-256 snapshot and the
adoption records from #28085 to choose a state proof or one witness-only batch.
The relay submits that payload as received and reports the updated anchor on its
next poll. A request that omits field 5 selects the native WRAPS-bearing proof.

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
submission.
