// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeIdentityTest {

    private static final int GRPC_PORT = 50211;
    private static final int TLS_PORT = 50212;

    @Mock
    private NodeInfo selfNodeInfo;

    private GrpcConfig grpcConfig;

    @BeforeEach
    void setUp() {
        grpcConfig = new GrpcConfig(GRPC_PORT, TLS_PORT, true, 50213, 60211, 60212, 4194304, 4194304, 4194304);
    }

    @Test
    @DisplayName("loopback host on the configured gRPC port is always self")
    void loopbackOnGrpcPortIsSelf() {
        // Loopback + gRPC/TLS port short-circuits before reading hapiEndpoints.
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("127.0.0.1", GRPC_PORT)).isTrue();
        assertThat(identity.isSelf("::1", GRPC_PORT)).isTrue();
        assertThat(identity.isSelf("localhost", GRPC_PORT)).isTrue();
    }

    @Test
    @DisplayName("loopback host on the configured TLS port is always self")
    void loopbackOnTlsPortIsSelf() {
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("127.0.0.1", TLS_PORT)).isTrue();
    }

    @Test
    @DisplayName("loopback host on the configured CLPR mTLS port is self")
    void loopbackOnClprMtlsPortIsSelf() {
        final int clprMtlsPort = 50214;
        final var identity = new NodeIdentity(grpcConfig, clprMtlsPort, selfNodeInfo);

        assertThat(identity.isSelf("127.0.0.1", clprMtlsPort)).isTrue();
    }

    @Test
    @DisplayName("loopback host on an unrelated port is not self when hapiEndpoints don't match")
    void loopbackOnUnrelatedPortIsNotSelf() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of());
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("127.0.0.1", 9999)).isFalse();
    }

    @Test
    @DisplayName("a null self NodeInfo with a non-loopback host is never self")
    void nullSelfIsNotSelf() {
        final var identity = new NodeIdentity(grpcConfig, null);

        assertThat(identity.isSelf("10.0.0.1", GRPC_PORT)).isFalse();
    }

    @Test
    @DisplayName("a null self NodeInfo still recognises loopback on the gRPC port as self")
    void nullSelfStillMatchesLoopbackOnGrpcPort() {
        final var identity = new NodeIdentity(grpcConfig, null);

        assertThat(identity.isSelf("127.0.0.1", GRPC_PORT)).isTrue();
    }

    @Test
    @DisplayName("matching ipAddressV4 + port in hapiEndpoints is self")
    void matchingIpV4AndPortIsSelf() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithIp("10.0.0.5", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("10.0.0.5", 50211)).isTrue();
    }

    @Test
    @DisplayName("matching ip but mismatched port is not self")
    void mismatchedPortIsNotSelf() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithIp("10.0.0.5", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("10.0.0.5", 50300)).isFalse();
    }

    @Test
    @DisplayName("mismatched ip on the same port is not self")
    void mismatchedIpIsNotSelf() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithIp("10.0.0.5", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("10.0.0.6", 50211)).isFalse();
    }

    @Test
    @DisplayName("matching domainName + port in hapiEndpoints is self")
    void matchingDomainNameAndPortIsSelf() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithDomain("node1.example.com", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("node1.example.com", 50211)).isTrue();
    }

    @Test
    @DisplayName("mismatched domainName is not self")
    void mismatchedDomainNameIsNotSelf() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithDomain("node1.example.com", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("node2.example.com", 50211)).isFalse();
    }

    @Test
    @DisplayName("domainName comparison is case-insensitive (RFC 1035)")
    void domainNameComparisonIsCaseInsensitive() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithDomain("Node1.Example.Com", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("node1.example.com", 50211)).isTrue();
        assertThat(identity.isSelf("NODE1.EXAMPLE.COM", 50211)).isTrue();
    }

    @Test
    @DisplayName("trailing dot in domainName is normalised on either side")
    void trailingDotInDomainNameIsNormalised() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithDomain("node1.example.com.", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        // Trailing dot present on self, absent on peer.
        assertThat(identity.isSelf("node1.example.com", 50211)).isTrue();
        // Trailing dot present on both.
        assertThat(identity.isSelf("node1.example.com.", 50211)).isTrue();

        // Trailing dot present on peer, absent on self.
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithDomain("node1.example.com", 50211)));
        final var identityNoDotInSelf = new NodeIdentity(grpcConfig, selfNodeInfo);
        assertThat(identityNoDotInSelf.isSelf("node1.example.com.", 50211)).isTrue();
    }

    @Test
    @DisplayName("non-loopback IPv6 peer does not match an IPv4-only hapi endpoint (documents current gap)")
    void ipv6PeerDoesNotMatchIpv4SelfEndpoint() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithIp("10.0.0.5", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        // HAPI ServiceEndpoint is IPv4-only; spec-allowed numeric IPv6 peer addresses
        // cannot currently be matched to self even if they describe the same node.
        assertThat(identity.isSelf("2001:db8::1", 50211)).isFalse();
    }

    @Test
    @DisplayName("non-canonical IPv4 forms do not match canonical self IPv4")
    void nonCanonicalIpv4DoesNotMatch() {
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(endpointWithIp("10.0.0.5", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        // IPv4 comparison is strict string equality on dotted-decimal — no canonicalisation,
        // so leading-zero forms and IPv4-mapped IPv6 forms slip past.
        assertThat(identity.isSelf("010.000.000.005", 50211)).isFalse();
        assertThat(identity.isSelf("::ffff:10.0.0.5", 50211)).isFalse();
    }

    @Test
    @DisplayName("an endpoint with a malformed ipAddressV4 is ignored for ip matching")
    void malformedIpV4IsIgnored() {
        final var malformed = ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(new byte[] {1, 2, 3})) // not 4 bytes
                .port(50211)
                .build();
        given(selfNodeInfo.hapiEndpoints()).willReturn(List.of(malformed));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("1.2.3.0", 50211)).isFalse();
    }

    @Test
    @DisplayName("scans all hapiEndpoints — match in a later entry still wins")
    void scansAllHapiEndpoints() {
        given(selfNodeInfo.hapiEndpoints())
                .willReturn(List.of(endpointWithIp("10.0.0.1", 50211), endpointWithDomain("node2.example.com", 50211)));
        final var identity = new NodeIdentity(grpcConfig, selfNodeInfo);

        assertThat(identity.isSelf("node2.example.com", 50211)).isTrue();
    }

    private static ServiceEndpoint endpointWithIp(final String dottedIp, final int port) {
        final var octets = dottedIp.split("\\.");
        final var ipBytes = new byte[] {
            (byte) Integer.parseInt(octets[0]),
            (byte) Integer.parseInt(octets[1]),
            (byte) Integer.parseInt(octets[2]),
            (byte) Integer.parseInt(octets[3])
        };
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(ipBytes))
                .port(port)
                .build();
    }

    private static ServiceEndpoint endpointWithDomain(final String domain, final int port) {
        return ServiceEndpoint.newBuilder().domainName(domain).port(port).build();
    }
}
