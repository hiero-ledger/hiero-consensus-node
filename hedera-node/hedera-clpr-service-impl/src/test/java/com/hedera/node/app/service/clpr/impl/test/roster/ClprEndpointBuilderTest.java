// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.roster;

import static com.hedera.hapi.util.HapiUtils.endpointFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.clpr.impl.roster.ClprEndpointBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprEndpointBuilderTest {

    private static final long NODE_ID = 1L;
    private static final int HAPI_PORT = 50211;
    private static final int HAPI_TLS_PORT = 50212;
    private static final Bytes HAPI_TLS_CERT_DER = Bytes.wrap(new byte[] {1, 2, 3, 4});
    private static final ServiceEndpoint HOSTNAME = endpointFor("node.example", 50000);
    private static final ServiceEndpoint IPV4 = endpointFor("10.0.0.145", HAPI_PORT);

    @Mock
    private ReadableNodeStore nodeStore;

    @Test
    void returnsNullWhenNodeAbsent() {
        given(nodeStore.get(NODE_ID)).willReturn(null);
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, Bytes.EMPTY))
                .isNull();
        assertThat(ClprEndpointBuilder.ipAddressOf(NODE_ID, nodeStore)).isNull();
    }

    @Test
    void returnsNullWhenNodeDeleted() {
        given(nodeStore.get(NODE_ID))
                .willReturn(nodeWithEndpoints(IPV4).copyBuilder().deleted(true).build());
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, Bytes.EMPTY))
                .isNull();
        assertThat(ClprEndpointBuilder.ipAddressOf(NODE_ID, nodeStore)).isNull();
    }

    @Test
    void returnsNullWhenNoServiceEndpoint() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithEndpoints());
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, HAPI_TLS_CERT_DER))
                .isNull();
        assertThat(ClprEndpointBuilder.ipAddressOf(NODE_ID, nodeStore)).isNull();
    }

    @Test
    void skipsDomainOnlyNodeForPublicationAndReconciliation() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithEndpoints(HOSTNAME));
        assertThat(ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, HAPI_TLS_CERT_DER))
                .isNull();
        assertThat(ClprEndpointBuilder.ipAddressOf(NODE_ID, nodeStore)).isNull();
    }

    @Test
    @DisplayName("given no HAPI TLS certificate, then the address-book HAPI port and an empty cert are advertised")
    void givenNoHapiTlsCertificate_thenAddressBookHapiPortAndEmptyCertAreAdvertised() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithEndpoints(HOSTNAME, IPV4));

        final var endpoint = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, Bytes.EMPTY);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.serviceEndpointOrThrow().ipAddress()).isEqualTo("10.0.0.145");
        assertThat(endpoint.serviceEndpointOrThrow().port()).isEqualTo(HAPI_PORT);
        assertThat(endpoint.tlsCertificate()).isEqualTo(Bytes.EMPTY);
    }

    @Test
    @DisplayName("given a HAPI TLS certificate, then the HAPI TLS port and that cert are advertised")
    void givenHapiTlsCertificate_thenHapiTlsPortAndCertAreAdvertised() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithEndpoints(HOSTNAME, IPV4));

        final var endpoint = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, HAPI_TLS_CERT_DER);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.serviceEndpointOrThrow().ipAddress()).isEqualTo("10.0.0.145");
        assertThat(endpoint.serviceEndpointOrThrow().port()).isEqualTo(HAPI_TLS_PORT);
        assertThat(endpoint.tlsCertificate()).isEqualTo(HAPI_TLS_CERT_DER);
    }

    @Test
    void publicationAndReconciliationUseFirstIpv4InAddressBookOrder() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithEndpoints(HOSTNAME, IPV4, endpointFor("10.0.0.2", HAPI_PORT)));

        final var endpoint = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, Bytes.EMPTY);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.serviceEndpointOrThrow().ipAddress()).isEqualTo("10.0.0.145");
        assertThat(ClprEndpointBuilder.ipAddressOf(NODE_ID, nodeStore)).isEqualTo("10.0.0.145");
    }

    @Test
    void identityOfUsesPublishedIp() {
        given(nodeStore.get(NODE_ID)).willReturn(nodeWithEndpoints(IPV4));
        final var withIp = ClprEndpointBuilder.buildFor(NODE_ID, nodeStore, HAPI_TLS_PORT, HAPI_TLS_CERT_DER);
        assertThat(ClprEndpointBuilder.identityOf(withIp)).isEqualTo(Bytes.wrap("10.0.0.145"));
    }

    private static Node nodeWithEndpoints(final ServiceEndpoint... endpoints) {
        return Node.newBuilder()
                .nodeId(NODE_ID)
                .accountId(AccountID.newBuilder().accountNum(3001L).build())
                .serviceEndpoint(endpoints)
                .build();
    }
}
