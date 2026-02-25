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
