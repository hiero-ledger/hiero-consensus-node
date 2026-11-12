// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.MAX_STORED_MANIFEST_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.boundedManifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClprPeerManifestPolicyTest {
    @Test
    void countLimitAndAuthenticatedManifestFieldsArePreserved() {
        final var manifest = manifest(Collections.nCopies(10, ClprEndpoint.DEFAULT));
        final var bounded = boundedManifest(manifest, 2);
        assertThat(bounded.endpoints()).hasSize(2);
        assertThat(bounded.version()).isEqualTo(manifest.version());
        assertThat(bounded.serviceAddress()).isEqualTo(manifest.serviceAddress());
    }

    @Test
    void countLimitAboveEndpointCountRetainsEveryEndpointInDeclaredOrder() {
        final var first =
                ClprEndpoint.newBuilder().accountId(Bytes.wrap(new byte[] {1})).build();
        final var second =
                ClprEndpoint.newBuilder().accountId(Bytes.wrap(new byte[] {2})).build();
        final var manifest = manifest(List.of(first, second));

        assertThat(boundedManifest(manifest, 100)).isSameAs(manifest);
        assertThat(boundedManifest(manifest, 100).endpoints()).containsExactly(first, second);
    }

    @Test
    void zeroCountLimitStillBoundsSerializedStorageIncludingEmptyEntries() {
        final var bounded =
                boundedManifest(manifest(Collections.nCopies(MAX_STORED_MANIFEST_BYTES, ClprEndpoint.DEFAULT)), 0);
        assertThat(bounded.endpoints()).hasSizeLessThan(MAX_STORED_MANIFEST_BYTES);
        assertThat(ClprEndpointManifest.PROTOBUF.measureRecord(bounded)).isLessThanOrEqualTo(MAX_STORED_MANIFEST_BYTES);
    }

    @Test
    void oneOversizedCertificateCannotBypassCountLimit() {
        final var oversized = ClprEndpoint.newBuilder()
                .tlsCertificate(Bytes.wrap(new byte[MAX_STORED_MANIFEST_BYTES]))
                .build();
        final var bounded =
                boundedManifest(manifest(List.of(ClprEndpoint.DEFAULT, oversized, ClprEndpoint.DEFAULT)), 100);
        assertThat(bounded.endpoints()).containsExactly(ClprEndpoint.DEFAULT);
    }

    @Test
    void accountAndAddressBytesAreAlsoBounded() {
        final var hugeAccount = ClprEndpoint.newBuilder()
                .accountId(Bytes.wrap(new byte[MAX_STORED_MANIFEST_BYTES]))
                .build();
        final var hugeAddress = ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder().ipAddress("a".repeat(MAX_STORED_MANIFEST_BYTES)))
                .build();
        assertThat(boundedManifest(manifest(List.of(hugeAccount)), 0).endpoints())
                .isEmpty();
        assertThat(boundedManifest(manifest(List.of(hugeAddress)), 0).endpoints())
                .isEmpty();
        assertThatThrownBy(() -> boundedManifest(
                        ClprEndpointManifest.newBuilder()
                                .serviceAddress(Bytes.wrap(new byte[MAX_STORED_MANIFEST_BYTES]))
                                .build(),
                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsExactCodecBoundaryAndDropsOneByteOver() {
        int certificateBytes = MAX_STORED_MANIFEST_BYTES;
        ClprEndpointManifest manifest;
        do {
            manifest = manifest(List.of(ClprEndpoint.newBuilder()
                    .tlsCertificate(Bytes.wrap(new byte[--certificateBytes]))
                    .build()));
        } while (ClprEndpointManifest.PROTOBUF.measureRecord(manifest) > MAX_STORED_MANIFEST_BYTES);
        assertThat(ClprEndpointManifest.PROTOBUF.measureRecord(manifest)).isEqualTo(MAX_STORED_MANIFEST_BYTES);
        assertThat(boundedManifest(manifest, 0)).isEqualTo(manifest);
        final var tooLarge = manifest(List.of(ClprEndpoint.newBuilder()
                .tlsCertificate(Bytes.wrap(new byte[certificateBytes + 1]))
                .build()));
        assertThat(boundedManifest(tooLarge, 0).endpoints()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 255})
    void twoByteEndpointLengthPrefixCountsTowardTheStorageCeiling(final int serializedEndpointBytes) {
        int certificateBytes = serializedEndpointBytes;
        ClprEndpoint endpoint;
        do {
            endpoint = ClprEndpoint.newBuilder()
                    .tlsCertificate(Bytes.wrap(new byte[--certificateBytes]))
                    .build();
        } while (ClprEndpoint.PROTOBUF.measureRecord(endpoint) > serializedEndpointBytes);
        assertThat(ClprEndpoint.PROTOBUF.measureRecord(endpoint)).isEqualTo(serializedEndpointBytes);

        // One byte remains after the first entry. An empty second entry needs two bytes
        // (tag and zero length), so undercounting the first entry's length prefix admits it incorrectly.
        final var expected = manifestWithSerializedSize(List.of(endpoint), MAX_STORED_MANIFEST_BYTES - 1);
        final var tooLarge = expected.copyBuilder()
                .endpoints(List.of(endpoint, ClprEndpoint.DEFAULT))
                .build();
        assertThat(ClprEndpointManifest.PROTOBUF.toBytes(tooLarge).length()).isEqualTo(MAX_STORED_MANIFEST_BYTES + 1);

        final var bounded = boundedManifest(tooLarge, 100);
        assertThat(bounded).isEqualTo(expected);
        assertThat(ClprEndpointManifest.PROTOBUF.toBytes(bounded).length()).isEqualTo(MAX_STORED_MANIFEST_BYTES - 1);
    }

    @Test
    void framingExactlyAtTheCeilingIsAllowedButOneByteOverIsRejected() {
        final var exact = manifestWithSerializedSize(List.of(), MAX_STORED_MANIFEST_BYTES);
        assertThat(boundedManifest(exact, 100)).isSameAs(exact);

        final var tooLarge = exact.copyBuilder()
                .serviceAddress(Bytes.wrap(new byte[(int) exact.serviceAddress().length() + 1]))
                .build();
        assertThat(ClprEndpointManifest.PROTOBUF.toBytes(tooLarge).length()).isEqualTo(MAX_STORED_MANIFEST_BYTES + 1);
        assertThatThrownBy(() -> boundedManifest(tooLarge, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Peer manifest framing exceeds storage limit");
    }

    private static ClprEndpointManifest manifestWithSerializedSize(
            final List<ClprEndpoint> endpoints, final int serializedBytes) {
        int addressBytes = serializedBytes;
        ClprEndpointManifest manifest;
        do {
            manifest = manifest(endpoints)
                    .copyBuilder()
                    .serviceAddress(Bytes.wrap(new byte[--addressBytes]))
                    .build();
        } while (ClprEndpointManifest.PROTOBUF.measureRecord(manifest) > serializedBytes);
        assertThat(ClprEndpointManifest.PROTOBUF.toBytes(manifest).length()).isEqualTo(serializedBytes);
        return manifest;
    }

    private static ClprEndpointManifest manifest(final List<ClprEndpoint> endpoints) {
        return ClprEndpointManifest.newBuilder()
                .version(7)
                .serviceAddress(Bytes.wrap(new byte[20]))
                .endpoints(endpoints)
                .build();
    }
}
