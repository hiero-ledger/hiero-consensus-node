// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.crypto;

import static com.swirlds.platform.crypto.CryptoStatic.generateKeysAndCerts;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandom;

import com.swirlds.common.io.streams.SerializableDataInputStreamImpl;
import com.swirlds.common.io.streams.SerializableDataOutputStreamImpl;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.common.test.fixtures.io.ResourceNotFoundException;
import com.swirlds.platform.crypto.SerializableX509Certificate;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility class for generating and retrieving pre-generated X.509 certificates for testing purposes.
 * <p>
 * Do Not Use In Production Code
 */
public class PreGeneratedX509Certs {

    public static final Path PATH_TO_RESOURCES = Path.of("src/test/resources/");
    public static final String SIG_CERT_FILE = "com/swirlds/platform/crypto/sigCerts.data";
    public static final String AGREE_CERT_FILE = "com/swirlds/platform/crypto/agrCerts.data";

    private static final Map<NodeId, SerializableX509Certificate> sigCerts = new HashMap<>();
    private static final Map<NodeId, SerializableX509Certificate> agreeCerts = new HashMap<>();

    /**
     * Utility class
     */
    private PreGeneratedX509Certs() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Generates a set of X.509 certificates for testing purposes.
     * <p>
     * The method must be called from within swirlds-common for the path to resolve to the right location.
     *
     * @param numCerts the number of certificates to generate
     * @param random   the random number generator to use
     */
    public static void generateCerts(final int numCerts, @NonNull final Random random)
            throws URISyntaxException, KeyStoreException, ExecutionException, InterruptedException, IOException {

        // path to the files to create
        final Path sigCertPath = PATH_TO_RESOURCES.resolve(SIG_CERT_FILE);
        final Path agreeCertPath = PATH_TO_RESOURCES.resolve(AGREE_CERT_FILE);

        // clean out old files if they exist.
        final File sigCertFile = sigCertPath.toFile();
        if (sigCertFile.exists()) {
            sigCertFile.delete();
        }

        final File agreeCertFile = agreeCertPath.toFile();
        if (agreeCertFile.exists()) {
            agreeCertFile.delete();
        }

        // create address book without any certs.
        final AddressBook addressBook =
                RandomAddressBookBuilder.create(random).withSize(numCerts).build();

        // generate certs for the address book.
        generateKeysAndCerts(addressBook);

        // autocloseable output streams to write the serializable certs.
        try (final SerializableDataOutputStream sigCertDos =
                        new SerializableDataOutputStreamImpl(new FileOutputStream(sigCertFile));
                final SerializableDataOutputStream agreeCertDos =
                        new SerializableDataOutputStreamImpl(new FileOutputStream(agreeCertFile))) {

            // record number of certs being written to each file.
            sigCertDos.writeInt(addressBook.getSize());
            agreeCertDos.writeInt(addressBook.getSize());

            // write the certs to their respective files.
            for (final Address address : addressBook) {
                final SerializableX509Certificate sigCert =
                        new SerializableX509Certificate(Objects.requireNonNull(address.getSigCert()));
                final SerializableX509Certificate agreeCert =
                        new SerializableX509Certificate(Objects.requireNonNull(address.getAgreeCert()));
                sigCerts.put(address.getNodeId(), sigCert);
                agreeCerts.put(address.getNodeId(), agreeCert);

                sigCertDos.writeSerializable(sigCert, false);
                agreeCertDos.writeSerializable(agreeCert, false);
            }
        }
    }

    /**
     * Generates a set of X.509 certificates for testing purposes.
     *
     * @param numCerts the number of certificates to generate
     */
    public static void generateCerts(final int numCerts)
            throws URISyntaxException, KeyStoreException, IOException, ExecutionException, InterruptedException {
        generateCerts(numCerts, getRandom());
    }

    /**
     * Retrieves a pre-generated X.509 certificate for signing purposes.
     *
     * @param nodeId the node ID
     * @return the X.509 certificate
     */
    @Nullable
    public static SerializableX509Certificate getSigCert(final long nodeId) {
        if (sigCerts.isEmpty()) {
            loadCerts();
            if (sigCerts.isEmpty()) {
                return null;
            }
        }
        long index = nodeId % sigCerts.size();
        return sigCerts.get(NodeId.of(index));
    }

    /**
     * Retrieves a pre-generated X.509 certificate for TLS agreement use.
     *
     * @param nodeId the node ID
     * @return the X.509 certificate
     */
    public static SerializableX509Certificate getAgreeCert(final long nodeId) {
        if (agreeCerts.isEmpty()) {
            loadCerts();
            if (agreeCerts.isEmpty()) {
                return null;
            }
        }
        long index = nodeId % agreeCerts.size();
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
            sigCertIs = ResourceLoader.loadFileAsStream(SIG_CERT_FILE);
            agreeCertIs = ResourceLoader.loadFileAsStream(AGREE_CERT_FILE);

            if (sigCertIs == null || agreeCertIs == null) {
                // certs need to be generated before they can be loaded.
                return;
            }
        } catch (final ResourceNotFoundException e) {
            // certs need to be generated before they can be loaded.
            return;
        }

        final SerializableDataInputStream sigCertDis = new SerializableDataInputStreamImpl(sigCertIs);
        final SerializableDataInputStream agreeCertDis = new SerializableDataInputStreamImpl(agreeCertIs);
        try {
            // load signing certs
            final int numSigCerts = sigCertDis.readInt();
            for (int i = 0; i < numSigCerts; i++) {
                SerializableX509Certificate sigCert =
                        sigCertDis.readSerializable(false, SerializableX509Certificate::new);
                sigCerts.put(NodeId.of(i), sigCert);
            }

            // load agreement certs
            final int numAgreeCerts = agreeCertDis.readInt();
            for (int i = 0; i < numAgreeCerts; i++) {
                SerializableX509Certificate agreeCert =
                        agreeCertDis.readSerializable(false, SerializableX509Certificate::new);
                agreeCerts.put(NodeId.of(i), agreeCert);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("critical failure in loading certificates", e);
        }
    }

    public static X509Certificate createBadCertificate() {
        return new X509Certificate() {
            @Override
            public void checkValidity() throws CertificateExpiredException, CertificateNotYetValidException {}

            @Override
            public void checkValidity(final Date date)
                    throws CertificateExpiredException, CertificateNotYetValidException {}

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
            public byte[] getTBSCertificate() throws CertificateEncodingException {
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
            public byte[] getEncoded() throws CertificateEncodingException {
                return new byte[0];
            }

            @Override
            public void verify(final PublicKey key)
                    throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException,
                            SignatureException {}

            @Override
            public void verify(final PublicKey key, final String sigProvider)
                    throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException,
                            SignatureException {}

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
