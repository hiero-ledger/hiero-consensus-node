// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FQDN_SIZE_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SERVICE_ENDPOINT;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.writeCertificatePemFile;
import static com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase.generateX509Certificates;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.addressbook.RegisteredServiceEndpoint;
import com.hedera.hapi.node.base.ServiceEndpoint;
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
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of()));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointsForCreateAcceptsValidIpv4() {
        assertDoesNotThrow(() -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(blockNodeEndpoint(new byte[] {127, 0, 0, 1}))));
    }

    @Test
    void registeredEndpointsForCreateAcceptsValidIpv6() {
        assertDoesNotThrow(() -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(blockNodeEndpoint(new byte[16]))));
    }

    @Test
    void registeredEndpointsForCreateAcceptsValidDomain() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("block.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
    }

    @Test
    void registeredEndpointsForCreateRejectsExceedingLimit() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {127, 0, 0, 1}));
        }
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(endpoints));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointsForCreateAcceptsExactlyFifty() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 50; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {127, 0, 0, 1}));
        }
        assertDoesNotThrow(() -> new AddressBookValidator().validateRegisteredServiceEndpointsForCreate(endpoints));
    }

    @Test
    void registeredEndpointsForCreateRejectsMissingAddress() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointsForCreateRejectsMissingEndpointType() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {127, 0, 0, 1}))
                .port(443)
                .build();
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointsForCreateRejectsInvalidIpv4Length() {
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(blockNodeEndpoint(new byte[] {127, 0, 0}))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointsForCreateRejectsInvalidIpv6Length() {
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(blockNodeEndpoint(new byte[15]))));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointsForUpdateAcceptsEmptyList() {
        assertDoesNotThrow(() -> new AddressBookValidator().validateRegisteredServiceEndpointsForUpdate(List.of()));
    }

    @Test
    void registeredEndpointsForUpdateAcceptsValidEndpoints() {
        assertDoesNotThrow(() -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForUpdate(List.of(blockNodeEndpoint(new byte[] {10, 0, 0, 1}))));
    }

    @Test
    void registeredEndpointsForUpdateRejectsExceedingLimit() {
        final var endpoints = new ArrayList<RegisteredServiceEndpoint>();
        for (int i = 0; i < 51; i++) {
            endpoints.add(blockNodeEndpoint(new byte[] {10, 0, 0, 1}));
        }
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForUpdate(endpoints));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointRejectsDomainWithLeadingHyphen() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("-example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointRejectsNonAsciiDomain() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("bl\u00f6ck.example.com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
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
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointRejectsDomainWithConsecutiveDots() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .domainName("example..com")
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
        final var e = assertThrows(PreCheckException.class, () -> new AddressBookValidator()
                .validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
        assertEquals(INVALID_SERVICE_ENDPOINT, e.responseCode());
    }

    @Test
    void registeredEndpointAcceptsMirrorNodeType() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(443)
                .mirrorNode(RegisteredServiceEndpoint.MirrorNodeEndpoint.DEFAULT)
                .build();
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
    }

    @Test
    void registeredEndpointAcceptsRpcRelayType() {
        final var endpoint = RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(new byte[] {10, 0, 0, 1}))
                .port(8545)
                .rpcRelay(RegisteredServiceEndpoint.RpcRelayEndpoint.DEFAULT)
                .build();
        assertDoesNotThrow(
                () -> new AddressBookValidator().validateRegisteredServiceEndpointsForCreate(List.of(endpoint)));
    }

    private static RegisteredServiceEndpoint blockNodeEndpoint(final byte[] ip) {
        return RegisteredServiceEndpoint.newBuilder()
                .ipAddress(Bytes.wrap(ip))
                .port(443)
                .blockNode(blockNodeEndpointType())
                .build();
    }

    private static RegisteredServiceEndpoint.BlockNodeEndpoint blockNodeEndpointType() {
        return RegisteredServiceEndpoint.BlockNodeEndpoint.newBuilder()
                .endpointApi(RegisteredServiceEndpoint.BlockNodeEndpoint.BlockNodeApi.STATUS)
                .build();
    }

    private NodesConfig newNodesConfig() {
        return newNodesConfig(253);
    }

    private NodesConfig newNodesConfig(final int maxFqdnSize) {
        return new TestConfigBuilder()
                .withConfigDataType(NodesConfig.class)
                .withValue("nodes.maxFqdnSize", maxFqdnSize)
                .getOrCreateConfig()
                .getConfigData(NodesConfig.class);
    }
}
