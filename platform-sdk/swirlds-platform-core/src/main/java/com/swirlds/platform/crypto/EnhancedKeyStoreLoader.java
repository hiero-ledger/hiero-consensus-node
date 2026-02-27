// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.crypto.CryptoStatic.loadKeys;

import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.jcajce.JceInputDecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.hiero.base.crypto.KeystorePasswordPolicy;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.base.crypto.config.CryptoConfig_;
import org.hiero.consensus.crypto.CertificateUtils;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.crypto.KeyCertPurpose;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.node.NodeUtilities;
import org.hiero.consensus.roster.RosterUtils;

/**
 * This class is responsible for loading the key stores for all nodes in the address book.
 *
 * <p>
 * The {@link EnhancedKeyStoreLoader} class is a replacement for the now deprecated method.
 * This new implementation adds support for loading industry standard PEM formatted PKCS #8 private keys.
 * The legacy key stores are still supported but are no longer the preferred format.
 *
 * <p>
 * This implementation will attempt to load the private key stores in the following order:
 *     <ol>
 *         <li>Enhanced private key store ({@code [type]-private-[nodeName].pem})</li>
 *         <li>Legacy private key store ({@code private-[nodeName].pfx})</li>
 *     </ol>
 * <p>
 *
 * where {@code nodeName} is the string "node"+(NodeId+1)
 */
public class EnhancedKeyStoreLoader {
    /**
     * The constant message to use when the {@code nodeId} required parameter is {@code null}.
     */
    private static final String MSG_NODE_ID_NON_NULL = "nodeId must not be null";

    /**
     * The constant message to use when the {@code nodeAlias} required parameter is {@code null}.
     */
    private static final String MSG_NODE_ALIAS_NON_NULL = "nodeAlias must not be null";

    /**
     * The constant message to use when the {@code location} required parameter is {@code null}.
     */
    private static final String MSG_LOCATION_NON_NULL = "location must not be null";

    /**
     * The constant message to use when the {@code entry} required parameter is {@code null}.
     */
    private static final String MSG_ENTRY_NON_NULL = "entry must not be null";

    /**
     * The constant message to use when the {@code keyStoreDirectory} required parameter is {@code null}.
     */
    private static final String MSG_KEY_STORE_DIRECTORY_NON_NULL = "keyStoreDirectory must not be null";

    /**
     * The constant message to use when the {@code keyStorePassphrase} required parameter is {@code null}.
     */
    private static final String MSG_KEY_STORE_PASSPHRASE_NON_NULL = "keyStorePassphrase must not be null";

    /**
     * The constant message to use when the {@code localNodes} required parameter is {@code null}.
     */
    private static final String MSG_NODES_TO_START_NON_NULL = "the local nodes must not be null";

    /**
     * The constant message to use when the {@code rosterEntries} required parameter is {@code null}.
     */
    private static final String MSG_ROSTER_ENTRIES_NON_NULL = "rosterEntries must not be null";

    /**
     * The Log4j2 logger instance to use for all logging.
     */
    private static final Logger logger = LogManager.getLogger(EnhancedKeyStoreLoader.class);

    /**
     * The absolute path to the key store directory.
     */
    private final Path keyStoreDirectory;

    /**
     * The passphrase used to protect the key stores.
     */
    private final char[] keyStorePassphrase;

    /**
     * The private keys loaded from the key stores.
     */
    private final Map<NodeId, PrivateKey> sigPrivateKeys;

    /**
     * The X.509 Certificates loaded from the key stores.
     */
    private final Map<NodeId, Certificate> sigCertificates;

    /**
     * The private keys loaded from the key stores.
     */
    private final Map<NodeId, PrivateKey> agrPrivateKeys;

    /**
     * The X.509 Certificates loaded from the key stores.
     */
    private final Map<NodeId, Certificate> agrCertificates;

    /**
     * The list of {@link NodeId}s which must have a private key loaded.
     */
    private final Set<NodeId> nodeIds;

    /**
     * The list of {@link RosterEntry}s of the active roster.
     */
    private final List<RosterEntry> rosterEntries;

    /*
     * Static initializer to ensure the Bouncy Castle security provider is registered.
     */
    static {
        if (Arrays.stream(Security.getProviders()).noneMatch(p -> p instanceof BouncyCastleProvider)) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Constructs a new {@link EnhancedKeyStoreLoader} instance. Intentionally private to prevent direct instantiation.
     * Use the {@link #using(Configuration, Set, List)} method to create a new instance.
     *
     * @param keyStoreDirectory  the absolute path to the key store directory.
     * @param keyStorePassphrase the passphrase used to protect the key stores.
     * @param nodeIds            the set of local nodes that need private keys loaded
     * @param rosterEntries      roster entries of the active roster, used to provide certificates
     * @throws NullPointerException if {@code addressBook} or {@code configuration} is {@code null}.
     */
    private EnhancedKeyStoreLoader(
            @NonNull final Path keyStoreDirectory,
            @NonNull final char[] keyStorePassphrase,
            @NonNull final Set<NodeId> nodeIds,
            @NonNull final List<RosterEntry> rosterEntries) {
        this.keyStoreDirectory = Objects.requireNonNull(keyStoreDirectory, MSG_KEY_STORE_DIRECTORY_NON_NULL);
        this.keyStorePassphrase = Objects.requireNonNull(keyStorePassphrase, MSG_KEY_STORE_PASSPHRASE_NON_NULL);
        this.sigPrivateKeys = HashMap.newHashMap(nodeIds.size());
        this.sigCertificates = HashMap.newHashMap(nodeIds.size());
        this.agrPrivateKeys = HashMap.newHashMap(nodeIds.size());
        this.agrCertificates = HashMap.newHashMap(nodeIds.size());
        this.nodeIds = Collections.unmodifiableSet(Objects.requireNonNull(nodeIds, MSG_NODES_TO_START_NON_NULL));
        this.rosterEntries =
                Collections.unmodifiableList(Objects.requireNonNull(rosterEntries, MSG_ROSTER_ENTRIES_NON_NULL));
    }

    /**
     * Creates a new {@link EnhancedKeyStoreLoader} instance using the provided {@code addressBook} and
     * {@code configuration}.
     *
     * @param configuration the configuration to use for loading the key stores.
     * @param localNodes    the local nodes that need private keys loaded.
     * @param rosterEntries roster entries of the active roster, used to provide certificates
     * @return a new {@link EnhancedKeyStoreLoader} instance.
     * @throws NullPointerException     if {@code addressBook} or {@code configuration} is {@code null}.
     * @throws IllegalArgumentException if the value from the configuration element {@code crypto.keystorePassword} is
     *                                  {@code null} or blank.
     */
    @NonNull
    public static EnhancedKeyStoreLoader using(
            @NonNull final Configuration configuration,
            @NonNull final Set<NodeId> localNodes,
            @NonNull final List<RosterEntry> rosterEntries) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(localNodes, MSG_NODES_TO_START_NON_NULL);

        final String keyStorePassphrase =
                configuration.getConfigData(CryptoConfig.class).keystorePassword();
        final Path keyStoreDirectory =
                configuration.getConfigData(PathsConfig.class).getKeysDirPath();

        if (keyStorePassphrase == null || keyStorePassphrase.isBlank()) {
            throw new IllegalArgumentException("keyStorePassphrase must not be null or blank");
        }

        KeystorePasswordPolicy.warnIfNonCompliant(CryptoConfig_.KEYSTORE_PASSWORD, keyStorePassphrase);

        return new EnhancedKeyStoreLoader(
                keyStoreDirectory, keyStorePassphrase.toCharArray(), localNodes, rosterEntries);
    }

    /**
     * Scan the directory specified by {@code paths.keyDirPath} configuration element for key stores. This method will
     * process and load keys found in both the legacy or enhanced formats.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     */
    @NonNull
    public EnhancedKeyStoreLoader scan() throws KeyLoadingException, KeyStoreException {
        logger.debug(STARTUP.getMarker(), "Starting key store enumeration");

        for (final NodeId nodeId : this.nodeIds) {
            logger.debug(STARTUP.getMarker(), "Attempting to locate key stores for nodeId {}", nodeId);

            if (nodeIds.contains(nodeId)) {
                sigPrivateKeys.compute(nodeId, (k, v) -> resolveNodePrivateKey(nodeId));
            }

            sigCertificates.compute(nodeId, (k, v) -> resolveNodeCertificate(nodeId));
        }

        logger.trace(STARTUP.getMarker(), "Completed key store enumeration");
        return this;
    }

    /**
     * Iterates over the local nodes and creates the agreement key and certificate for each.  This method should be
     * called after {@link #scan()} and before {@link #verify()}.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws NoSuchAlgorithmException if the algorithm required to generate the key pair is not available.
     * @throws NoSuchProviderException  if the security provider required to generate the key pair is not available.
     * @throws KeyGeneratingException   if an error occurred while generating the agreement key pair.
     */
    public EnhancedKeyStoreLoader generate()
            throws NoSuchAlgorithmException, NoSuchProviderException, KeyGeneratingException {

        for (final NodeId nodeId : nodeIds) {
            if (!agrPrivateKeys.containsKey(nodeId)) {
                logger.info(STARTUP.getMarker(), "Generating agreement key pair for local nodeId {}", nodeId);
                // Generate a new agreement key since it does not exist
                final KeyPair agrKeyPair = KeysAndCertsGenerator.generateAgreementKeyPair();
                agrPrivateKeys.put(nodeId, agrKeyPair.getPrivate());

                // recover signing key pair to be root of trust on agreement certificate
                final PrivateKey privateSigningKey = sigPrivateKeys.get(nodeId);
                final X509Certificate signingCert = (X509Certificate) sigCertificates.get(nodeId);
                if (privateSigningKey == null || signingCert == null) {
                    continue;
                }
                final PublicKey publicSigningKey = signingCert.getPublicKey();
                final KeyPair signingKeyPair = new KeyPair(publicSigningKey, privateSigningKey);

                // generate the agreement certificate
                final String dnA = CertificateUtils.distinguishedName(KeyCertPurpose.AGREEMENT.storeName(nodeId));
                final X509Certificate agrCert = CertificateUtils.generateCertificate(
                        dnA,
                        agrKeyPair,
                        signingCert.getSubjectX500Principal().getName(),
                        signingKeyPair,
                        SecureRandom.getInstanceStrong(),
                        CryptoConstants.SIG_TYPE2);
                agrCertificates.put(nodeId, agrCert);
            }
        }
        return this;
    }

    /**
     * Verifies the presence of all required keys based on the address book provided during initialization.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyLoadingException if one or more of the required keys were not loaded.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not
     *                              initialized.
     */
    @NonNull
    public EnhancedKeyStoreLoader verify() throws KeyLoadingException, KeyStoreException {
        for (final NodeId nodeId : this.nodeIds) {
            try {
                if (!sigPrivateKeys.containsKey(nodeId)) {
                    throw new KeyLoadingException("No private key found for nodeId %s [ purpose = %s ]"
                            .formatted(nodeId, KeyCertPurpose.SIGNING));
                }

                if (!agrPrivateKeys.containsKey(nodeId)) {
                    throw new KeyLoadingException("No private key found for nodeId %s [purpose = %s ]"
                            .formatted(nodeId, KeyCertPurpose.AGREEMENT));
                }

                // the agreement certificate must be present for local nodes
                if (!agrCertificates.containsKey(nodeId)) {
                    throw new KeyLoadingException("No certificate found for nodeId %s [purpose = %s ]"
                            .formatted(nodeId, KeyCertPurpose.AGREEMENT));
                }

                if (!sigCertificates.containsKey(nodeId)) {
                    throw new KeyLoadingException("No certificate found for nodeId %s [purpose = %s ]"
                            .formatted(nodeId, KeyCertPurpose.SIGNING));
                }
            } catch (final KeyLoadingException e) {
                logger.warn(STARTUP.getMarker(), e.getMessage());
                throw e;
            }
        }

        return this;
    }

    /**
     * Creates a map containing the private keys for all local nodes and the public keys for all nodes using the
     * supplied address book.
     *
     * @return the map of all keys and certificates per {@link NodeId}.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not
     *                             initialized.
     * @throws KeyLoadingException if one or more of the required keys were not loaded or are not of the correct type.
     */
    @NonNull
    public Map<NodeId, KeysAndCerts> keysAndCerts() throws KeyStoreException, KeyLoadingException {
        final Map<NodeId, KeysAndCerts> keysAndCerts = HashMap.newHashMap(nodeIds.size());
        final Map<NodeId, X509Certificate> signing = signingCertificates();

        for (final NodeId nodeId : this.nodeIds) {
            final Certificate agrCert = agrCertificates.get(nodeId);
            final PrivateKey sigPrivateKey = sigPrivateKeys.get(nodeId);
            final PrivateKey agrPrivateKey = agrPrivateKeys.get(nodeId);

            if (sigPrivateKey == null) {
                throw new KeyLoadingException("No signing private key found for nodeId: %s".formatted(nodeId));
            }

            if (agrPrivateKey == null) {
                throw new KeyLoadingException("No agreement private key found for nodeId: %s".formatted(nodeId));
            }

            // the agreement certificate must be present for local nodes
            if (agrCert == null) {
                throw new KeyLoadingException("No agreement certificate found for nodeId: %s".formatted(nodeId));
            }

            if (!(agrCert instanceof final X509Certificate x509AgrCert)) {
                throw new KeyLoadingException("Illegal agreement certificate type for nodeId: %s [ purpose = %s ]"
                        .formatted(nodeId, KeyCertPurpose.AGREEMENT));
            }

            final X509Certificate sigCert = signing.get(nodeId);

            final KeyPair sigKeyPair = new KeyPair(sigCert.getPublicKey(), sigPrivateKey);
            final KeyPair agrKeyPair = new KeyPair(agrCert.getPublicKey(), agrPrivateKey);
            final KeysAndCerts kc = new KeysAndCerts(sigKeyPair, agrKeyPair, sigCert, x509AgrCert);

            keysAndCerts.put(nodeId, kc);
        }

        return keysAndCerts;
    }

    @NonNull
    private Map<NodeId, X509Certificate> signingCertificates() throws KeyLoadingException {
        final Map<NodeId, X509Certificate> certs = HashMap.newHashMap(nodeIds.size());
        for (final NodeId nodeId : this.nodeIds) {
            final Certificate sigCert = sigCertificates.get(nodeId);

            if (sigCert == null) {
                throw new KeyLoadingException("No signing certificate found for nodeId: %s".formatted(nodeId));
            }
            if (!(sigCert instanceof final X509Certificate x509SigCert)) {
                throw new KeyLoadingException("Illegal signing certificate type for nodeId: %s [ purpose = %s ]"
                        .formatted(nodeId, KeyCertPurpose.SIGNING));
            }
            certs.put(nodeId, x509SigCert);
        }
        return certs;
    }

    /**
     * Attempts to locate a private key for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}.
     *
     * <p>
     * This method will attempt to load the private key stores in the following order:
     * <ol>
     *     <li>Enhanced private key store ({@code [type]-private-[alias].pem})</li>
     *     <li>Legacy private key store ({@code private-[alias].pfx})</li>
     * </ol>
     *
     * @param nodeId the {@link NodeId} for which the private key should be loaded.
     * @return the private key for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}; otherwise,
     * {@code null} if no key was found.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, or {@code purpose} is {@code null}.
     */
    @Nullable
    private PrivateKey resolveNodePrivateKey(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);

        // Check for the enhanced private key store. The enhance key store is preferred over the legacy key store.
        Path ksLocation = privateKeyStore(nodeId);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found enhanced private key store for nodeId: {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    KeyCertPurpose.SIGNING,
                    ksLocation.getFileName());
            return readPrivateKey(nodeId, ksLocation);
        }

        // Check for the legacy private key store.
        ksLocation = legacyPrivateKeyStore(nodeId);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found legacy private key store for nodeId: {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    KeyCertPurpose.SIGNING,
                    ksLocation.getFileName());
            return readLegacyPrivateKey(nodeId, ksLocation, KeyCertPurpose.SIGNING.storeName(nodeId));
        }

        // No keys were found so return null. Missing keys will be detected during a call to
        // EnhancedKeyStoreLoader::verify() or EnhancedKeyStoreLoader::keysAndCerts().
        logger.warn(
                STARTUP.getMarker(),
                "No private key store found for nodeId: {} [ purpose = {} ]",
                nodeId,
                KeyCertPurpose.SIGNING);
        return null;
    }

    /**
     * Attempts to locate a certificate for the specified {@code nodeId}.
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @return the certificate for the specified {@code nodeId} otherwise, {@code null} if no certificate was found.
     * @throws NullPointerException if {@code nodeId} is {@code null}.
     */
    @Nullable
    private Certificate resolveNodeCertificate(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);

        return rosterEntries.stream()
                .filter(e -> e.nodeId() == nodeId.id())
                .map(RosterUtils::fetchGossipCaCertificate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Attempts to read a private key contained in an enhanced store from the specified {@code location} for the
     * specified {@code nodeId}.
     *
     * @param nodeId   the {@link NodeId} for which the private key should be loaded.
     * @param location the location of the enhanced private key store.
     * @return the private key for the specified {@code nodeId}; otherwise, {@code null} if no private key was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId} or {@code location} is {@code null}.
     */
    @Nullable
    PrivateKey readPrivateKey(@NonNull final NodeId nodeId, @NonNull final Path location) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);

        try {
            return readEnhancedStore(location);
        } catch (final KeyLoadingException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the enhanced private key store for nodeId: {} [ fileName = {} ]",
                    nodeId,
                    location.getFileName(),
                    e);
            return null;
        }
    }

    /**
     * Attempts to read a private key contained in the legacy store from the specified {@code location} for the
     * specified {@code nodeId} and {@code entryName}.
     *
     * @param nodeId    the {@link NodeId} for which the private key should be loaded.
     * @param location  the location of the legacy private key store.
     * @param entryName the name of the entry in the legacy private key store.
     * @return the private key for the specified {@code nodeId}; otherwise, {@code null} if no private key was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId}, {@code location}, or {@code entryName} is {@code null}.
     */
    @Nullable
    private PrivateKey readLegacyPrivateKey(
            @NonNull final NodeId nodeId, @NonNull final Path location, @NonNull final String entryName) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);

        try {
            final KeyStore ks = loadKeys(location, keyStorePassphrase);
            final Key k = ks.getKey(entryName, keyStorePassphrase);

            if (!(k instanceof PrivateKey)) {
                logger.warn(
                        STARTUP.getMarker(),
                        "No private key found for nodeId: {} [ entryName = {} ]",
                        nodeId,
                        entryName);
            }

            return (k instanceof final PrivateKey pk) ? pk : null;
        } catch (final KeyLoadingException
                | KeyStoreException
                | UnrecoverableKeyException
                | NoSuchAlgorithmException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the legacy private key store [ fileName = {} ]",
                    location.getFileName(),
                    e);
            return null;
        }
    }

    /**
     * Utility method for resolving the {@link Path} to the enhanced private key store for the specified
     * {@code nodeAlias} and {@code purpose}.
     *
     * @param nodeId the alias of the node for which the private key store should be loaded.
     * @return the {@link Path} to the enhanced private key store for the specified {@code nodeAlias} and
     * {@code purpose}.
     * @throws NullPointerException if {@code nodeAlias} or {@code purpose} is {@code null}.
     */
    @NonNull
    private Path privateKeyStore(@NonNull final NodeId nodeId) {
        return keyStoreDirectory.resolve(String.format(
                "%s-private-%s.pem", KeyCertPurpose.SIGNING.prefix(), NodeUtilities.formatNodeName(nodeId)));
    }

    /**
     * Utility method for resolving the {@link Path} to the legacy private key store for the specified
     * {@code nodeAlias}.
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @return the {@link Path} to the legacy private key store for the specified {@code nodeAlias}.
     * @throws NullPointerException if {@code nodeAlias} is {@code null}.
     */
    @NonNull
    private Path legacyPrivateKeyStore(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, MSG_NODE_ALIAS_NON_NULL);
        return keyStoreDirectory.resolve(String.format("private-%s.pfx", NodeUtilities.formatNodeName(nodeId)));
    }

    /**
     * Utility method for reading a private key from an enhanced key store at the specified
     * {@code location}.
     *
     * @param location the {@link Path} to the enhanced key store.
     * @return the private key from the key store.
     * @throws KeyLoadingException  if an error occurred while attempting to read the key store or the requested entry
     *                              was not found.
     * @throws NullPointerException if {@code location} is {@code null}.
     */
    @NonNull
    private PrivateKey readEnhancedStore(@NonNull final Path location) throws KeyLoadingException {
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);

        try (final PEMParser parser =
                new PEMParser(new InputStreamReader(Files.newInputStream(location), StandardCharsets.UTF_8))) {
            Object entry;

            while ((entry = parser.readObject()) != null) {
                if (entry instanceof PEMKeyPair
                        || entry instanceof PrivateKeyInfo
                        || entry instanceof PKCS8EncryptedPrivateKeyInfo
                        || entry instanceof PEMEncryptedKeyPair) {
                    break;
                }
            }

            if (entry == null) {
                throw new KeyLoadingException("No entry of the requested Private Key found [ fileName = %s ]"
                        .formatted(location.getFileName()));
            }

            return extractPrivateKeyEntity(entry);
        } catch (final IOException | DecoderException e) {
            throw new KeyLoadingException(
                    "Unable to read enhanced store [ fileName = %s ]".formatted(location.getFileName()), e);
        }
    }

    /**
     * Helper method used by {@link #readEnhancedStore(Path)} for extracting a {@link PrivateKey} from the
     * specified {@code entry}.
     *
     * @param entry the entry loaded from the store.
     * @return the {@link PrivateKey} extracted from the specified {@code entry}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the {@link PrivateKey} from the
     *                              specified {@code entry}.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    @NonNull
    private PrivateKey extractPrivateKeyEntity(@NonNull final Object entry) throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);

        try {
            final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            final PEMDecryptorProvider decrypter = new JcePEMDecryptorProviderBuilder().build(keyStorePassphrase);
            final InputDecryptorProvider inputDecrypter =
                    new JceInputDecryptorProviderBuilder().build(new String(keyStorePassphrase).getBytes());

            return switch (entry) {
                case final PrivateKeyInfo pki -> converter.getPrivateKey(pki);
                case final PKCS8EncryptedPrivateKeyInfo epki ->
                    converter.getPrivateKey(epki.decryptPrivateKeyInfo(inputDecrypter));
                case final PEMKeyPair kp -> converter.getPrivateKey(kp.getPrivateKeyInfo());
                case final PEMEncryptedKeyPair ekp ->
                    converter.getPrivateKey(ekp.decryptKeyPair(decrypter).getPrivateKeyInfo());
                default ->
                    throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                            .formatted(entry.getClass().getName()));
            };
        } catch (final IOException | PKCSException e) {
            throw new KeyLoadingException(
                    "Unable to extract a private key from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    // ----------------------------------------------------------------------------------------------
    //                                   MIGRATION METHODS
    // ----------------------------------------------------------------------------------------------

    /**
     * Performs any necessary migration steps to ensure the key storage is up-to-date.
     * <p>
     * As of release 0.56 the on-disk cryptography should reflect the following structure:
     * <ul>
     *     <li>s-private-alias.pem - the private signing key </li>
     *     <li>all *.pfx files moved to <b>OLD_PFX_KEYS</b> subdirectory and no longer used.</li>
     *     <li>all agreement key material is deleted from disk.</li>
     * </ul>
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     */
    @NonNull
    public EnhancedKeyStoreLoader migrate() throws KeyLoadingException, KeyStoreException {
        logger.info(STARTUP.getMarker(), "Starting key store migration");
        final Map<NodeId, PrivateKey> pfxPrivateKeys = new HashMap<>();

        // Delete agreement keys permanently. They are being created at startup by generateIfNecessary() after scan().
        deleteAgreementKeys();

        // Create PEM files for signing keys.
        long errorCount = extractPrivateKeysFromPfxFiles(pfxPrivateKeys);

        if (errorCount == 0) {
            // Validate only when there are no errors extracting pem files.
            errorCount = validateKeysAreLoadableFromPemFiles(pfxPrivateKeys);
        }

        if (errorCount > 0) {
            // Roll back due to errors.
            // This deletes any pem files created but leaves the agreement keys deleted.
            logger.error(STARTUP.getMarker(), "Due to {} errors, reverting pem file creation.", errorCount);
            rollBackSigningKeysChanges(pfxPrivateKeys);
        } else {
            // Clean up pfx files by moving them to a subdirectory.
            cleanupByMovingPfxFilesToSubDirectory();
            logger.info(STARTUP.getMarker(), "Finished key store migration.");
        }

        return this;
    }

    /**
     * Delete any agreement keys from the key store directory.
     */
    private void deleteAgreementKeys() {
        // delete any agreement keys of the form a-*
        final File[] agreementKeyFiles = keyStoreDirectory.toFile().listFiles((dir, name) -> name.startsWith("a-"));
        if (agreementKeyFiles != null) {
            for (final File agreementKeyFile : agreementKeyFiles) {
                if (agreementKeyFile.isFile()) {
                    try {
                        Files.delete(agreementKeyFile.toPath());
                        logger.debug(STARTUP.getMarker(), "Deleted agreement key file {}", agreementKeyFile.getName());
                    } catch (final IOException e) {
                        logger.error(
                                ERROR.getMarker(),
                                "Failed to delete agreement key file {}",
                                agreementKeyFile.getName());
                    }
                }
            }
        }
    }

    /**
     * Extracts the private keys from the PFX files and writes them to PEM files.
     *
     * @param pfxPrivateKeys the map of private keys being extracted (Updated By Method Call)
     * @return the number of errors encountered during the extraction process.
     */
    private long extractPrivateKeysFromPfxFiles(final Map<NodeId, PrivateKey> pfxPrivateKeys) {
        final AtomicLong errorCount = new AtomicLong(0);

        for (final NodeId nodeId : this.nodeIds) {
            // extract private keys for local nodes
            final Path sPrivateKeyLocation =
                    keyStoreDirectory.resolve(String.format("s-private-%s.pem", NodeUtilities.formatNodeName(nodeId)));
            final Path privateKs = legacyPrivateKeyStore(nodeId);
            if (!Files.exists(sPrivateKeyLocation) && Files.exists(privateKs)) {
                logger.info(
                        STARTUP.getMarker(),
                        "Extracting private signing key for nodeId: {} from file {}",
                        nodeId,
                        privateKs.getFileName());
                final PrivateKey privateKey =
                        readLegacyPrivateKey(nodeId, privateKs, KeyCertPurpose.SIGNING.storeName(nodeId));
                pfxPrivateKeys.put(nodeId, privateKey);
                if (privateKey == null) {
                    logger.error(
                            ERROR.getMarker(),
                            "Failed to extract private signing key for nodeId: {} from file {}",
                            nodeId,
                            privateKs.getFileName());
                    errorCount.incrementAndGet();
                } else {
                    logger.info(
                            STARTUP.getMarker(),
                            "Writing private signing key for nodeId: {} to PEM file {}",
                            nodeId,
                            sPrivateKeyLocation.getFileName());
                    try {
                        writePemFile(true, sPrivateKeyLocation, privateKey.getEncoded());
                    } catch (final IOException e) {
                        logger.error(
                                ERROR.getMarker(),
                                "Failed to write private key for nodeId: {} to PEM file {}",
                                nodeId,
                                sPrivateKeyLocation.getFileName());
                        errorCount.incrementAndGet();
                    }
                }
            }
        }
        return errorCount.get();
    }

    /**
     * Validates that the private keys in PEM files are loadable and match the PFX loaded keys.
     *
     * @param pfxPrivateKeys the map of private keys being extracted.
     * @return the number of errors encountered during the validation process.
     */
    private long validateKeysAreLoadableFromPemFiles(final Map<NodeId, PrivateKey> pfxPrivateKeys) {
        final AtomicLong errorCount = new AtomicLong(0);
        rosterEntries.stream()
                .map(e -> NodeId.of(e.nodeId()))
                .filter(this.nodeIds::contains)
                .forEach(nodeId -> {
                    // validate private keys for local nodes
                    final Path ksLocation = privateKeyStore(nodeId);
                    final PrivateKey pemPrivateKey = readPrivateKey(nodeId, ksLocation);
                    if (pemPrivateKey == null
                            || (pfxPrivateKeys.get(nodeId) != null
                                    && !Arrays.equals(
                                            pemPrivateKey.getEncoded(),
                                            pfxPrivateKeys.get(nodeId).getEncoded()))) {
                        logger.error(
                                ERROR.getMarker(),
                                "Private key for nodeId: {} does not match the migrated key",
                                nodeId);
                        errorCount.incrementAndGet();
                    }
                });
        return errorCount.get();
    }

    /**
     * Rollback the creation of PEM files for signing keys.
     *
     * @param pfxPrivateKeys the map of private keys being extracted.
     */
    private void rollBackSigningKeysChanges(final Map<NodeId, PrivateKey> pfxPrivateKeys) {

        final AtomicLong cleanupErrorCount = new AtomicLong(0);
        for (final NodeId nodeId : this.nodeIds) {
            // private key rollback
            if (pfxPrivateKeys.containsKey(nodeId)) {
                try {
                    Files.deleteIfExists(privateKeyStore(nodeId));
                } catch (final IOException e) {
                    cleanupErrorCount.incrementAndGet();
                }
            }
        }
        if (cleanupErrorCount.get() > 0) {
            logger.error(
                    ERROR.getMarker(),
                    "Failed to rollback {} pem files created. Manual cleanup required.",
                    cleanupErrorCount.get());
            throw new IllegalStateException("Cryptography Migration failed to generate or validate PEM files.");
        }
    }

    /**
     * Move the PFX files to the OLD_PFX_KEYS subdirectory.
     */
    private void cleanupByMovingPfxFilesToSubDirectory() {
        final AtomicLong cleanupErrorCount = new AtomicLong(0);
        final AtomicBoolean doCleanup = new AtomicBoolean(false);
        for (final NodeId nodeId : this.nodeIds) {
            // move private key PFX files per local node
            final File sPrivatePfx = legacyPrivateKeyStore(nodeId).toFile();
            if (sPrivatePfx.exists() && sPrivatePfx.isFile()) {
                doCleanup.set(true);
            }
        }

        if (!doCleanup.get()) return;

        final String archiveDirectory = ".archive";
        final String now = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now());
        final String newDirectory = archiveDirectory + File.pathSeparator + now;
        final Path pfxArchiveDirectory = keyStoreDirectory.resolve(archiveDirectory);
        final Path pfxDateDirectory = pfxArchiveDirectory.resolve(now);

        logger.info(STARTUP.getMarker(), "Cryptography Migration Cleanup: Moving PFX files to {}", pfxDateDirectory);
        if (!Files.exists(pfxDateDirectory)) {
            try {
                if (!Files.exists(pfxArchiveDirectory)) {
                    Files.createDirectory(pfxArchiveDirectory);
                }
                Files.createDirectory(pfxDateDirectory);
            } catch (final IOException e) {
                logger.error(
                        ERROR.getMarker(),
                        "Failed to create [{}] subdirectory. Manual cleanup required.",
                        newDirectory);
                return;
            }
        }
        for (final NodeId nodeId : this.nodeIds) {
            // move private key PFX files per local node
            final File sPrivatePfx = legacyPrivateKeyStore(nodeId).toFile();
            if (sPrivatePfx.exists()
                    && sPrivatePfx.isFile()
                    && !sPrivatePfx.renameTo(
                            pfxDateDirectory.resolve(sPrivatePfx.getName()).toFile())) {
                cleanupErrorCount.incrementAndGet();
            }
        }
        if (cleanupErrorCount.get() > 0) {
            logger.error(
                    ERROR.getMarker(),
                    "Failed to move {} PFX files to [{}] subdirectory. Manual cleanup required.",
                    cleanupErrorCount.get(),
                    newDirectory);
            throw new IllegalStateException(
                    "Cryptography Migration failed to move PFX files to [" + newDirectory + "] subdirectory.");
        }
    }

    /**
     * Write the provided encoded key or certificate as a base64 DER encoded PEM file to the provided location.
     *
     * @param isPrivateKey true if the encoded data is a private key; false if it is a certificate.
     * @param location     the location to write the PEM file.
     * @param encoded      the byte encoded data to write to the PEM file.
     * @throws IOException if an error occurred while writing the PEM file.
     */
    public static void writePemFile(
            final boolean isPrivateKey, @NonNull final Path location, @NonNull final byte[] encoded)
            throws IOException {
        final PemObject pemObj = new PemObject(isPrivateKey ? "PRIVATE KEY" : "CERTIFICATE", encoded);
        try (final FileOutputStream file = new FileOutputStream(location.toFile(), false);
                final var out = new OutputStreamWriter(file);
                final PemWriter writer = new PemWriter(out)) {
            writer.writeObject(pemObj);
            file.getFD().sync();
        }
    }
}
