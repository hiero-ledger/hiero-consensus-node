// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyBundleCall;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Objects;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VerifyBundleCall}, in two flavours:
 *
 * <ul>
 *   <li>{@link CapturedFixtureReplay} — an offline replay of the verifier path against checked-in
 *       {@code .bin} fixtures captured from a real cross-ledger run.</li>
 *   <li>{@link ManifestOnlyBranch} — drives {@link VerifyBundleCall#execute} with a stubbed
 *       {@link TssVerifier} and synthetic {@link StateProof}s to cover the manifest-only recovery
 *       branch (spec §8.1.4).</li>
 * </ul>
 */
class VerifyBundleCallTest {

    /**
     * Offline replay of the {@code VerifyBundle} verifier path against checked-in fixtures captured
     * from a real cross-ledger run. The fixtures live next to this test on the classpath:
     *
     * <ul>
     *   <li>{@code stateProof.bin} — the raw {@code bundlePayload} bytes (serialized {@code StateProof}).</li>
     *   <li>{@code trustAnchor.bin} — the 32-byte peer ledger id that signed the bundle
     *       (i.e. the {@code Channel.trust_anchor} value the verifier was invoked with).</li>
     * </ul>
     *
     * <p>The test parses the proof and runs the same structural + TSS checks {@link VerifyBundleCall}
     * performs in production. A failure here means either (a) the checked-in fixture pair is
     * internally inconsistent (e.g. proof from one ledger paired with the other ledger's id), or
     * (b) something in the verifier / TSS chain has regressed in a way that breaks previously-valid
     * proofs.
     *
     * <p>To refresh the fixtures, temporarily re-enable the {@code dumpBytesFailOpen} calls in
     * {@code VerifyBundleCall.execute(...)}, run an end-to-end flow, then copy the resulting files
     * from {@code verification-inputs/} into this test's resource package.
     */
    @Nested
    class CapturedFixtureReplay {

        private static final String PROOF_RESOURCE = "stateProof.bin";
        private static final String TRUST_ANCHOR_RESOURCE = "trustAnchor.bin";

        @Test
        @DisplayName("checked-in stateProof.bin verifies against checked-in trustAnchor.bin")
        void capturedProofVerifiesAgainstCapturedTrustAnchor() throws IOException, ParseException {
            final byte[] proofBytes = loadResource(PROOF_RESOURCE);
            final byte[] trustAnchor = loadResource(TRUST_ANCHOR_RESOURCE);
            final var trustAnchorBytes = Bytes.wrap(trustAnchor);

            // 1) Parse the StateProof.
            final StateProof proof =
                    StateProof.PROTOBUF.parse(Bytes.wrap(proofBytes).toReadableSequentialData());

            // 2) Structural checks (mirror VerifyBundleCall.execute lines 121-145).
            assertThat(proof.hasSignedBlockProof())
                    .as("captured proof is missing signedBlockProof")
                    .isTrue();
            final var signature = proof.signedBlockProofOrThrow().blockSignature();
            assertThat(signature.length())
                    .as("captured proof has empty blockSignature")
                    .isPositive();

            byte[] blockRootHash = null;
            for (final var path : proof.paths()) {
                if (!path.hasStateItemLeaf()) {
                    continue;
                }
                blockRootHash = StateProofVerifier.computeBlockRootHashFromPath(path);
                break;
            }
            assertThat(blockRootHash)
                    .as("captured proof has no state-item-leaf path to root a block hash from")
                    .isNotNull();

            // 3) TSS aggregate-signature check against the captured trust anchor.
            //    Equivalent to VerifyBundleCall.execute line 120:
            //        tssVerifier.verifyTss(trustAnchorBytes, signature, Bytes.wrap(blockRootHash))
            final var ok = new NativeTssVerifier().verifyTss(trustAnchorBytes, signature, Bytes.wrap(blockRootHash));

            assertThat(ok)
                    .as(
                            "TSS verification FAILED: captured stateProof.bin is not signed by the "
                                    + "ledger identified by trustAnchor.bin (0x%s). The most common cause is "
                                    + "a mismatched capture — e.g. stateProof.bin from one ledger's outbound "
                                    + "bundle paired with the other ledger's id.",
                            trustAnchorBytes.toHex())
                    .isTrue();
        }

        private byte[] loadResource(final String name) throws IOException {
            try (final InputStream in = Objects.requireNonNull(
                    getClass().getResourceAsStream(name),
                    "missing test resource: " + name + " (expected next to "
                            + VerifyBundleCallTest.class.getSimpleName() + " on the classpath)")) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * Covers the manifest-only recovery branch of {@link VerifyBundleCall} (spec §8.1.4): a
     * state-proven endpoint manifest with no channel leaf.
     *
     * <p>Each test drives {@link VerifyBundleCall#execute} with a stubbed {@link TssVerifier}
     * (returns true) so a synthetic {@link StateProof} reaches the extraction logic without a real
     * TSS-signed fixture — the same approach as {@code VerifyConfigCallTest}. The single-leaf cases
     * use a {@code nextPathIndex = -1} path that the real {@link StateProofVerifier} accepts as
     * self-rooting; the multi-leaf case stubs {@link StateProofVerifier} so two independent leaves
     * both verify against one block root, isolating the {@code messages.isEmpty()} guard.
     */
    @Nested
    class ManifestOnlyBranch extends CallTestBase {

        private static final byte[] TRUST_ANCHOR = {1, 2, 3, 4};
        private static final Bytes SERVICE_ADDR = Bytes.wrap(new byte[20]);

        @Test
        @DisplayName("manifest-only bundle (no channel leaf, flag on) → V3 SUCCESS with absent metadata + manifest")
        void manifestOnlyBundleReturnsV3SuccessWhenFlagOn() {
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(2L)
                    .serviceAddress(SERVICE_ADDR)
                    .build();
            stubManifestFlag(true);

            final var result = subject(singleLeafProof(manifestLeaf(manifest))).execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);

            final var decoded = ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat(decoded.size()).isEqualTo(5);
            // Member 0: absent-metadata sentinel — nextMessageId == 0.
            final Tuple metaTuple = decoded.get(0);
            assertThat(ClprVerifierAbi.isMetadataAbsent(metaTuple)).isTrue();
            // Members 1-3: no messages, no trust-anchor rotation.
            assertThat((byte[][]) decoded.get(1)).isEmpty();
            assertThat((byte[]) decoded.get(2)).isEmpty();
            assertThat((byte[]) decoded.get(3)).isEmpty();
            // Member 4: the proven manifest (version 2, matching service address).
            final Tuple manifestStruct = decoded.get(4);
            assertThat(((BigInteger) manifestStruct.get(0)).longValue()).isEqualTo(2L);
            assertThat((byte[]) manifestStruct.get(1)).isEqualTo(SERVICE_ADDR.toByteArray());
        }

        @Test
        @DisplayName("manifest-only bundle with the endpoint-manifest flag off → CLPR_BUNDLE_VERIFICATION_FAILED")
        void manifestOnlyBundleRejectedWhenFlagOff() {
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(2L)
                    .serviceAddress(SERVICE_ADDR)
                    .build();
            stubManifestFlag(false);

            final var result = subject(singleLeafProof(manifestLeaf(manifest))).execute(frame);

            assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        }

        @Test
        @DisplayName("no channel leaf + a message leaf alongside the manifest → rejected (messages.isEmpty() guard)")
        void bundleWithMessageAndManifestButNoChannelRejected() {
            final var manifest = ClprEndpointManifest.newBuilder()
                    .version(2L)
                    .serviceAddress(SERVICE_ADDR)
                    .build();
            final var message = ClprMessageValue.newBuilder()
                    .runningHashAfterProcessing(Bytes.wrap(new byte[32]))
                    .build();
            stubManifestFlag(true);

            // Two independent leaves cannot share a self-rooting nextPathIndex=-1 root, so stub the
            // path verifier: both leaves verify against one block root and reach the branch together.
            // Without the messages.isEmpty() guard this would wrongly return manifestOnlySuccess.
            try (final var verifier = mockStatic(StateProofVerifier.class)) {
                verifier.when(() -> StateProofVerifier.computeBlockRootHashFromPath(any()))
                        .thenReturn(new byte[32]);
                verifier.when(() -> StateProofVerifier.verifyPath(any(), any())).thenReturn(true);

                final var result = subject(twoLeafProof(manifestLeaf(manifest), messageLeaf(message)))
                        .execute(frame);

                assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
                assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
            }
        }

        // ---- helpers ----

        private VerifyBundleCall subject(@NonNull final byte[] bundlePayload) {
            return new VerifyBundleCall(mockEnhancement(), gasCalculator, bundlePayload, TRUST_ANCHOR, acceptingTss());
        }

        /** Stubs {@code configOf(frame)} to a config with the given endpoint-manifest flag value. */
        private void stubManifestFlag(final boolean enabled) {
            final Configuration config = HederaTestConfigBuilder.create()
                    .withValue("clpr.endpointManifestEnabled", enabled)
                    .getOrCreateConfig();
            given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
            given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        }
    }

    private static TssVerifier acceptingTss() {
        final var verifier = mock(TssVerifier.class);
        given(verifier.verifyTss(any(), any(), any())).willReturn(true);
        return verifier;
    }

    private static Bytes manifestLeaf(@NonNull final ClprEndpointManifest manifest) {
        return leaf(
                StateValue.newBuilder().clprServiceIEndpointManifest(manifest).build());
    }

    private static Bytes messageLeaf(@NonNull final ClprMessageValue message) {
        return leaf(StateValue.newBuilder().clprServiceIMessageQueue(message).build());
    }

    private static Bytes leaf(@NonNull final StateValue stateValue) {
        return StateItem.PROTOBUF.toBytes(
                StateItem.newBuilder().value(stateValue).build());
    }

    /**
     * A single-leaf {@link StateProof} that {@code computeBlockRootHashFromPath} accepts: one
     * {@code state_item_leaf} path with {@code nextPathIndex = -1}, plus a signed block proof
     * carrying a non-empty (unchecked — the TssVerifier is stubbed) block signature.
     */
    private static byte[] singleLeafProof(@NonNull final Bytes leaf) {
        final var path =
                MerklePath.newBuilder().stateItemLeaf(leaf).nextPathIndex(-1).build();
        return proofOf(path);
    }

    /** A two-leaf {@link StateProof}; use only with a stubbed {@link StateProofVerifier}. */
    private static byte[] twoLeafProof(@NonNull final Bytes first, @NonNull final Bytes second) {
        final var p1 =
                MerklePath.newBuilder().stateItemLeaf(first).nextPathIndex(-1).build();
        final var p2 =
                MerklePath.newBuilder().stateItemLeaf(second).nextPathIndex(-1).build();
        return proofOf(p1, p2);
    }

    private static byte[] proofOf(@NonNull final MerklePath... paths) {
        final var proof = StateProof.newBuilder()
                .paths(paths)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {9, 9, 9, 9}))
                        .build())
                .build();
        return StateProof.PROTOBUF.toBytes(proof).toByteArray();
    }
}
