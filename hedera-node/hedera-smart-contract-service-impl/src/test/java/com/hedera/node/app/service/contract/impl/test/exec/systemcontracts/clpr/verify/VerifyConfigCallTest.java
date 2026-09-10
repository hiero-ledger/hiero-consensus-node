// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.node.app.hapi.utils.blocks.NativeTssVerifier;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify.VerifyConfigCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link VerifyConfigCall}.
 *
 * <p>The {@link #invokeVerifyConfig} helper accepts the path to a serialized {@code StateProof}
 * file and returns the call's {@link Call.PricedResult}. Tests use it to drive the call against
 * either a synthesized in-memory proof (written to a temp file) or an externally supplied
 * fixture. The trust anchor used to authenticate the proof is read from the proven config's
 * {@code initial_trust_anchor} field rather than passed as a separate argument.
 */
@ExtendWith(MockitoExtension.class)
class VerifyConfigCallTest extends CallTestBase {

    /**
     * Reads {@code stateProofFile} from disk and runs a {@link VerifyConfigCall}, returning
     * the call's outcome.
     *
     * @param stateProofFile path to a file containing serialized {@code StateProof} bytes
     *                       that should decode to a {@code ClprLedgerConfiguration}
     * @param tssVerifier    plugged in to the call to check the TSS aggregate signature
     * @return the call's {@link Call.PricedResult} including response code and EVM output
     */
    @NonNull
    Call.PricedResult invokeVerifyConfig(@NonNull final Path stateProofFile, @NonNull final TssVerifier tssVerifier)
            throws IOException {
        return invokeVerifyConfig(stateProofFile, tssVerifier, true);
    }

    @NonNull
    Call.PricedResult invokeVerifyConfig(
            @NonNull final Path stateProofFile, @NonNull final TssVerifier tssVerifier, final boolean manifestAware)
            throws IOException {
        final var stateProofBytes = Files.readAllBytes(stateProofFile);
        // manifestAware routes between the V1 legacy return (config bytes only) and the V3
        // context+manifest tuple return. The V3 path uses a 32-byte channelId and an empty
        // manifest proof (these tests fail at config parsing before the manifest is reached).
        final var subject = manifestAware
                ? new VerifyConfigCall(
                        mockEnhancement(), gasCalculator, stateProofBytes, new byte[32], new byte[0], tssVerifier)
                : new VerifyConfigCall(mockEnhancement(), gasCalculator, stateProofBytes, tssVerifier);
        return subject.execute(frame);
    }

    @Test
    @DisplayName("reverts with CLPR_VERIFIER_CONFIG_FAILED when state proof bytes are malformed")
    void revertsOnMalformedProof(@TempDir final Path tempDir) throws IOException {
        final var proofFile = tempDir.resolve("malformed.proof");
        Files.write(proofFile, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});

        final var result = invokeVerifyConfig(proofFile, new NativeTssVerifier());

        assertThat(result.isViewCall()).isTrue();
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    @Disabled("Requires a real TSS-signed peer-proof2-v1.bin fixture that is not yet committed as a "
            + "test resource. Re-enable once the fixture is checked in under src/test/resources or is "
            + "synthesized in-test from the same machinery the sender path uses.")
    void testHappyPath(@TempDir final Path tempDir) throws IOException {
        final var proofFile = Paths.get("peer-proof2-v1.bin");

        final var result = invokeVerifyConfig(proofFile, new NativeTssVerifier());

        assertThat(result.isViewCall()).isTrue();
        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    }

    @Test
    @DisplayName("reverts with CLPR_VERIFIER_CONFIG_FAILED for an empty state proof file")
    void revertsOnEmptyProofFile(@TempDir final Path tempDir) throws IOException {
        final var proofFile = tempDir.resolve("empty.proof");
        Files.write(proofFile, new byte[0]);

        final var result = invokeVerifyConfig(proofFile, new NativeTssVerifier());

        assertThat(result.isViewCall()).isTrue();
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    @DisplayName("reverts through the V1 legacy code path when manifestAware=false")
    void revertsOnMalformedProofV1(@TempDir final Path tempDir) throws IOException {
        final var proofFile = tempDir.resolve("malformed-v1.proof");
        Files.write(proofFile, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});

        final var result = invokeVerifyConfig(proofFile, new NativeTssVerifier(), /*manifestAware*/ false);

        assertThat(result.isViewCall()).isTrue();
        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    // ---- V2 manifest-proof path (verifyManifest, spec §4.8) ----
    // These drive the full config + manifest verification with a stubbed TssVerifier (returns true),
    // so a synthetic single-leaf StateProof — nextPathIndex=-1 so computeRootHash accepts it — reaches
    // verifyManifest without a real TSS-signed fixture.

    private static final Bytes SERVICE_ADDR = Bytes.wrap(new byte[20]);
    private static final Bytes TRUST_ANCHOR = Bytes.wrap(new byte[] {1, 2, 3, 4});

    @Test
    @DisplayName("V2: a valid config proof + valid manifest proof verifies and returns SUCCESS")
    void verifyManifestHappyPath() {
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(2L)
                .serviceAddress(SERVICE_ADDR)
                .build();

        final var result = invokeV2(configProofBytes(testConfig()), manifestProofBytes(manifest), acceptingTss());

        assertThat(result.responseCode()).isEqualTo(SUCCESS);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
    }

    @Test
    @DisplayName("V2: manifest.version == 0 violates §4.8 → CLPR_VERIFIER_CONFIG_FAILED")
    void rejectsManifestVersionZero() {
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(0L)
                .serviceAddress(SERVICE_ADDR)
                .build();

        final var result = invokeV2(configProofBytes(testConfig()), manifestProofBytes(manifest), acceptingTss());

        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @Test
    @DisplayName("V2: manifest.service_address != config.service_address violates §4.8 → failed")
    void rejectsManifestServiceAddressMismatch() {
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(2L)
                .serviceAddress(Bytes.wrap(new byte[] {7, 7}))
                .build();

        final var result = invokeV2(configProofBytes(testConfig()), manifestProofBytes(manifest), acceptingTss());

        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
    }

    @Test
    @DisplayName("V2: malformed manifest proof bytes → CLPR_VERIFIER_CONFIG_FAILED")
    void rejectsMalformedManifestProof() {
        final var result =
                invokeV2(configProofBytes(testConfig()), new byte[] {(byte) 0xff, (byte) 0xff}, acceptingTss());

        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
    }

    @Test
    @DisplayName("V3: empty manifest proof is rejected (a non-empty proof is required) → CLPR_VERIFIER_CONFIG_FAILED")
    void emptyManifestProofRejected() {
        final var result = invokeV2(configProofBytes(testConfig()), new byte[0], acceptingTss());

        assertThat(result.responseCode()).isEqualTo(CLPR_VERIFIER_CONFIG_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
    }

    @NonNull
    private Call.PricedResult invokeV2(
            @NonNull final byte[] configProof, @NonNull final byte[] manifestProof, @NonNull final TssVerifier tss) {
        final var subject =
                new VerifyConfigCall(mockEnhancement(), gasCalculator, configProof, new byte[32], manifestProof, tss);
        return subject.execute(frame);
    }

    private static TssVerifier acceptingTss() {
        final var verifier = mock(TssVerifier.class);
        given(verifier.verifyTss(any(), any(), any())).willReturn(true);
        return verifier;
    }

    private static ClprLedgerConfiguration testConfig() {
        return ClprLedgerConfiguration.newBuilder()
                .chainId("295")
                .serviceAddress(SERVICE_ADDR)
                .initialTrustAnchor(TRUST_ANCHOR)
                .build();
    }

    private static byte[] configProofBytes(@NonNull final ClprLedgerConfiguration config) {
        return proofBytes(
                StateValue.newBuilder().clprServiceILedgerConfiguration(config).build());
    }

    private static byte[] manifestProofBytes(@NonNull final ClprEndpointManifest manifest) {
        return proofBytes(
                StateValue.newBuilder().clprServiceIEndpointManifest(manifest).build());
    }

    /**
     * Wraps a {@link StateValue} in a single-leaf {@link StateProof} that {@code computeBlockRootHash}
     * accepts: one {@code state_item_leaf} path with {@code nextPathIndex = -1}, plus a signed block
     * proof carrying a non-empty (unchecked — the TssVerifier is stubbed) block signature.
     */
    private static byte[] proofBytes(@NonNull final StateValue stateValue) {
        final var leaf = StateItem.PROTOBUF.toBytes(
                StateItem.newBuilder().value(stateValue).build());
        final var path =
                MerklePath.newBuilder().stateItemLeaf(leaf).nextPathIndex(-1).build();
        final var proof = StateProof.newBuilder()
                .paths(path)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {9, 9, 9, 9}))
                        .build())
                .build();
        return StateProof.PROTOBUF.toBytes(proof).toByteArray();
    }
}
