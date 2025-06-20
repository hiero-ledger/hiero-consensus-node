// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.writeCertificatePemFile;
import static com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase.generateX509Certificates;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AddressBookValidatorTest {
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
}
