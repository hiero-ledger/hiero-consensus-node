// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the shared metadata tuple helpers on {@link ClprVerifierAbi}. */
class ClprVerifierAbiTest {

    @Test
    @DisplayName("absentMetadataTuple is the zero sentinel with full 32-byte hashes, recognised as absent")
    void absentMetadataTupleIsRecognisedAsAbsent() {
        final Tuple absent = ClprVerifierAbi.absentMetadataTuple();
        assertThat((BigInteger) absent.get(0)).isEqualTo(BigInteger.ZERO);
        // Full 32-byte zero arrays (not empty) so the sentinel encodes against the bytes32 members.
        assertThat((byte[]) absent.get(1)).hasSize(32);
        assertThat((byte[]) absent.get(3)).hasSize(32);
        // Round-trips through the return tuple's encoder.
        assertThat(ClprVerifierAbi.VERIFY_BUNDLE_RETURN.encode(Tuple.of(
                        absent,
                        new byte[0][],
                        new byte[0],
                        new byte[0],
                        ClprVerifierAbi.manifestStructTuple(
                                com.hedera.hapi.node.state.clpr.ClprEndpointManifest.DEFAULT))))
                .isNotNull();
        assertThat(ClprVerifierAbi.isMetadataAbsent(absent)).isTrue();
    }

    @Test
    @DisplayName("isMetadataAbsent keys only on nextMessageId — any non-zero value is a real bundle")
    void isMetadataAbsentKeysOnNextMessageId() {
        final Tuple present = Tuple.of(BigInteger.ONE, new byte[32], BigInteger.ZERO, new byte[32], 0, BigInteger.ZERO);
        assertThat(ClprVerifierAbi.isMetadataAbsent(present)).isFalse();
        // The other members being zero does NOT make it absent — only nextMessageId is the discriminator.
        final Tuple genesisReal =
                Tuple.of(BigInteger.valueOf(5L), new byte[32], BigInteger.ZERO, new byte[32], 0, BigInteger.ZERO);
        assertThat(ClprVerifierAbi.isMetadataAbsent(genesisReal)).isFalse();
    }

    @Test
    @DisplayName("metadataTuple encodes all six members, including endpoint_manifest_version")
    void metadataTupleEncodesAllSixMembers() {
        final var meta = ClprQueueMetadata.newBuilder()
                .nextMessageId(3L)
                .sentRunningHash(Bytes.wrap(new byte[32]))
                .receivedMessageId(2L)
                .receivedRunningHash(Bytes.wrap(new byte[32]))
                .status(ClprChannelStatus.ACTIVE)
                .endpointManifestVersion(4L)
                .build();

        final Tuple tuple = ClprVerifierAbi.metadataTuple(meta);

        assertThat(tuple).isNotNull();
        assertThat(tuple.size()).isEqualTo(6);
        assertThat((BigInteger) tuple.get(0)).isEqualTo(BigInteger.valueOf(3L));
        assertThat((BigInteger) tuple.get(2)).isEqualTo(BigInteger.valueOf(2L));
        assertThat((Integer) tuple.get(4)).isEqualTo(ClprChannelStatus.ACTIVE.protoOrdinal());
        assertThat((BigInteger) tuple.get(5)).isEqualTo(BigInteger.valueOf(4L));
    }

    @Test
    @DisplayName("metadataTuple rejects metadata the ABI encoder cannot represent")
    void metadataTupleRejectsUnencodableMetadata() {
        final var valid = ClprQueueMetadata.newBuilder()
                .nextMessageId(3L)
                .sentRunningHash(Bytes.wrap(new byte[32]))
                .receivedRunningHash(Bytes.wrap(new byte[32]))
                .status(ClprChannelStatus.ACTIVE)
                .build();

        // A proto uint64 >= 2^63 parses to a negative long.
        assertThat(ClprVerifierAbi.metadataTuple(
                        valid.copyBuilder().endpointManifestVersion(-1L).build()))
                .isNull();
        assertThat(ClprVerifierAbi.metadataTuple(
                        valid.copyBuilder().nextMessageId(-1L).build()))
                .isNull();
        // Running hashes must be exactly 32 bytes (bytes32).
        assertThat(ClprVerifierAbi.metadataTuple(
                        valid.copyBuilder().sentRunningHash(Bytes.EMPTY).build()))
                .isNull();
    }
}
