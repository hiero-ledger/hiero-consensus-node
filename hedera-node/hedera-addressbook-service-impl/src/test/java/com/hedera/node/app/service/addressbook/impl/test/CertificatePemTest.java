// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.loadResourceFile;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.readCertificatePemFile;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.writeCertificatePemFile;
import static com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator.validateX509Certificate;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificatePemTest {
    @TempDir
    private File tmpDir;

    @Test
    void validateGeneratedPemFile() throws IOException, CertificateException {
        final var pemFileName = "s-public-node1.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        final var cert = readCertificatePemFile(pemFilePath);
        final var genPemPath = Path.of(tmpDir.getPath() + "/generated.pem");
        writeCertificatePemFile(genPemPath, cert.getEncoded());
        final var genCert = readCertificatePemFile(genPemPath);

        assertEquals("SHA384withRSA", cert.getSigAlgName());
        assertEquals("X.509", cert.getType());
        assertArrayEquals(cert.getEncoded(), genCert.getEncoded());
        assertEquals(cert, genCert);
        assertDoesNotThrow(() -> validateX509Certificate(Bytes.wrap(genCert.getEncoded())));
    }

    @Test
    void onlyCertificatePemAllowedToRead() {
        final var pemFileName = "s-private-node1.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        final var exception = assertThrows(CertificateException.class, () -> readCertificatePemFile(pemFilePath));
        assertEquals("Not X509 Certificate, it is PrivateKeyInfo", exception.getMessage());
    }

    @Test
    void invalidCertificatePem() throws CertificateException, IOException {
        final var pemFileName = "s-public-node1.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        final var cert = readCertificatePemFile(pemFilePath);
        final var test = Path.of(tmpDir.getPath() + "/test");
        Files.write(test, cert.getEncoded());
        assertThrows(CertificateException.class, () -> readCertificatePemFile(test));
    }

    @Test
    void invalidBytesInPemCannotRead() throws IOException {
        final var genPemPath = Path.of(tmpDir.getPath() + "/generated.pem");
        writeCertificatePemFile(genPemPath, Bytes.wrap("anyString").toByteArray());
        final var exception = assertThrows(IOException.class, () -> readCertificatePemFile(genPemPath));
        assertThat(exception.getMessage()).contains("problem parsing cert: java.io.EOFException:");
        final var msg = assertThrows(PreCheckException.class, () -> validateX509Certificate(Bytes.wrap("anyString")));
        assertEquals(ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE, msg.responseCode());
    }

    @Test
    void badX509CertificateFailedOnReadAndValidation() throws IOException {
        final var pemFileName = "badX509.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        final var exception = assertThrows(IOException.class, () -> readCertificatePemFile(pemFilePath));
        assertEquals("problem parsing cert: java.io.IOException: unknown tag 13 encountered", exception.getMessage());

        final byte[] certBytes = Files.readAllBytes(pemFilePath);
        final var msg = assertThrows(PreCheckException.class, () -> validateX509Certificate(Bytes.wrap(certBytes)));
        assertEquals(ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE, msg.responseCode());
        final var getmsg = assertThrows(PreCheckException.class, () -> getX509Certificate(Bytes.wrap(certBytes)));
        assertEquals(ResponseCodeEnum.INVALID_GOSSIP_CA_CERTIFICATE, getmsg.responseCode());
    }

    @Test
    void goodX509CertificateSuccessOnReadAndValidation() throws IOException, CertificateException {
        final var pemFileName = "goodX509.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        final var cert = readCertificatePemFile(pemFilePath);

        assertEquals("SHA384withRSA", cert.getSigAlgName());
        assertEquals("X.509", cert.getType());
        assertDoesNotThrow(() -> getX509Certificate(Bytes.wrap(cert.getEncoded())));
        assertDoesNotThrow(() -> validateX509Certificate(Bytes.wrap(cert.getEncoded())));

        final byte[] certBytes = Files.readAllBytes(pemFilePath);
        assertDoesNotThrow(() -> getX509Certificate(Bytes.wrap(certBytes)));
    }

    @Test
    void getSameCertificateFromPemEncodingAndPemBytes() throws IOException, CertificateException, PreCheckException {
        final var pemFileName = "goodX509.pem";
        final var pemFilePath = loadResourceFile(pemFileName);
        final var cert = readCertificatePemFile(pemFilePath);

        assertEquals("SHA384withRSA", cert.getSigAlgName());
        assertEquals("X.509", cert.getType());
        final var encodingCert = getX509Certificate(Bytes.wrap(cert.getEncoded()));

        final byte[] certBytes = Files.readAllBytes(pemFilePath);
        final var bytesCert = getX509Certificate(Bytes.wrap(certBytes));

        assertEquals(encodingCert, bytesCert);
    }

    @Test
    void goodCertificateBecomeBad() throws IOException {
        final var goodPem = "goodX509.pem";
        final var goodFilePath = loadResourceFile(goodPem);
        final byte[] certBytes = Files.readAllBytes(goodFilePath);
        final var genPemPath = Path.of(tmpDir.getPath() + "/generated.pem");
        writeCertificatePemFile(genPemPath, certBytes);
        final byte[] genAllBytes = Files.readAllBytes(genPemPath);
        final byte[] genBytes = Arrays.copyOf(genAllBytes, genAllBytes.length - 1);

        final var badPem = "badX509.pem";
        final var badFilePath = loadResourceFile(badPem);
        final byte[] badBytes = Files.readAllBytes(badFilePath);
        assertArrayEquals(genBytes, badBytes);
    }

    /**
     * Parse X509Certificate bytes and get the X509Certificate object.
     * @param certBytes the Bytes to validate
     * @throws PreCheckException if the certificate is invalid
     */
    private static X509Certificate getX509Certificate(@NonNull Bytes certBytes) throws PreCheckException {
        X509Certificate cert;
        try {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certBytes.toByteArray()));
        } catch (final CertificateException e) {
            throw new PreCheckException(INVALID_GOSSIP_CA_CERTIFICATE);
        }
        return cert;
    }
}
