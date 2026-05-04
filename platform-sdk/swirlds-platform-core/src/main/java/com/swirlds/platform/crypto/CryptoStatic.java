// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static com.swirlds.logging.legacy.LogMarker.CERTIFICATES;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.node.state.roster.RosterEntry;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.utility.CommonUtils;
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
     * @throws KeyStoreException   if {@link CryptoUtils#createEmptyTrustStore()} throws
     * @throws KeyLoadingException if the file is empty or another issue occurs while reading it
     */
    @NonNull
    public static KeyStore loadKeys(@NonNull final Path file, @NonNull final char[] password)
            throws KeyStoreException, KeyLoadingException {
        final KeyStore store = CryptoUtils.createEmptyTrustStore();
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
                org.hiero.base.utility.CommonUtils.tellUserConsole("Reading crypto keys from the files here:   "
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
                CommonUtils.tellUserConsoleHighlighted(
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
