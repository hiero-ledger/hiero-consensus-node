// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.MAX_CHAIN_ID_LENGTH;
import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.MAX_STORED_MANIFEST_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.boundedManifest;
import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.isValidChainId;
import static com.hedera.node.app.service.clpr.impl.ClprPeerManifestPolicy.isValidHost;
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
    private static final ClprEndpoint ENDPOINT = endpointWithHost("10.0.0.1");

    @Test
    void countLimitAndAuthenticatedManifestFieldsArePreserved() {
        final var manifest = manifest(Collections.nCopies(10, ENDPOINT));
        final var bounded = boundedManifest(manifest, 2);
        assertThat(bounded.endpoints()).hasSize(2);
        assertThat(bounded.version()).isEqualTo(manifest.version());
        assertThat(bounded.serviceAddress()).isEqualTo(manifest.serviceAddress());
    }

    @Test
    void countLimitAboveEndpointCountRetainsEveryEndpointInDeclaredOrder() {
        final var first =
                ENDPOINT.copyBuilder().accountId(Bytes.wrap(new byte[] {1})).build();
        final var second =
                ENDPOINT.copyBuilder().accountId(Bytes.wrap(new byte[] {2})).build();
        final var manifest = manifest(List.of(first, second));

        assertThat(boundedManifest(manifest, 100)).isSameAs(manifest);
        assertThat(boundedManifest(manifest, 100).endpoints()).containsExactly(first, second);
    }

    @Test
    void zeroCountLimitStillBoundsSerializedStorage() {
        final var bounded = boundedManifest(manifest(Collections.nCopies(MAX_STORED_MANIFEST_BYTES, ENDPOINT)), 0);
        assertThat(bounded.endpoints()).hasSizeLessThan(MAX_STORED_MANIFEST_BYTES);
        assertThat(ClprEndpointManifest.PROTOBUF.measureRecord(bounded)).isLessThanOrEqualTo(MAX_STORED_MANIFEST_BYTES);
    }

    @Test
    void oneOversizedCertificateCannotBypassCountLimit() {
        final var oversized = ENDPOINT.copyBuilder()
                .tlsCertificate(Bytes.wrap(new byte[MAX_STORED_MANIFEST_BYTES]))
                .build();
        final var bounded = boundedManifest(manifest(List.of(ENDPOINT, oversized, ENDPOINT)), 100);
        assertThat(bounded.endpoints()).containsExactly(ENDPOINT);
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
            manifest = manifest(List.of(ENDPOINT.copyBuilder()
                    .tlsCertificate(Bytes.wrap(new byte[--certificateBytes]))
                    .build()));
        } while (ClprEndpointManifest.PROTOBUF.measureRecord(manifest) > MAX_STORED_MANIFEST_BYTES);
        assertThat(ClprEndpointManifest.PROTOBUF.measureRecord(manifest)).isEqualTo(MAX_STORED_MANIFEST_BYTES);
        assertThat(boundedManifest(manifest, 0)).isEqualTo(manifest);
        final var tooLarge = manifest(List.of(ENDPOINT.copyBuilder()
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
            endpoint = ENDPOINT.copyBuilder()
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "10.0.0.1",
                "2001:db8::1",
                "::ffff:10.0.0.1",
                "node1.example.com",
                "NODE1.EXAMPLE.COM",
                "example.com.",
                "localhost"
            })
    void wellFormedHostsAreValid(final String host) {
        assertThat(isValidHost(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "\u0000",
                "10.0.0.1\u0000",
                "peer\u0000.example.com",
                "peer.example.com\n",
                " 10.0.0.1",
                "fe80::1%eth0",
                "fe80::1%1",
                "peer_1.example.com",
                "pëer.example.com",
                "10.0.0.1:50211",
                "-peer.example.com",
                "[::1]",
                "1.2.3",
                "010.0.0.1",
                "0x7f.0.0.1"
            })
    void malformedHostsAreInvalid(final String host) {
        assertThat(isValidHost(host)).isFalse();
    }

    @Test
    void hostLengthIsBoundedByDnsLimit() {
        // Four labels of up to 63 characters separated by dots: 253 characters is the DNS maximum.
        final var maxLength = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(maxLength).hasSize(253);
        assertThat(isValidHost(maxLength)).isTrue();
        assertThat(isValidHost(maxLength + "d")).isFalse();
    }

    @Test
    void retainedEndpointWithNulHostRejectsTheManifest() {
        final var nul = endpointWithHost("\u0000");
        assertThatThrownBy(() -> boundedManifest(manifest(List.of(ENDPOINT, nul)), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Peer manifest endpoint 1 host is not an IP address or DNS hostname");
    }

    @Test
    void retainedEndpointWithoutServiceEndpointRejectsTheManifest() {
        assertThatThrownBy(() -> boundedManifest(manifest(List.of(ENDPOINT, ClprEndpoint.DEFAULT)), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Peer manifest endpoint 1 has no service endpoint");
    }

    @Test
    void endpointDroppedByCountLimitIsNotValidated() {
        final var bounded =
                boundedManifest(manifest(List.of(ENDPOINT, endpointWithHost("\u0000"), ClprEndpoint.DEFAULT)), 1);
        assertThat(bounded.endpoints()).containsExactly(ENDPOINT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hedera:testnet", "eip155:295", "sei:atlantic-2", "besu:qbft:testnet"})
    void wellFormedChainIdsAreValid(final String chainId) {
        assertThat(isValidChainId(chainId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hedera:\u0000testnet", "hedera:testnet\n", "hedera testnet", "hedera:tëstnet"})
    void malformedChainIdsAreInvalid(final String chainId) {
        assertThat(isValidChainId(chainId)).isFalse();
    }

    @Test
    void chainIdLengthIsBounded() {
        assertThat(isValidChainId("a".repeat(MAX_CHAIN_ID_LENGTH))).isTrue();
        assertThat(isValidChainId("a".repeat(MAX_CHAIN_ID_LENGTH + 1))).isFalse();
    }

    private static ClprEndpoint endpointWithHost(final String host) {
        return ClprEndpoint.newBuilder()
                .serviceEndpoint(
                        ClprServiceEndpoint.newBuilder().ipAddress(host).port(50211))
                .build();
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
