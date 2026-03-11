// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FQDN_SIZE_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_ADDRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_REGISTERED_ENDPOINT_TYPE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_REGISTERED_NODES_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REGISTERED_ENDPOINTS_EXCEEDED_LIMIT;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.writeCertificatePemFile;
import static com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase.generateX509Certificates;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AddressBookValidatorTest {
    private static final ServiceEndpoint GRPC_PROXY_ENDPOINT_FQDN = ServiceEndpoint.newBuilder()
            .domainName("grpc.web.proxy.com")
            .port(123)
            .build();
    private static final ServiceEndpoint GRPC_PROXY_ENDPOINT_IP = ServiceEndpoint.newBuilder()
            .ipAddressV4(Bytes.wrap("192.168.1.255"))
            .port(123)
            .build();

    private static X509Certificate x509Cert;

    @BeforeAll
    static void beforeAll() {
        x509Cert = generateX509Certificates(1).getFirst();
    }

    @Test
    void encodedCertPassesValidation() {
        assertDoesNotThrow(() -> validateX509Certificate(Bytes.wrap(x509Cert.getEncoded())));
    }

    @Test
    void utf8EncodingOfX509PemFailsValidation() throws CertificateEncodingException, IOException {
        final var baos = new ByteArrayOutputStream();
        writeCertificatePemFile(x509Cert.getEncoded(), baos);
        final var e =
                assertThrows(PreCheckException.class, () -> validateX509Certificate(Bytes.wrap(baos.toByteArray())));
        assertEquals(INVALID_GOSSIP_CA_CERTIFICATE, e.responseCode());
    }

    @Test
    void nullParamsWebProxyEndpointFailsValidation() {
        final var config = newNodesConfig();
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new AddressBookValidator().validateFqdnEndpoint(null, config));
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new AddressBookValidator()
                .validateFqdnEndpoint(GRPC_PROXY_ENDPOINT_FQDN, null));
    }

    @Test
    void ipOnlyWebProxyEndpointFailsValidation() {
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateFqdnEndpoint(GRPC_PROXY_ENDPOINT_IP, newNodesConfig()));
        Assertions.assertThat(e.getStatus()).isEqualTo(INVALID_SERVICE_ENDPOINT);
    }

    @Test
    void ipAndfqdnWebProxyEndpointFailsValidation() {
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateFqdnEndpoint(
                        ServiceEndpoint.newBuilder()
                                .ipAddressV4(Bytes.wrap("192.168.1.255"))
                                .domainName("grpc.web.proxy.com")
                                .port(123)
                                .build(),
                        newNodesConfig()));
        Assertions.assertThat(e.getStatus()).isEqualTo(INVALID_SERVICE_ENDPOINT);
    }

    @Test
    void emptyDomainNameWebProxyEndpointFailsValidation() {
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateFqdnEndpoint(
                        ServiceEndpoint.newBuilder().domainName("").port(123).build(), newNodesConfig()));
        Assertions.assertThat(e.getStatus()).isEqualTo(INVALID_SERVICE_ENDPOINT);
    }

    @Test
    void tooLongFqdnWebProxyEndpointFailsValidation() {
        // Intentionally reduce the max FQDN size to trigger the validation error
        final var config = newNodesConfig(10);
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateFqdnEndpoint(GRPC_PROXY_ENDPOINT_FQDN, config));
        Assertions.assertThat(e.getStatus()).isEqualTo(FQDN_SIZE_TOO_LARGE);
    }

    @Test
    void fqdnWebProxyEndpointPassesValidation() {
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateFqdnEndpoint(GRPC_PROXY_ENDPOINT_FQDN, newNodesConfig()));
    }

    // --- Registered service endpoint validation tests ---

    @Test
    void registeredEndpointsForCreateRejectsEmptyList() {
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT, e.getStatus());
    }

    @Test
    void registeredEndpointsForCreateAcceptsValidIpv4() {
        assertDoesNotThrow(() -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(
                        List.of(blockNodeEndpoint(new byte[] {127, 0, 0, 1})), newNodesConfig()));
    }

    @Test
    void registeredEndpointsForCreateAcceptsValidIpv6() {
        assertDoesNotThrow(() -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(blockNodeEndpoint(new byte[16])), newNodesConfig()));
    }

    @Test
    void registeredEndpointsForCreateAcceptsValidDomain() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointsForCreateRejectsExceedingLimit() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {127, 0, 0, 1}));
        }
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(endpoints, newNodesConfig()));
        assertEquals(REGISTERED_ENDPOINTS_EXCEEDED_LIMIT, e.getStatus());
    }

    @Test
    void registeredEndpointsForCreateAcceptsExactlyFifty() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 50; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {127, 0, 0, 1}));
        }
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateRegisteredServiceEndpoints(endpoints, newNodesConfig()));
    }

    @Test
    void registeredEndpointsForCreateRejectsMissingAddress() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT, e.getStatus());
    }

    @Test
    void registeredEndpointsForCreateRejectsMissingEndpointType() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(443)
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_TYPE, e.getStatus());
    }

    @Test
    void registeredEndpointsForCreateRejectsInvalidIpv4Length() {
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(
                        List.of(blockNodeEndpoint(new byte[] {127, 0, 0})), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointsForCreateRejectsInvalidIpv6Length() {
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(blockNodeEndpoint(new byte[15])), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointsRejectsExceedingLimit() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {10, 0, 0, 1}));
        }
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(endpoints, newNodesConfig()));
        assertEquals(REGISTERED_ENDPOINTS_EXCEEDED_LIMIT, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsDomainWithLeadingHyphen() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("-example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsNonAsciiDomain() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("bl\u00f6ck.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsDomainExceeding253Chars() {
        // Build a domain > 253 chars: 4 labels of 63 chars each = 63*4 + 3 dots = 255
        final var label = "a".repeat(63);
        final var domain = label + "." + label + "." + label + "." + label;
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName(domain)
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsDomainWithConsecutiveDots() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("example..com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointAcceptsPort0() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(0)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointAcceptsPort65535() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(65535)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointRejectsPortAbove65535() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(70000)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT, e.getStatus());
    }

    @Test
    void registeredEndpointAcceptsDomainWithTrailingDot() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block.example.com.")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointAcceptsSingleLabelDomain() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("localhost")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointRejectsDomainLabelExceeding63Chars() {
        final var longLabel = "a".repeat(64);
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName(longLabel + ".example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsDomainWithTrailingHyphenInLabel() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("example-.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsDomainWithSpecialChars() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block_node.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointAcceptsDomainWithHyphenInMiddle() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block-node.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointAcceptsDomainAtExactlyMaxRegisteredFqdnSize() {
        // maxRegisteredFqdnSize default = 250, build a domain at exactly 250 chars
        // 63.63.63.58 = 247 chars of labels + 3 dots = 250
        final var domain = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58);
        assertEquals(250, domain.length());
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName(domain)
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointRejectsDomainExceedingCustomFqdnSize() {
        // Configure maxRegisteredFqdnSize to 10, verify a longer domain is rejected
        final var config = newNodesConfig(253, 10);
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), config));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointsAcceptsExactlyMaxEntries() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 50; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {10, 0, 0, 1}));
        }
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateRegisteredServiceEndpoints(endpoints, newNodesConfig()));
    }

    @Test
    void registeredEndpointsRejectsInvalidEndpoint() {
        final var badEndpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0}))
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(badEndpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsEmptyDomainName() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointRejectsDomainExceedingMaxAsciiChars() {
        // Build a domain of 251 chars (exceeds maxRegisteredFqdnSize default = 250)
        // but under 253 DNS limit. Tests the configurable ASCII limit specifically.
        final var domain = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(59);
        assertEquals(251, domain.length());
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName(domain)
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT_ADDRESS, e.getStatus());
    }

    @Test
    void registeredEndpointAcceptsMirrorNodeType() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(443)
                .mirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.DEFAULT)
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointAcceptsRpcRelayType() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(8545)
                .rpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.DEFAULT)
                .build();
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointAcceptsGeneralServiceType() {
        final var endpoint = generalServiceEndpoint(new byte[] {10, 0, 0, 1}, "Custom API");
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointAcceptsGeneralServiceWithEmptyDescription() {
        final var endpoint = generalServiceEndpoint(new byte[] {10, 0, 0, 1}, "");
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointRejectsGeneralServiceDescriptionExceedingMaxBytes() {
        // maxGeneralServiceDescriptionUtf8Bytes defaults to 100; use 101 ASCII chars to exceed
        final var endpoint = generalServiceEndpoint(new byte[] {10, 0, 0, 1}, "x".repeat(101));
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT, e.getStatus());
    }

    @Test
    void registeredEndpointAcceptsGeneralServiceDescriptionAtExactlyMaxBytes() {
        final var endpoint = generalServiceEndpoint(new byte[] {10, 0, 0, 1}, "x".repeat(100));
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
    }

    @Test
    void registeredEndpointRejectsGeneralServiceDescriptionWithNullByte() {
        final var endpoint = generalServiceEndpoint(new byte[] {10, 0, 0, 1}, "valid\0hidden");
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpoints(List.of(endpoint), newNodesConfig()));
        assertEquals(INVALID_REGISTERED_ENDPOINT, e.getStatus());
    }

    // --- Associated registered node validation tests ---

    @Test
    void associatedRegisteredNodesAcceptsValidIds() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        given(store.get(1L)).willReturn(RegisteredNode.DEFAULT);
        given(store.get(2L)).willReturn(RegisteredNode.DEFAULT);
        assertDoesNotThrow(() ->
                new AddressBookValidator().validateAssociatedRegisteredNodes(List.of(1L, 2L), store, newNodesConfig()));
    }

    @Test
    void associatedRegisteredNodesAcceptsEmptyList() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateAssociatedRegisteredNodes(List.of(), store, newNodesConfig()));
    }

    @Test
    void associatedRegisteredNodesAcceptsExactlyMax() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        final var ids = new ArrayList<Long>();
        for (long i = 1; i <= 20; i++) {
            ids.add(i);
            given(store.get(i)).willReturn(RegisteredNode.DEFAULT);
        }
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateAssociatedRegisteredNodes(ids, store, newNodesConfig()));
    }

    @Test
    void associatedRegisteredNodesRejectsExceedingMax() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        final var ids = new ArrayList<Long>();
        for (long i = 1; i <= 21; i++) {
            ids.add(i);
        }
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateAssociatedRegisteredNodes(ids, store, newNodesConfig()));
        assertEquals(MAX_REGISTERED_NODES_EXCEEDED, e.getStatus());
    }

    @Test
    void associatedRegisteredNodesRejectsNegativeId() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateAssociatedRegisteredNodes(List.of(-1L), store, newNodesConfig()));
        assertEquals(INVALID_NODE_ID, e.getStatus());
    }

    @Test
    void associatedRegisteredNodesRejectsNonExistentId() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        given(store.get(999L)).willReturn(null);
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateAssociatedRegisteredNodes(List.of(999L), store, newNodesConfig()));
        assertEquals(INVALID_NODE_ID, e.getStatus());
    }

    @Test
    void associatedRegisteredNodesRespectsCustomMaxConfig() {
        final var store = mock(ReadableRegisteredNodeStore.class);
        given(store.get(1L)).willReturn(RegisteredNode.DEFAULT);
        given(store.get(2L)).willReturn(RegisteredNode.DEFAULT);
        given(store.get(3L)).willReturn(RegisteredNode.DEFAULT);
        // Configure max to 2, then try 3 entries
        final var config = newNodesConfig(253, 250, 2);
        final var e = assertThrows(HandleException.class, () -> new AddressBookValidator()
                .validateAssociatedRegisteredNodes(List.of(1L, 2L, 3L), store, config));
        assertEquals(MAX_REGISTERED_NODES_EXCEEDED, e.getStatus());
    }

    private static RegisteredServiceEndpoint blockNodeEndpoint(final byte[] ip) {
        return RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(ip))
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
    }

    private static RegisteredServiceEndpoint generalServiceEndpoint(final byte[] ip, final String description) {
        return RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(ip))
                .port(443)
                .generalService(RegisteredServiceEndpoint.GeneralServiceEndpoint.newBuilder()
                        .description(description)
                        .build())
                .build();
    }

    private static RegisteredServiceEndpoint.BlockNodeEndpoint blockNodeEndpointType() {
        return RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                .build();
    }

    private NodesConfig newNodesConfig() {
        return new TestConfigBuilder()
                .withConfigDataType(NodesConfig.class)
                .getOrCreateConfig()
                .getConfigData(NodesConfig.class);
    }

    private NodesConfig newNodesConfig(final int maxFqdnSize) {
        return newNodesConfig(maxFqdnSize, 250);
    }

    private NodesConfig newNodesConfig(final int maxFqdnSize, final int maxRegisteredFqdnSize) {
        return new TestConfigBuilder()
                .withConfigDataType(NodesConfig.class)
                .withValue("nodes.maxFqdnSize", maxFqdnSize)
                .withValue("nodes.maxRegisteredFqdnSize", maxRegisteredFqdnSize)
                .getOrCreateConfig()
                .getConfigData(NodesConfig.class);
    }

    private NodesConfig newNodesConfig(
            final int maxFqdnSize, final int maxRegisteredFqdnSize, final int maxAssociatedRegisteredNodes) {
        return new TestConfigBuilder()
                .withConfigDataType(NodesConfig.class)
                .withValue("nodes.maxFqdnSize", maxFqdnSize)
                .withValue("nodes.maxRegisteredFqdnSize", maxRegisteredFqdnSize)
                .withValue("nodes.maxAssociatedRegisteredNodes", maxAssociatedRegisteredNodes)
                .getOrCreateConfig()
                .getConfigData(NodesConfig.class);
    }
}
