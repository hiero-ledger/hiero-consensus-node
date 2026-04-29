// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.crypto.KeyCertPurpose.AGREEMENT;
import static org.hiero.consensus.crypto.KeyCertPurpose.SIGNING;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hiero.base.concurrent.futures.FutureUtils;
import org.hiero.base.crypto.CertificateUtils;
import org.hiero.base.crypto.CryptoConstants;
import org.hiero.base.crypto.DetRandomProvider;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.crypto.SigningFactory;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class is responsible for generating the keys and certificates {@link KeysAndCerts} used in the system.
 * <p>
 * The algorithms and key sizes used here are chosen in accordance with the IAD-NSA Commercial National Security
 * Algorithm (CNSA) Suite, and TLS 1.2, as implemented by the SUN and SunEC security providers, using the JCE Unlimited
 * Strength Jurisdiction files. The TLS suite used here is called TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384. Java uses the
 * NIST p-384 curve specified by the CNSA for ECDH and ECDSA.
 */
public class KeysAndCertsGenerator {
    private static final int MASTER_KEY_MULTIPLIER = 157;
    private static final int SWIRLD_ID_MULTIPLIER = 163;
    private static final int BITS_IN_BYTE = 8;

    private static final int SIG_SEED = 2;
    private static final int AGR_SEED = 0;

    private KeysAndCertsGenerator() {}

    /**
     * Creates an instance holding all the keys and certificates. The key pairs are generated as a function of the
     * node ID.
     *
     * @param nodeId the node identifier
     */
    public static KeysAndCerts generate(final NodeId nodeId)
            throws NoSuchAlgorithmException, NoSuchProviderException, KeyStoreException, KeyGeneratingException {
        return generate(nodeId, SigningSchema.RSA);
    }

    /**
     * Creates an instance holding all the keys and certificates. The key pairs are generated as a function of the
     * node ID.
     *
     * @param nodeId the node identifier
     * @param schema the signing shema that determines the type of signing keys to generate
     */
    public static KeysAndCerts generate(final NodeId nodeId, @NonNull final SigningSchema schema)
            throws NoSuchAlgorithmException, NoSuchProviderException, KeyStoreException, KeyGeneratingException {

        final byte[] masterKey = new byte[CryptoConstants.SYM_KEY_SIZE_BYTES];
        final byte[] swirldId = new byte[CryptoConstants.HASH_SIZE_BYTES];
        final int i = (int) nodeId.id();
        for (int j = 0; j < masterKey.length; j++) {
            masterKey[j] = (byte) (j * MASTER_KEY_MULTIPLIER);
        }
        for (int j = 0; j < swirldId.length; j++) {
            swirldId[j] = (byte) (j * SWIRLD_ID_MULTIPLIER);
        }
        masterKey[0] = (byte) i;
        masterKey[1] = (byte) (i >> BITS_IN_BYTE);
        final byte[] memberId = intToBytes(i);

        // deterministic CSPRNG, used briefly then discarded
        final SecureRandom sigDetRandom = DetRandomProvider.getDetRandom();
        sigDetRandom.setSeed(masterKey);
        sigDetRandom.setSeed(swirldId);
        sigDetRandom.setSeed(memberId);
        sigDetRandom.setSeed(SIG_SEED);

        // deterministic CSPRNG, used briefly then discarded
        final SecureRandom agrDetRandom = DetRandomProvider.getDetRandom();
        agrDetRandom.setSeed(masterKey);
        agrDetRandom.setSeed(swirldId);
        agrDetRandom.setSeed(memberId);
        agrDetRandom.setSeed(AGR_SEED);

        return generate(nodeId, schema, sigDetRandom, agrDetRandom);
    }

    /**
     * Generated keys using the supplied randomness and creates certificates with those keys.
     *
     * @param nodeId       the node ID used for the certificate distinguished names
     * @param schema       the signing schema to use for the signing key pair
     * @param sigDetRandom the source of randomness for generating the signing key pair
     * @param agrDetRandom the source of randomness for generating the agreement key pair
     * @return the generated keys and certs
     */
    @NonNull
    public static KeysAndCerts generate(
            @NonNull final NodeId nodeId,
            @NonNull final SigningSchema schema,
            @NonNull final SecureRandom sigDetRandom,
            @NonNull final SecureRandom agrDetRandom)
            throws NoSuchAlgorithmException, NoSuchProviderException, KeyGeneratingException {
        final KeyPairGenerator agrKeyGen =
                KeyPairGenerator.getInstance(CryptoConstants.AGR_TYPE, CryptoConstants.AGR_PROVIDER);
        agrKeyGen.initialize(CryptoConstants.AGR_KEY_SIZE_BITS, agrDetRandom);

        final KeyPair sigKeyPair = SigningFactory.generateKeyPair(schema, sigDetRandom);
        final KeyPair agrKeyPair = agrKeyGen.generateKeyPair();

        final String dnS = CertificateUtils.distinguishedName(SIGNING.storeName(nodeId));
        final String dnA = CertificateUtils.distinguishedName(AGREEMENT.storeName(nodeId));

        // create the 2 certs (java.security.cert.Certificate)
        // both are signed by sigKeyPair, so sigCert is self-signed
        final X509Certificate sigCert = CertificateUtils.generateCertificate(
                dnS, sigKeyPair, dnS, sigKeyPair, sigDetRandom, schema.getSigningAlgorithm());
        final X509Certificate agrCert = CertificateUtils.generateCertificate(
                dnA, agrKeyPair, dnS, sigKeyPair, agrDetRandom, schema.getSigningAlgorithm());
        return new KeysAndCerts(sigKeyPair, agrKeyPair, sigCert, agrCert);
    }

    /**
     * Generates keys and certificates for all given node IDs in parallel using a cached thread pool. Each node's keys
     * are generated based on its {@link NodeId}.
     *
     * @param nodeIds the node IDs to generate keys for
     * @return a map of node IDs to their generated keys and certificates
     * @throws ExecutionException if key generation throws an exception
     * @throws InterruptedException if this thread is interrupted
     * @throws KeyStoreException if there is no provider that supports the required keystore type
     */
    @NonNull
    public static Map<NodeId, KeysAndCerts> generateKeysAndCerts(@NonNull final Collection<NodeId> nodeIds)
            throws ExecutionException, InterruptedException, KeyStoreException {
        final Map<NodeId, Future<KeysAndCerts>> futures = HashMap.newHashMap(nodeIds.size());
        try (final ExecutorService threadPool =
                Executors.newCachedThreadPool(new ThreadConfiguration(getStaticThreadManager())
                        .setComponent("crypto")
                        .setThreadName("crypto-generate")
                        .setDaemon(false)
                        .buildFactory())) {
            for (final NodeId nodeId : nodeIds) {
                futures.put(nodeId, threadPool.submit(() -> generate(nodeId)));
            }
            final Map<NodeId, KeysAndCerts> keysAndCerts = FutureUtils.awaitAll(futures);
            threadPool.shutdown();
            return keysAndCerts;
        }
    }

    /**
     * Generates a new agreement key pair using {@link SecureRandom#getInstanceStrong()} as the CSPRNG.
     *
     * @return the generated agreement key pair
     */
    @NonNull
    public static KeyPair generateAgreementKeyPair() throws NoSuchAlgorithmException, NoSuchProviderException {
        // getInstanceStrong() is no longer blocking - https://blogs.oracle.com/linux/post/rngd1
        final SecureRandom secureRandom = SecureRandom.getInstanceStrong();
        // generate the agreement key pair
        final KeyPairGenerator keyPairGenerator =
                KeyPairGenerator.getInstance(CryptoConstants.AGR_TYPE, CryptoConstants.AGR_PROVIDER);
        keyPairGenerator.initialize(CryptoConstants.AGR_KEY_SIZE_BITS, secureRandom);
        return keyPairGenerator.generateKeyPair();
    }

    private static byte[] intToBytes(final int value) {
        final byte[] dst = new byte[Integer.BYTES];
        for (int i = 0; i < Integer.BYTES; i++) {
            final int shift = i * 8;
            dst[i] = (byte) (0xff & (value >> shift));
        }
        return dst;
    }
}
