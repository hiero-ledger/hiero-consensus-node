// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.test.fixtures.crypto;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.hiero.consensus.exceptions.ResourceNotFoundException;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility class for generating and retrieving pre-generated X.509 certificates for testing purposes.
 * <p>
 * Do Not Use In Production Code
 */
public class PreGeneratedX509Certs {

    public static final String SIG_CERT_FILE = "sigCerts.data";
    public static final String AGREE_CERT_FILE = "agrCerts.data";

    private static final Map<NodeId, X509Certificate> sigCerts = new HashMap<>();
    private static final Map<NodeId, X509Certificate> agreeCerts = new HashMap<>();

    /**
     * Utility class
     */
    private PreGeneratedX509Certs() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Retrieves a pre-generated X.509 certificate for signing purposes.
     *
     * @param nodeId the node ID
     * @return the X.509 certificate
     */
    @Nullable
    public static X509Certificate getSigCert(final long nodeId) {
        if (sigCerts.isEmpty()) {
            loadCerts();
            if (sigCerts.isEmpty()) {
                return null;
            }
        }
        final long index = nodeId % sigCerts.size();
        return sigCerts.get(NodeId.of(index));
    }

    /**
     * Retrieves a pre-generated X.509 certificate for TLS agreement use.
     *
     * @param nodeId the node ID
     * @return the X.509 certificate
     */
    public static X509Certificate getAgreeCert(final long nodeId) {
        if (agreeCerts.isEmpty()) {
            loadCerts();
            if (agreeCerts.isEmpty()) {
                return null;
            }
        }
        final long index = nodeId % agreeCerts.size();
        return agreeCerts.get(NodeId.of(index));
    }

    /**
     * Loads pre-generated X.509 certificates from disk.  If the files do not exist, the method will return without
     * loading any certificates.
     */
    private static void loadCerts() {

        final InputStream sigCertIs;
        final InputStream agreeCertIs;
        try {
            sigCertIs = PreGeneratedX509Certs.class.getResourceAsStream(SIG_CERT_FILE);
            agreeCertIs = PreGeneratedX509Certs.class.getResourceAsStream(AGREE_CERT_FILE);

            if (sigCertIs == null || agreeCertIs == null) {
                // certs need to be generated before they can be loaded.
                return;
            }
        } catch (final ResourceNotFoundException e) {
            // certs need to be generated before they can be loaded.
            return;
        }

        final DataInputStream sigCertDis = new DataInputStream(sigCertIs);
        final DataInputStream agreeCertDis = new DataInputStream(agreeCertIs);
        try {
            // load signing certs
            final int numSigCerts = sigCertDis.readInt();
            for (int i = 0; i < numSigCerts; i++) {
                sigCertDis.readInt(); // read and ignore obsolete self serializable version
                sigCertDis.readInt(); // read and ignore obsolete self serializable array length
                final X509Certificate certificate = (X509Certificate)
                        CertificateFactory.getInstance("X.509").generateCertificate(sigCertDis);
                sigCerts.put(NodeId.of(i), certificate);
            }

            // load agreement certs
            final int numAgreeCerts = agreeCertDis.readInt();
            for (int i = 0; i < numAgreeCerts; i++) {
                agreeCertDis.readInt(); // read and ignore obsolete self serializable version
                agreeCertDis.readInt(); // read and ignore obsolete self serializable array length
                final X509Certificate certificate = (X509Certificate)
                        CertificateFactory.getInstance("X.509").generateCertificate(agreeCertDis);
                agreeCerts.put(NodeId.of(i), certificate);
            }
        } catch (final IOException | CertificateException e) {
            throw new IllegalStateException("critical failure in loading certificates", e);
        }
    }

    public static X509Certificate createBadCertificate() {
        return new X509Certificate() {
            @Override
            public void checkValidity() {}

            @Override
            public void checkValidity(final Date date) {}

            @Override
            public int getVersion() {
                return 0;
            }

            @Override
            public BigInteger getSerialNumber() {
                return null;
            }

            @Override
            public Principal getIssuerDN() {
                return null;
            }

            @Override
            public Principal getSubjectDN() {
                return null;
            }

            @Override
            public Date getNotBefore() {
                return null;
            }

            @Override
            public Date getNotAfter() {
                return null;
            }

            @Override
            public byte[] getTBSCertificate() {
                return new byte[0];
            }

            @Override
            public byte[] getSignature() {
                return new byte[0];
            }

            @Override
            public String getSigAlgName() {
                return "";
            }

            @Override
            public String getSigAlgOID() {
                return "";
            }

            @Override
            public byte[] getSigAlgParams() {
                return new byte[0];
            }

            @Override
            public boolean[] getIssuerUniqueID() {
                return new boolean[0];
            }

            @Override
            public boolean[] getSubjectUniqueID() {
                return new boolean[0];
            }

            @Override
            public boolean[] getKeyUsage() {
                return new boolean[0];
            }

            @Override
            public int getBasicConstraints() {
                return 0;
            }

            @Override
            public byte[] getEncoded() {
                return new byte[0];
            }

            @Override
            public void verify(final PublicKey key) {}

            @Override
            public void verify(final PublicKey key, final String sigProvider) {}

            @Override
            public String toString() {
                return "";
            }

            @Override
            public PublicKey getPublicKey() {
                return null;
            }

            @Override
            public boolean hasUnsupportedCriticalExtension() {
                return false;
            }

            @Override
            public Set<String> getCriticalExtensionOIDs() {
                return Set.of();
            }

            @Override
            public Set<String> getNonCriticalExtensionOIDs() {
                return Set.of();
            }

            @Override
            public byte[] getExtensionValue(final String oid) {
                return new byte[0];
            }
        };
    }
}
