// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.workflows.clpr.mtls.ClprMtlsContexts;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hiero.base.crypto.CertificateUtils;

/**
 * Generates and holds the ephemeral Ed25519 leaf certificate presented at TLS handshake.
 *
 * <p>The leaf is generated once at process start, signed by the CLPR CA key from
 * {@link ClprCaCertManager}, and held only in memory. It is never persisted and never published
 * on-chain. Peers validate it by verifying it was signed by the CA cert they pinned from on-chain state.
 *
 * <p>If {@link ClprCaCertManager#isMtlsEnabled()} is {@code false} (no CA configured), this manager
 * yields {@code null} cert/key and CLPR sync runs in plaintext. If a CA <i>is</i> configured but the
 * leaf cannot be generated, construction fails fast (the node refuses to start) rather than silently
 * downgrading to plaintext — the operator asked for mTLS, so a leaf failure is an unexpected state.
 */
@Singleton
public class ClprLeafCertManager {
    private static final Logger logger = LogManager.getLogger(ClprLeafCertManager.class);

    private final ClprLeafCredentials credentials;

    @Inject
    public ClprLeafCertManager(@NonNull final ClprCaCertManager caManager) {
        requireNonNull(caManager);

        if (!caManager.isMtlsEnabled()) {
            // No CA configured — CLPR sync runs in plaintext. A legitimate mode, not an error.
            this.credentials = null;
            return;
        }

        try {
            ClprMtlsContexts.ensureProvidersRegistered();
            final var leafKeyPair = generateEd25519KeyPair();
            final var caCert = caManager.caCert();
            final var leafCert = CertificateUtils.generateCertificate(
                    CertificateUtils.distinguishedName("clpr-leaf"),
                    leafKeyPair,
                    caCert.getSubjectX500Principal().getName(),
                    new KeyPair(caCert.getPublicKey(), caManager.caKey()),
                    SecureRandom.getInstanceStrong(),
                    "SHA384withECDSA");
            this.credentials = new ClprLeafCredentials(leafCert, leafKeyPair.getPrivate());
            logger.info("Generated ephemeral CLPR mTLS leaf certificate (Ed25519, signed by ECDSA P-384 CA)");
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to generate the CLPR mTLS leaf certificate despite a configured CA", e);
        }
    }

    /** Returns {@code true} iff the leaf identity was generated successfully and mTLS is enabled. */
    public boolean isMtlsEnabled() {
        return credentials != null;
    }

    /** This node's ephemeral CLPR mTLS leaf identity (cert + key), or {@code null} if mTLS is disabled. */
    @Nullable
    public ClprLeafCredentials leafCredentials() {
        return credentials;
    }

    private static KeyPair generateEd25519KeyPair() throws Exception {
        // BC provider so the key is a BC EdDSA key (BCJSSE's TLS crypto rejects a SunEC Ed25519 key).
        return KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
                .generateKeyPair();
    }
}
