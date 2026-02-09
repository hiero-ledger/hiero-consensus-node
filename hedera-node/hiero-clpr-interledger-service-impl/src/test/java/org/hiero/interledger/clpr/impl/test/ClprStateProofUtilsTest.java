// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.test;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.hapi.utils.blocks.MerklePathBuilder;
import com.hedera.node.app.hapi.utils.blocks.StateProofBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.hapi.interledger.state.clpr.ClprEndpoint;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClprStateProofUtils}.
 */
class ClprStateProofUtilsTest extends ClprTestBase {

    @Test
    void testExtractConfiguration_ValidStateProof() {
        // Create a valid CLPR configuration
        final var ledgerId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.wrap("test-ledger".getBytes()))
                .build();
        final var config =
                ClprLedgerConfiguration.newBuilder().ledgerId(ledgerId).build();

        final var stateProof = buildLocalClprStateProofWrapper(config);

        // Extract and verify
        final var extracted = ClprStateProofUtils.extractConfiguration(stateProof);
        assertNotNull(extracted);
        assertEquals(config.ledgerId(), extracted.ledgerId());
    }

    @Test
    void testExtractConfiguration_InvalidSignature() {
        // Create configuration with mismatched signature
        final var config = ClprLedgerConfiguration.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap("test".getBytes()))
                        .build())
                .build();

        final var validProof = buildLocalClprStateProofWrapper(config);
        final var stateProof = validProof
                .copyBuilder()
                .signedBlockProof(validProof
                        .signedBlockProofOrThrow()
                        .copyBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                        .build())
                .build();

        // Signature verification should fail
        assertFalse(ClprStateProofUtils.validateStateProof(stateProof));
        // Extraction is orthogonal and should still parse the payload
        final var extracted = assertDoesNotThrow(() -> ClprStateProofUtils.extractConfiguration(stateProof));
        assertEquals(config.ledgerId(), extracted.ledgerId());
    }

    @Test
    void testExtractConfiguration_EmptyPaths() {
        // Create state proof with no paths
        final var stateProof = StateProof.newBuilder().build();

        // Should throw due to empty paths (StateProofVerifier throws IllegalStateException)
        assertThrows(IllegalStateException.class, () -> {
            ClprStateProofUtils.extractConfiguration(stateProof);
        });
    }

    @Test
    void testExtractConfiguration_NoLeafInPath() {
        // Create state proof with path but no leaf
        final var pathBuilder = new MerklePathBuilder();
        // Don't set a leaf
        pathBuilder.setHash(Bytes.wrap(new byte[48])); // Just set a hash

        final var stateProof =
                StateProofBuilder.newBuilder().addMerklePath(pathBuilder).build();

        // Should throw due to missing leaf (StateProofVerifier throws IllegalStateException for structure issues)
        assertThrows(IllegalStateException.class, () -> {
            ClprStateProofUtils.extractConfiguration(stateProof);
        });
    }

    @Test
    void testExtractConfiguration_MalformedConfigurationBytes() {
        // Create leaf with invalid configuration bytes
        final var pathBuilder = new MerklePathBuilder()
                .setStateItemLeaf(Bytes.wrap(new byte[] {1, 2, 3, 4, 5})); // Invalid protobuf

        final var stateProof =
                StateProofBuilder.newBuilder().addMerklePath(pathBuilder).build();

        // Should throw due to parse failure
        assertThrows(IllegalStateException.class, () -> {
            ClprStateProofUtils.extractConfiguration(stateProof);
        });
    }

    @Test
    void testExtractConfiguration_NoStateItemInLeaf() {
        // Create leaf without state item
        final var pathBuilder = new MerklePathBuilder().setTimestampLeaf(Bytes.wrap(new byte[] {1, 2, 3}));

        final var stateProof =
                StateProofBuilder.newBuilder().addMerklePath(pathBuilder).build();

        // Should throw due to missing state item
        assertThrows(IllegalArgumentException.class, () -> {
            ClprStateProofUtils.extractConfiguration(stateProof);
        });
    }

    @Test
    void testValidateStateProof_ValidProof() {
        // Create valid state proof
        final var config = ClprLedgerConfiguration.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap("test".getBytes()))
                        .build())
                .build();

        final var stateProof = buildLocalClprStateProofWrapper(config);

        // Should return true
        assertTrue(ClprStateProofUtils.validateStateProof(stateProof));
    }

    @Test
    void testValidateStateProof_InvalidSignature() {
        // Create state proof with wrong signature
        final var config = ClprLedgerConfiguration.newBuilder()
                .ledgerId(ClprLedgerId.newBuilder()
                        .ledgerId(Bytes.wrap("test".getBytes()))
                        .build())
                .build();

        final var validProof = buildLocalClprStateProofWrapper(config);
        final var stateProof = validProof
                .copyBuilder()
                .signedBlockProof(validProof
                        .signedBlockProofOrThrow()
                        .copyBuilder()
                        .blockSignature(Bytes.wrap(new byte[] {9, 9, 9}))
                        .build())
                .build();

        // Should return false
        assertFalse(ClprStateProofUtils.validateStateProof(stateProof));
    }

    @Test
    void testExtractConfiguration_CompleteRoundTripAllFields() {
        // Create complete configuration with ALL fields populated
        final var ledgerId = ClprLedgerId.newBuilder()
                .ledgerId(Bytes.wrap("complete-ledger-id".getBytes()))
                .build();

        final var timestamp =
                Timestamp.newBuilder().seconds(1234567890L).nanos(123456789).build();

        final var endpoint1 = ClprEndpoint.newBuilder()
                .endpoint(ServiceEndpoint.newBuilder()
                        .ipAddressV4(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                        .port(50211)
                        .build())
                .signingCertificate(Bytes.wrap("cert-1-data".getBytes()))
                .nodeAccountId(AccountID.newBuilder().accountNum(1001L).build())
                .build();

        final var endpoint2 = ClprEndpoint.newBuilder()
                .endpoint(ServiceEndpoint.newBuilder()
                        .ipAddressV4(Bytes.wrap(new byte[] {(byte) 192, (byte) 168, 1, 100}))
                        .port(50212)
                        .build())
                .signingCertificate(Bytes.wrap("cert-2-data".getBytes()))
                .nodeAccountId(AccountID.newBuilder().accountNum(1002L).build())
                .build();

        final var originalConfig = ClprLedgerConfiguration.newBuilder()
                .ledgerId(ledgerId)
                .timestamp(timestamp)
                .endpoints(List.of(endpoint1, endpoint2))
                .build();

        final var stateProof = buildLocalClprStateProofWrapper(originalConfig);

        // Extract configuration back
        final var extractedConfig = ClprStateProofUtils.extractConfiguration(stateProof);

        // Verify ALL fields match exactly
        assertNotNull(extractedConfig);

        // Verify ledger_id
        assertNotNull(extractedConfig.ledgerId());
        assertEquals(
                originalConfig.ledgerId().ledgerId(), extractedConfig.ledgerId().ledgerId());

        // Verify timestamp
        assertNotNull(extractedConfig.timestamp());
        assertEquals(
                originalConfig.timestamp().seconds(),
                extractedConfig.timestamp().seconds());
        assertEquals(
                originalConfig.timestamp().nanos(), extractedConfig.timestamp().nanos());

        // Verify endpoints
        assertNotNull(extractedConfig.endpoints());
        assertEquals(2, extractedConfig.endpoints().size());

        // Verify first endpoint
        final var extractedEndpoint1 = extractedConfig.endpoints().get(0);
        assertEquals(
                endpoint1.endpoint().ipAddressV4(),
                extractedEndpoint1.endpoint().ipAddressV4());
        assertEquals(endpoint1.endpoint().port(), extractedEndpoint1.endpoint().port());
        assertEquals(endpoint1.signingCertificate(), extractedEndpoint1.signingCertificate());
        assertEquals(
                endpoint1.nodeAccountIdOrElse(AccountID.DEFAULT),
                extractedEndpoint1.nodeAccountIdOrElse(AccountID.DEFAULT));

        // Verify second endpoint
        final var extractedEndpoint2 = extractedConfig.endpoints().get(1);
        assertEquals(
                endpoint2.endpoint().ipAddressV4(),
                extractedEndpoint2.endpoint().ipAddressV4());
        assertEquals(endpoint2.endpoint().port(), extractedEndpoint2.endpoint().port());
        assertEquals(endpoint2.signingCertificate(), extractedEndpoint2.signingCertificate());
        assertEquals(
                endpoint2.nodeAccountIdOrElse(AccountID.DEFAULT),
                extractedEndpoint2.nodeAccountIdOrElse(AccountID.DEFAULT));
    }
}
