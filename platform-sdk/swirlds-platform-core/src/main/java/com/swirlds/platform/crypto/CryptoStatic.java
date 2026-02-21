// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static com.swirlds.logging.legacy.LogMarker.CERTIFICATES;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.exceptions.ThrowableUtilities;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * A collection of various static crypto methods
 */
public final class CryptoStatic {
    private static final Logger logger = LogManager.getLogger(CryptoStatic.class);
    private static final String LOCAL_NODES_MUST_NOT_BE_NULL = "the local nodes must not be null";

    static {
        // used to generate certificates
        Security.addProvider(new BouncyCastleProvider());
    }

    private CryptoStatic() {}

    /**
     * Loads all data from a .pfx file into a KeyStore
     *
     * @param file     the file to load from
     * @param password the encryption password
     * @return a KeyStore with all certificates and keys found in the file
     * @throws KeyStoreException   if {@link ConsensusCryptoUtils#createEmptyTrustStore()} throws
     * @throws KeyLoadingException if the file is empty or another issue occurs while reading it
     */
    @NonNull
    public static KeyStore loadKeys(@NonNull final Path file, @NonNull final char[] password)
            throws KeyStoreException, KeyLoadingException {
        final KeyStore store = ConsensusCryptoUtils.createEmptyTrustStore();
        try (final FileInputStream fis = new FileInputStream(file.toFile())) {
            store.load(fis, password);
            if (store.size() == 0) {
                throw new KeyLoadingException("there are no valid keys or certificates in " + file);
            }
        } catch (final IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException e) {
            throw new KeyLoadingException("there was a problem reading: " + file, e);
        }

        return store;
    }

    /**
     * This method is designed to generate all a user's keys from their master key, to help with key recovery if their
     * computer is erased.
     * <p>
     * We follow the "CNSA Suite" (Commercial National Security Algorithm), which is the current US government standard
     * for protecting information up to and including Top Secret:
     * <p>
     * <a
     * href="https://www.iad.gov/iad/library/ia-guidance/ia-solutions-for-classified/algorithm-guidance/commercial-national-security-algorithm-suite-factsheet.cfm">...</a>
     * <p>
     * The CNSA standard specifies AES-256, SHA-384, RSA, ECDH and ECDSA. So that is what is used here. Their intent
     * appears to be that AES and SHA will each have 128 bits of post-quantum security, against Grover's and the BHT
     * algorithm, respectively. Of course, ECDH and ECDSA aren't post-quantum, but AES-256 and SHA-384 are (as far as we
     * know).
     *
     * @param nodeIds The nodeIds to generate keys for
     * @throws ExecutionException   if key generation throws an exception, it will be wrapped in an ExecutionException
     * @throws InterruptedException if this thread is interrupted
     * @throws KeyStoreException    if there is no provider that supports the required keystore type
     */
    @NonNull
    public static Map<NodeId, KeysAndCerts> generateKeysAndCerts(final @NonNull Collection<NodeId> nodeIds)
            throws ExecutionException, InterruptedException, KeyStoreException {
        final Map<NodeId, Future<KeysAndCerts>> futures = HashMap.newHashMap(nodeIds.size());
        try (final ExecutorService threadPool =
                Executors.newCachedThreadPool(new ThreadConfiguration(getStaticThreadManager())
                        .setComponent("browser")
                        .setThreadName("crypto-generate")
                        .setDaemon(false)
                        .buildFactory())) {
            for (final NodeId nodeId : nodeIds) {
                // Crypto objects will be created in parallel. The process of creating a Crypto object is
                // very CPU intensive even if the keys are loaded from the hard drive, so making it parallel
                // greatly reduces the time it takes to create them all.
                futures.put(nodeId, threadPool.submit(() -> KeysAndCertsGenerator.generate(nodeId)));
            }
            final Map<NodeId, KeysAndCerts> keysAndCerts = futuresToMap(futures);
            threadPool.shutdown();
            // After the keys have been generated or loaded, they are then copied to the address book
            return keysAndCerts;
        }
    }

    /**
     * Wait for all futures to finish and return the results as an array
     *
     * @param futures the futures to wait for
     * @param <T>     the result and array type
     * @return all results
     * @throws ExecutionException   if {@link Future#get} throws
     * @throws InterruptedException if {@link Future#get} throws
     */
    @NonNull
    private static <T> Map<NodeId, T> futuresToMap(@NonNull final Map<NodeId, Future<T>> futures)
            throws ExecutionException, InterruptedException {
        final Map<NodeId, T> map = new HashMap<>();
        for (final Map.Entry<NodeId, Future<T>> entry : futures.entrySet()) {
            map.put(entry.getKey(), entry.getValue().get());
        }
        return map;
    }

    /**
     * Create {@link KeysAndCerts} object for the given node id.
     *
     * @param configuration the current configuration
     * @param localNode     the local node that need private keys loaded
     * @param rosterEntries roster entries of the active roster, used to provide certificates
     * @return keys and certificates for the requested node id
     */
    public static KeysAndCerts initNodeSecurity(
            @NonNull final Configuration configuration,
            @NonNull final NodeId localNode,
            @NonNull final List<RosterEntry> rosterEntries) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(localNode, LOCAL_NODES_MUST_NOT_BE_NULL);

        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);

        final Map<NodeId, KeysAndCerts> keysAndCerts;
        try {
            try (final Stream<Path> list = Files.list(pathsConfig.getKeysDirPath())) {
                CommonUtils.tellUserConsole("Reading crypto keys from the files here:   "
                        + Arrays.toString(list.map(p -> p.getFileName().toString())
                                .filter(fileName -> fileName.endsWith("pfx") || fileName.endsWith("pem"))
                                .toArray()));
            }

            logger.debug(STARTUP.getMarker(), "About to start loading keys");
            logger.debug(STARTUP.getMarker(), "Reading keys using the enhanced key loader");
            keysAndCerts = EnhancedKeyStoreLoader.using(configuration, Set.of(localNode), rosterEntries)
                    .migrate()
                    .scan()
                    .generate()
                    .verify()
                    .keysAndCerts();

            logger.debug(STARTUP.getMarker(), "Done loading keys");
        } catch (final KeyStoreException
                | KeyLoadingException
                | NoSuchAlgorithmException
                | IOException
                | KeyGeneratingException
                | NoSuchProviderException e) {
            logger.error(EXCEPTION.getMarker(), "Exception while loading/generating keys", e);
            if (ThrowableUtilities.isRootCauseSuppliedType(e, NoSuchAlgorithmException.class)
                    || ThrowableUtilities.isRootCauseSuppliedType(e, NoSuchProviderException.class)) {
                CommonUtils.tellUserConsolePopup(
                        "ERROR",
                        "ERROR: This Java installation does not have the needed cryptography " + "providers installed");
            }
            SystemExitUtils.exitSystem(SystemExitCode.KEY_LOADING_FAILED);
            throw new CryptographyException(e); // will never reach this line due to exit above
        }

        final String msg = "Certificate loaded: {}";

        keysAndCerts.forEach((nodeId, keysAndCertsForNode) -> {
            if (keysAndCertsForNode == null) {
                logger.error(EXCEPTION.getMarker(), "No keys and certs for node {}", nodeId);
                return;
            }
            logger.debug(CERTIFICATES.getMarker(), "Node ID: {}", nodeId);
            logger.debug(CERTIFICATES.getMarker(), msg, keysAndCertsForNode.sigCert());
            logger.debug(CERTIFICATES.getMarker(), msg, keysAndCertsForNode.agrCert());
        });

        return keysAndCerts.get(localNode);
    }
}
