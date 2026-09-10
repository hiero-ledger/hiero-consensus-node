// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMException;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.util.encoders.DecoderException;

/**
 * Loads and exposes the CLPR CA credentials (ECDSA P-384 cert + private key) from the paths
 * configured in {@link ClprConfig#caCrtPath()} and {@link ClprConfig#caKeyPath()}.
 *
 * <p>The CA cert is what this node publishes in {@code ClprEndpoint.tls_certificate}. Peer nodes
 * pin it to validate the ephemeral leaf cert this node presents at TLS handshake.
 *
 * <p>If either path is blank or loading fails, {@link #isMtlsEnabled()} returns {@code false} and
 * CLPR sync runs without mTLS (plaintext). This is the expected behaviour for local development
 * where no CA cert/key has been provisioned.
 */
@Singleton
public class ClprCaCertManager {

    private static final Logger logger = LogManager.getLogger(ClprCaCertManager.class);
    public static final int DER_SEQUENCE_TAG = 0x30;

    private final X509Certificate caCert;
    private final PrivateKey caKey;

    @Inject
    public ClprCaCertManager(@NonNull final ConfigProvider configProvider) {
        requireNonNull(configProvider);
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            this.caKey = null;
            this.caCert = null;
            return;
        }
        final var caCrtPath = clprConfig.caCrtPath();
        final var caKeyPath = clprConfig.caKeyPath();

        if (caCrtPath == null || caCrtPath.isBlank() || caKeyPath == null || caKeyPath.isBlank()) {
            logger.warn(
                    "CLPR CA cert/key not configured (clpr.caCrtPath / clpr.caKeyPath) — CLPR sync will run without mTLS");
            this.caKey = null;
            this.caCert = null;
        } else {
            this.caCert = loadCert(caCrtPath);
            this.caKey = loadKey(caKeyPath);
            logger.info("Loaded CLPR CA cert from {} (subject: {})", caCrtPath, caCert.getSubjectX500Principal());
        }
    }

    /** Returns {@code true} iff the CA cert and key were loaded successfully. */
    public boolean isMtlsEnabled() {
        return caCert != null && caKey != null;
    }

    /** The ECDSA P-384 CA certificate, or {@code null} if mTLS is not enabled. */
    @Nullable
    public X509Certificate caCert() {
        return caCert;
    }

    /** The ECDSA P-384 CA private key used to sign ephemeral leaf certs, or {@code null} if mTLS is not enabled. */
    @Nullable
    public PrivateKey caKey() {
        return caKey;
    }

    private static X509Certificate loadCert(final String path) {
        try (final var in = new FileInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (CertificateException ex) {
            throw new RuntimeException("Failed to load CLPR mTLS CA cert from " + path, ex);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read CLPR mTLS certificate (CA) file: " + path, ex);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException("Failed to parse CLPR mTLS certificate (CA)", ex);
        }
    }

    /**
     * Loads the CA private key from the given path using BouncyCastle. Accepts either:
     * <ul>
     *   <li>PEM — any unencrypted BouncyCastle-recognized key: PKCS#8 ({@code -----BEGIN PRIVATE KEY-----})
     *       or the traditional SEC1/PKCS#1 form ({@code -----BEGIN EC PRIVATE KEY-----}); or</li>
     *   <li>binary DER-encoded PKCS#8.</li>
     * </ul>
     * The key algorithm is inferred from the key material. Encrypted keys are not supported.
     */
    private static PrivateKey loadKey(final String path) {
        try {
            final byte[] raw = Files.readAllBytes(Path.of(path));
            final var keyInfo = toPrivateKeyInfo(raw);
            return new JcaPEMKeyConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getPrivateKey(keyInfo);
        } catch (final PEMException ex) {
            throw new IllegalStateException("Failed to parse CLPR mTLS private key (CA) file: " + path, ex);
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to read CLPR mTLS private key (CA) file: " + path, ex);
        }
    }

    /**
     * Extracts a {@link PrivateKeyInfo} from raw key bytes in either binary DER or PEM (any unencrypted
     * variant) form. The two are told apart by the leading byte: a DER-encoded key is ASN.1 whose
     * top-level type is a {@code SEQUENCE}, so it always starts with the SEQUENCE tag {@code 0x30};
     * PEM is base64 text in {@code -----BEGIN …-----} armor and never does.
     */
    private static PrivateKeyInfo toPrivateKeyInfo(final byte[] raw) throws IOException {
        if (raw.length == 0) {
            throw new IOException("empty CLPR CA private key");
        }
        if (raw[0] == DER_SEQUENCE_TAG) {
            return readDERKey(raw);
        }
        return readPEMKey(raw);
    }

    private static PrivateKeyInfo readDERKey(final byte[] raw) {
        try {
            // Binary DER (PKCS#8 PrivateKeyInfo, or a SEC1/PKCS#1 SEQUENCE the converter understands).
            return PrivateKeyInfo.getInstance(ASN1Primitive.fromByteArray(raw));
        } catch (final IOException ex) {
            throw new IllegalStateException("Invalid DER key", ex);
        }
    }

    private static PrivateKeyInfo readPEMKey(final byte[] raw) {
        try (final var parser = new PEMParser(new StringReader(new String(raw, StandardCharsets.UTF_8)))) {
            final var obj = parser.readObject();
            if (obj instanceof PEMKeyPair pemKeyPair) {
                // Traditional SEC1/PKCS#1 PEM ("EC PRIVATE KEY", "RSA PRIVATE KEY", ...).
                return pemKeyPair.getPrivateKeyInfo();
            }
            if (obj instanceof PrivateKeyInfo info) {
                // PKCS#8 PEM ("PRIVATE KEY").
                return info;
            }
            if (obj instanceof PKCS8EncryptedPrivateKeyInfo) {
                throw new IOException("encrypted CLPR CA private keys are not supported; provide an unencrypted key");
            }
            throw new IOException("unrecognized CLPR CA private key content: "
                    + (obj == null ? "neither PEM nor DER" : obj.getClass().getName()));
        } catch (final IOException ex) {
            if (ex.getCause() instanceof DecoderException) {
                throw new IllegalStateException("Failed to parse CLPR mTLS private key (CA)", ex);
            }
            throw new IllegalStateException("Failed to read CLPR mTLS CA PEM key", ex);
        } catch (final DecoderException ex) {
            throw new IllegalStateException("Failed to parse CLPR mTLS private key (CA)", ex);
        }
    }
}
