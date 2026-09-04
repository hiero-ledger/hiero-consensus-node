// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.impl.roster.ClprEndpointBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprEndpointBuilderTest {

    private static final long NODE_ID = 1L;
    private static final int HAPI_PORT = 50211;
    private static final int MTLS_PORT = 43450;
    private static final Bytes CA_CERT_DER = Bytes.wrap(new byte[] {1, 2, 3, 4});

    @Mock
    private ReadableNodeStore nodeStore;

    @Test
    void returnsNullWhenNodeAbsent() {
        given(nodeStore.get(NODE_ID)).willReturn(null);
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, false, MTLS_PORT, Bytes.EMPTY))
                .isNull();
    }

    @Test
    void returnsNullWhenNodeDeleted() {
        given(nodeStore.get(NODE_ID))
                .willReturn(nodeWithDomain("host-1").copyBuilder().deleted(true).build());
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, false, MTLS_PORT, Bytes.EMPTY))
                .isNull();
    }

    @Test
    void returnsNullWhenNoServiceEndpoint() {
        given(nodeStore.get(NODE_ID))
                .willReturn(Node.newBuilder()
                        .nodeId(NODE_ID)
                        .accountId(AccountID.newBuilder().accountNum(3001L).build())
                        .serviceEndpoint(List.of())
                        .build());
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, true, MTLS_PORT, CA_CERT_DER))
                .isNull();
    }

    @Test
    void mtlsOffFallsBackToHapiPortAndEmptyCert() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithDomain("host-1"));

        final var endpoint = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, false, MTLS_PORT, CA_CERT_DER);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.serviceEndpointOrThrow().ipAddress()).isEqualTo("host-1");
        assertThat(endpoint.serviceEndpointOrThrow().port()).isEqualTo(HAPI_PORT);
        assertThat(endpoint.tlsCertificate()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    void mtlsOnAdvertisesMtlsPortAndCaCert() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithDomain("host-1"));

        final var endpoint = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, true, MTLS_PORT, CA_CERT_DER);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.serviceEndpointOrThrow().port()).isEqualTo(MTLS_PORT);
        assertThat(endpoint.tlsCertificate()).isEqualTo(CA_CERT_DER);
    }

    @Test
    void ipv4EndpointIsFormattedAsDottedQuad() {
        given(nodeStore.get(NODE_ID))
                .willReturn(Node.newBuilder()
                        .nodeId(NODE_ID)
                        .accountId(AccountID.newBuilder().accountNum(3001L).build())
                        .serviceEndpoint(ServiceEndpoint.newBuilder()
                                .ipAddressV4(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                                .port(HAPI_PORT)
                                .build())
                        .build());

        final var endpoint = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, false, MTLS_PORT, Bytes.EMPTY);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.serviceEndpointOrThrow().ipAddress()).isEqualTo("10.0.0.1");
    }

    @Test
    void ipAddressOfReturnsHostOrNull() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithDomain("host-1"));
        assertThat(ClprEndpointBuilder.ipAddressOf(NODE_ID, nodeStore)).isEqualTo("host-1");

        given(nodeStore.get(2L)).willReturn(null);
        assertThat(ClprEndpointBuilder.ipAddressOf(2L, nodeStore)).isNull();
    }

    @Test
    void identityOfPrefersIpElseTlsCertificate() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithDomain("host-1"));
        final var withIp = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, true, MTLS_PORT, CA_CERT_DER);
        assertThat(ClprEndpointBuilder.identityOf(withIp))
                .isEqualTo(Bytes.wrap("host-1".getBytes(StandardCharsets.UTF_8)));
    }

    private static Node nodeWithDomain(final String host) {
        return Node.newBuilder()
                .nodeId(NODE_ID)
                .accountId(AccountID.newBuilder().accountNum(3001L).build())
                .serviceEndpoint(ServiceEndpoint.newBuilder()
                        .domainName(host)
                        .port(HAPI_PORT)
                        .build())
                .build();
    }
}
