// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static com.swirlds.logging.legacy.LogMarker.ERROR;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.crypto.CryptoConstants.PUBLIC_KEYS_FILE;
import static com.swirlds.platform.crypto.CryptoStatic.copyPublicKeys;
import static com.swirlds.platform.crypto.CryptoStatic.createEmptyTrustStore;
import static com.swirlds.platform.crypto.CryptoStatic.loadKeys;

import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
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
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
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
import org.hiero.consensus.model.node.NodeId;

/**
 * This class is responsible for loading the key stores for all nodes in the address book.
 *
 * <p>
 * The {@link EnhancedKeyStoreLoader} class is a replacement for the now deprecated
 * {@link CryptoStatic#loadKeysAndCerts(AddressBook, Path, char[], Set<NodeId>)} method. This new implementation adds support for
 * loading industry standard PEM formatted PKCS #8 private keys and X.509 certificates. The legacy key stores are still
 * supported, but are no longer the preferred format.
 *
 * <p>
 * This implementation will attempt to load the private key stores in the following order:
 *     <ol>
 *         <li>Enhanced private key store ({@code [type]-private-[nodeName].pem})</li>
 *         <li>Legacy private key store ({@code private-[nodeName].pfx})</li>
 *     </ol>
 * <p>
 *     Public key stores are loaded in the following order:
 *     <ol>
 *          <li>Enhanced certificate store ({@code [type]-public-[nodeName].pem})</li>
 *          <li>Legacy certificate store ({@code public.pfx})</li>
 *     </ol>
 *     where {@code nodeName} is the string "node"+(NodeId+1)
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
     * The constant message to use when the {@code purpose} required parameter is {@code null}.
     */
    private static final String MSG_PURPOSE_NON_NULL = "purpose must not be null";

    /**
     * The constant message to use when the {@code legacyPublicStore} required parameter is {@code null}.
     */
    private static final String MSG_LEGACY_PUBLIC_STORE_NON_NULL = "legacyPublicStore must not be null";

    /**
     * The constant message to use when the {@code location} required parameter is {@code null}.
     */
    private static final String MSG_LOCATION_NON_NULL = "location must not be null";

    /**
     * The constant message to use when the {@code entryType} required parameter is {@code null}.
     */
    private static final String MSG_ENTRY_TYPE_NON_NULL = "entryType must not be null";

    /**
     * The constant message to use when the {@code entry} required parameter is {@code null}.
     */
    private static final String MSG_ENTRY_NON_NULL = "entry must not be null";

    /**
     * The constant message to use when the {@code addressBook} required parameter is {@code null}.
     */
    private static final String MSG_ADDRESS_BOOK_NON_NULL = "addressBook must not be null";

    /**
     * The constant message to use when the {@code function} required parameter is {@code null}.
     */
    private static final String MSG_FUNCTION_NON_NULL = "function must not be null";

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
     * The Log4j2 logger instance to use for all logging.
     */
    private static final Logger logger = LogManager.getLogger(EnhancedKeyStoreLoader.class);

    /**
     * The address book to use for loading the key stores.
     */
    private final AddressBook addressBook;

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
    private final Set<NodeId> localNodes;

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
     * Use the {@link #using(AddressBook, Configuration, Set<NodeId>)} method to create a new instance.
     *
     * @param addressBook        the address book to use for loading the key stores.
     * @param keyStoreDirectory  the absolute path to the key store directory.
     * @param keyStorePassphrase the passphrase used to protect the key stores.
     * @param localNodes         the set of local nodes that need private keys loaded
     * @throws NullPointerException if {@code addressBook} or {@code configuration} is {@code null}.
     */
    private EnhancedKeyStoreLoader(
            @NonNull final AddressBook addressBook,
            @NonNull final Path keyStoreDirectory,
            @NonNull final char[] keyStorePassphrase,
            @NonNull final Set<NodeId> localNodes) {
        this.addressBook = Objects.requireNonNull(addressBook, MSG_ADDRESS_BOOK_NON_NULL);
        this.keyStoreDirectory = Objects.requireNonNull(keyStoreDirectory, MSG_KEY_STORE_DIRECTORY_NON_NULL);
        this.keyStorePassphrase = Objects.requireNonNull(keyStorePassphrase, MSG_KEY_STORE_PASSPHRASE_NON_NULL);
        this.sigPrivateKeys = HashMap.newHashMap(addressBook.getSize());
        this.sigCertificates = HashMap.newHashMap(addressBook.getSize());
        this.agrPrivateKeys = HashMap.newHashMap(addressBook.getSize());
        this.agrCertificates = HashMap.newHashMap(addressBook.getSize());
        this.localNodes = Collections.unmodifiableSet(Objects.requireNonNull(localNodes, MSG_NODES_TO_START_NON_NULL));
    }

    /**
     * Creates a new {@link EnhancedKeyStoreLoader} instance using the provided {@code addressBook} and
     * {@code configuration}.
     *
     * @param addressBook   the address book to use for loading the key stores.
     * @param configuration the configuration to use for loading the key stores.
     * @param localNodes    the local nodes that need private keys loaded.
     * @return a new {@link EnhancedKeyStoreLoader} instance.
     * @throws NullPointerException     if {@code addressBook} or {@code configuration} is {@code null}.
     * @throws IllegalArgumentException if the value from the configuration element {@code crypto.keystorePassword} is
     *                                  {@code null} or blank.
     */
    @NonNull
    public static EnhancedKeyStoreLoader using(
            @NonNull final AddressBook addressBook,
            @NonNull final Configuration configuration,
            @NonNull final Set<NodeId> localNodes) {
        Objects.requireNonNull(addressBook, MSG_ADDRESS_BOOK_NON_NULL);
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(localNodes, MSG_NODES_TO_START_NON_NULL);

        final String keyStorePassphrase =
                configuration.getConfigData(CryptoConfig.class).keystorePassword();
        final Path keyStoreDirectory =
                configuration.getConfigData(PathsConfig.class).getKeysDirPath();

        if (keyStorePassphrase == null || keyStorePassphrase.isBlank()) {
            throw new IllegalArgumentException("keyStorePassphrase must not be null or blank");
        }

        return new EnhancedKeyStoreLoader(addressBook, keyStoreDirectory, keyStorePassphrase.toCharArray(), localNodes);
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
        final KeyStore legacyPublicStore = resolveLegacyPublicStore();

        iterateAddressBook(addressBook, (nodeId, address) -> {
            logger.debug(STARTUP.getMarker(), "Attempting to locate key stores for nodeId {}", nodeId);

            if (localNodes.contains(address.getNodeId())) {
                sigPrivateKeys.compute(nodeId, (k, v) -> resolveNodePrivateKey(nodeId, KeyCertPurpose.SIGNING));
            }

            sigCertificates.compute(
                    nodeId, (k, v) -> resolveNodeCertificate(nodeId, KeyCertPurpose.SIGNING, legacyPublicStore));
        });

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

        for (final NodeId nodeId : localNodes) {
            if (!agrPrivateKeys.containsKey(nodeId)) {
                logger.info(STARTUP.getMarker(), "Generating agreement key pair for local nodeId {}", nodeId);
                // Generate a new agreement key since it does not exist
                final KeyPair agrKeyPair = KeysAndCerts.generateAgreementKeyPair();
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
                final String dnA = CryptoStatic.distinguishedName(KeyCertPurpose.AGREEMENT.storeName(nodeId));
                final X509Certificate agrCert = CryptoStatic.generateCertificate(
                        dnA,
                        agrKeyPair,
                        signingCert.getSubjectX500Principal().getName(),
                        signingKeyPair,
                        SecureRandom.getInstanceStrong());
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
     */
    @NonNull
    public EnhancedKeyStoreLoader verify() throws KeyLoadingException, KeyStoreException {
        return verify(addressBook);
    }

    /**
     * Verifies the presence of all required keys for the nodes being started based on the supplied address book.
     *
     * @param validatingBook the address book to use for validation
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not
     *                              initialized.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public EnhancedKeyStoreLoader verify(@NonNull final AddressBook validatingBook)
            throws KeyLoadingException, KeyStoreException {
        Objects.requireNonNull(validatingBook, "validatingBook must not be null");

        if (addressBook.getSize() != validatingBook.getSize()) {
            throw new KeyLoadingException(
                    "The validating address book size differs from the address book used during initialization [ validatingSize = %d, initializedSize = %d ]"
                            .formatted(validatingBook.getSize(), addressBook.getSize()));
        }

        iterateAddressBook(validatingBook, (nodeId, address) -> {
            try {
                if (localNodes.contains(address.getNodeId())) {
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
                }

                if (!sigCertificates.containsKey(nodeId)) {
                    throw new KeyLoadingException("No certificate found for nodeId %s [purpose = %s ]"
                            .formatted(nodeId, KeyCertPurpose.SIGNING));
                }
            } catch (KeyLoadingException e) {
                logger.warn(STARTUP.getMarker(), e.getMessage());
                throw e;
            }
        });

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
        return keysAndCerts(addressBook);
    }

    /**
     * Creates a map containing the private keys for all local nodes and the public keys for all nodes using the
     * supplied address book.
     *
     * @return the map of all keys and certificates per {@link NodeId}.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not
     *                              initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public Map<NodeId, KeysAndCerts> keysAndCerts(@NonNull final AddressBook validatingBook)
            throws KeyStoreException, KeyLoadingException {
        Objects.requireNonNull(validatingBook, "validatingBook must not be null");

        final Map<NodeId, KeysAndCerts> keysAndCerts = HashMap.newHashMap(validatingBook.getSize());
        final PublicStores publicStores = publicStores(validatingBook);

        iterateAddressBook(validatingBook, (nodeId, address) -> {
            final X509Certificate sigCert = publicStores.getCertificate(KeyCertPurpose.SIGNING, nodeId);

            if (sigCert == null) {
                throw new KeyLoadingException("No signing certificate found for nodeId: %s".formatted(nodeId));
            }

            if (localNodes.contains(nodeId)) {
                final X509Certificate agrCert = publicStores.getCertificate(KeyCertPurpose.AGREEMENT, nodeId);
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

                final KeyPair sigKeyPair = new KeyPair(sigCert.getPublicKey(), sigPrivateKey);
                final KeyPair agrKeyPair = new KeyPair(agrCert.getPublicKey(), agrPrivateKey);
                final KeysAndCerts kc = new KeysAndCerts(sigKeyPair, agrKeyPair, sigCert, agrCert);

                keysAndCerts.put(nodeId, kc);
            }
        });

        return keysAndCerts;
    }

    /**
     * Injects the public keys for all nodes into the address book provided during initialization.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not
     *                             initialized.
     * @throws KeyLoadingException if one or more of the required keys were not loaded or are not of the correct type.
     */
    @NonNull
    public EnhancedKeyStoreLoader injectInAddressBook() throws KeyLoadingException, KeyStoreException {
        return injectInAddressBook(addressBook);
    }

    /**
     * Injects the public keys for all nodes into the supplied address book.
     *
     * @param validatingBook the address book into which the public keys should be injected.
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not
     *                              initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public EnhancedKeyStoreLoader injectInAddressBook(@NonNull final AddressBook validatingBook)
            throws KeyStoreException, KeyLoadingException {
        final PublicStores publicStores = publicStores(validatingBook);
        copyPublicKeys(publicStores, validatingBook);
        return this;
    }

    /**
     * Creates a new {@link PublicStores} instance containing the public keys for all nodes using the supplied address
     * book.
     *
     * @param validatingBook the address book to use for loading the public keys.
     * @return the {@link PublicStores} instance containing the public keys for all nodes in the supplied address book.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not
     *                              initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public PublicStores publicStores(@NonNull final AddressBook validatingBook)
            throws KeyStoreException, KeyLoadingException {
        final PublicStores publicStores = new PublicStores();

        iterateAddressBook(validatingBook, (nodeId, address) -> {
            final Certificate sigCert = sigCertificates.get(nodeId);
            final Certificate agrCert = agrCertificates.get(nodeId);

            if (!(sigCert instanceof X509Certificate)) {
                throw new KeyLoadingException("Illegal signing certificate type for nodeId: %s [ purpose = %s ]"
                        .formatted(nodeId, KeyCertPurpose.SIGNING));
            }

            if (localNodes.contains(nodeId)) {
                // The agreement certificate is loaded by the local nodes and provided to peers through mTLS handshaking
                logger.trace(
                        STARTUP.getMarker(),
                        "Injecting agreement certificate for local nodeId {} into public stores",
                        nodeId);
                if (!(agrCert instanceof X509Certificate)) {
                    throw new KeyLoadingException("Illegal agreement certificate type for nodeId: %s [ purpose = %s ]"
                            .formatted(nodeId, KeyCertPurpose.AGREEMENT));
                }
                publicStores.setCertificate(KeyCertPurpose.AGREEMENT, (X509Certificate) agrCert, nodeId);
            }

            publicStores.setCertificate(KeyCertPurpose.SIGNING, (X509Certificate) sigCert, nodeId);
        });

        return publicStores;
    }

    /**
     * Attempts to locate the legacy (combined) public key store and load it.
     *
     * @return the legacy public key store fully loaded; otherwise, an empty key store.
     * @throws KeyLoadingException if the legacy public key store cannot be loaded or is empty.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not
     *                             initialized.
     */
    @NonNull
    private KeyStore resolveLegacyPublicStore() throws KeyLoadingException, KeyStoreException {
        final Path legacyStorePath = legacyCertificateStore();

        logger.trace(STARTUP.getMarker(), "Searching for the legacy public key store [ path = {} ]", legacyStorePath);

        if (Files.exists(legacyStorePath)) {
            logger.debug(STARTUP.getMarker(), "Loading the legacy public key store [ path = {} ]", legacyStorePath);
            return loadKeys(legacyStorePath, keyStorePassphrase);
        }

        logger.debug(STARTUP.getMarker(), "No Legacy public key store found");
        return createEmptyTrustStore();
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
     * @param nodeId    the {@link NodeId} for which the private key should be loaded.
     * @param purpose   the {@link KeyCertPurpose} for which the private key should be loaded.
     * @return the private key for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}; otherwise,
     * {@code null} if no key was found.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, or {@code purpose} is {@code null}.
     */
    @Nullable
    private PrivateKey resolveNodePrivateKey(@NonNull final NodeId nodeId, @NonNull final KeyCertPurpose purpose) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);

        // Check for the enhanced private key store. The enhance key store is preferred over the legacy key store.
        Path ksLocation = privateKeyStore(nodeId, purpose);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found enhanced private key store for nodeId: {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
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
                    purpose,
                    ksLocation.getFileName());
            return readLegacyPrivateKey(nodeId, ksLocation, purpose.storeName(nodeId));
        }

        // No keys were found so return null. Missing keys will be detected during a call to
        // EnhancedKeyStoreLoader::verify() or EnhancedKeyStoreLoader::keysAndCerts().
        logger.warn(STARTUP.getMarker(), "No private key store found for nodeId: {} [ purpose = {} ]", nodeId, purpose);
        return null;
    }

    /**
     * Attempts to locate a certificate for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}.
     * <p>
     * This method will attempt to load the certificate stores in the following order:
     * <ol>
     *     <li>Enhanced certificate store ({@code [type]-public-[alias].pem})</li>
     *     <li>Legacy certificate store ({@code public.pfx})</li>
     * </ol>
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @param purpose           the {@link KeyCertPurpose} for which the certificate should be loaded.
     * @param legacyPublicStore the preloaded legacy public key store to fallback on if the enhanced certificate store
     *                          is not found.
     * @return the certificate for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}; otherwise,
     * {@code null} if no certificate was found.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, {@code purpose}, or {@code legacyPublicStore}
     *                              is {@code null}.
     */
    @Nullable
    private Certificate resolveNodeCertificate(
            @NonNull final NodeId nodeId,
            @NonNull final KeyCertPurpose purpose,
            @NonNull final KeyStore legacyPublicStore) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        Objects.requireNonNull(legacyPublicStore, MSG_LEGACY_PUBLIC_STORE_NON_NULL);

        // Check for the enhanced certificate store. The enhanced certificate store is preferred over the legacy
        // certificate store.
        Path ksLocation = certificateStore(nodeId, purpose);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found enhanced certificate store for nodeId: {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
                    ksLocation.getFileName());
            return readCertificate(nodeId, ksLocation);
        }

        // Check for the legacy certificate store.
        ksLocation = legacyCertificateStore();
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found legacy certificate store for nodeId: {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
                    ksLocation.getFileName());
            return readLegacyCertificate(nodeId, purpose, legacyPublicStore);
        }

        // No certificates were found so return null. Missing certificates will be detected during a call to
        // EnhancedKeyStoreLoader::verify() or EnhancedKeyStoreLoader::keysAndCerts().
        logger.warn(STARTUP.getMarker(), "No certificate store found for nodeId: {} [ purpose = {} ]", nodeId, purpose);
        return null;
    }

    /**
     * Attempts to read a certificate contained in an enhanced store from the specified {@code location} for the
     * specified {@code nodeId}.
     *
     * @param nodeId   the {@link NodeId} for which the certificate should be loaded.
     * @param location the location of the enhanced certificate store.
     * @return the certificate for the specified {@code nodeId}; otherwise, {@code null} if no certificate was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId} or {@code location} is {@code null}.
     */
    @Nullable
    private Certificate readCertificate(@NonNull final NodeId nodeId, @NonNull final Path location) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);

        try {
            return readEnhancedStore(location, Certificate.class);
        } catch (KeyLoadingException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the enhanced certificate store for nodeId: {} [ fileName = {} ]",
                    nodeId,
                    location.getFileName(),
                    e);
            return null;
        }
    }

    /**
     * Attempts to read a certificate contained in the legacy store from the specified {@code legacyPublicStore} for the
     * specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}.
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @param purpose           the {@link KeyCertPurpose} for which the certificate should be loaded.
     * @param legacyPublicStore the preloaded legacy public key store from which to load the certificate.
     * @return the certificate for the specified {@code nodeId}; otherwise, {@code null} if no certificate was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, {@code purpose}, or {@code legacyPublicStore}
     *                              is {@code null}.
     */
    @Nullable
    private Certificate readLegacyCertificate(
            @NonNull final NodeId nodeId,
            @NonNull final KeyCertPurpose purpose,
            @NonNull final KeyStore legacyPublicStore) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        Objects.requireNonNull(legacyPublicStore, MSG_LEGACY_PUBLIC_STORE_NON_NULL);

        try {
            final Certificate cert = legacyPublicStore.getCertificate(purpose.storeName(nodeId));

            // Legacy certificate store was found, but did not contain the certificate requested.
            if (cert == null) {
                logger.warn(
                        STARTUP.getMarker(),
                        "No certificate found for nodeId: {} [ entryName = {} ]",
                        nodeId,
                        purpose.storeName(nodeId));
            }

            return cert;
        } catch (KeyStoreException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the legacy certificate store [ fileName = {} ]",
                    PUBLIC_KEYS_FILE,
                    e);
            return null;
        }
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
            return readEnhancedStore(location, PrivateKey.class);
        } catch (KeyLoadingException e) {
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

            return (k instanceof PrivateKey pk) ? pk : null;
        } catch (KeyLoadingException | KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException e) {
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
     * @param purpose   the {@link KeyCertPurpose} for which the private key store should be loaded.
     * @return the {@link Path} to the enhanced private key store for the specified {@code nodeAlias} and
     * {@code purpose}.
     * @throws NullPointerException if {@code nodeAlias} or {@code purpose} is {@code null}.
     */
    @NonNull
    private Path privateKeyStore(@NonNull NodeId nodeId, @NonNull KeyCertPurpose purpose) {
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        return keyStoreDirectory.resolve(
                String.format("%s-private-%s.pem", purpose.prefix(), RosterUtils.formatNodeName(nodeId)));
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
    private Path legacyPrivateKeyStore(@NonNull NodeId nodeId) {
        Objects.requireNonNull(nodeId, MSG_NODE_ALIAS_NON_NULL);
        return keyStoreDirectory.resolve(String.format("private-%s.pfx", RosterUtils.formatNodeName(nodeId)));
    }

    /**
     * Utility method for resolving the {@link Path} to the enhanced certificate store for the specified
     * {@code nodeAlias} and {@code purpose}.
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @param purpose   the {@link KeyCertPurpose} for which the certificate store should be loaded.
     * @return the {@link Path} to the enhanced certificate store for the specified {@code nodeAlias} and
     * {@code purpose}.
     * @throws NullPointerException if {@code nodeAlias} or {@code purpose} is {@code null}.
     */
    @NonNull
    private Path certificateStore(@NonNull NodeId nodeId, @NonNull KeyCertPurpose purpose) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        return keyStoreDirectory.resolve(
                String.format("%s-public-%s.pem", purpose.prefix(), RosterUtils.formatNodeName(nodeId)));
    }

    /**
     * Utility method for resolving the {@link Path} to the legacy certificate store.
     *
     * @return the {@link Path} to the legacy certificate store.
     */
    @NonNull
    private Path legacyCertificateStore() {
        return keyStoreDirectory.resolve(PUBLIC_KEYS_FILE);
    }

    /**
     * Utility method for reading a specific {@code entryType} from an enhanced key store at the specified
     * {@code location}.
     *
     * @param location  the {@link Path} to the enhanced key store.
     * @param entryType the {@link Class} instance of the requested entry type.
     * @param <T>       the type of entry to load from the key store.
     * @return the entry of the specified {@code entryType} from the key store.
     * @throws KeyLoadingException  if an error occurred while attempting to read the key store or the requested entry
     *                              was not found.
     * @throws NullPointerException if {@code location} or {@code entryType} is {@code null}.
     */
    @NonNull
    private <T> T readEnhancedStore(@NonNull final Path location, @NonNull final Class<T> entryType)
            throws KeyLoadingException {
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);
        Objects.requireNonNull(entryType, MSG_ENTRY_TYPE_NON_NULL);

        try (final PEMParser parser =
                new PEMParser(new InputStreamReader(Files.newInputStream(location), StandardCharsets.UTF_8))) {
            Object entry;

            while ((entry = parser.readObject()) != null) {
                if (isCompatibleStoreEntry(entry, entryType)) {
                    break;
                }
            }

            if (entry == null) {
                throw new KeyLoadingException("No entry of the requested type found [ entryType = %s, fileName = %s ]"
                        .formatted(entryType.getName(), location.getFileName()));
            }

            return extractEntityOfType(entry, entryType);
        } catch (IOException | DecoderException e) {
            throw new KeyLoadingException(
                    "Unable to read enhanced store [ fileName = %s ]".formatted(location.getFileName()), e);
        }
    }

    /**
     * Helper method related to {@link #readEnhancedStore(Path, Class)} used to extract the requested {@code entryType}
     * from the specified {@code entry} loaded from the store.
     *
     * @param entry     the entry loaded from the store.
     * @param entryType the {@link Class} instance of the requested entry type.
     * @param <T>       the type of entry to load from the key store.
     * @return the requested entry of the specified {@code entryType}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the requested entry or entry is an
     *                              unsupported type.
     * @throws NullPointerException if {@code entry} or {@code entryType} is {@code null}.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    private <T> T extractEntityOfType(@NonNull final Object entry, @NonNull final Class<T> entryType)
            throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);
        Objects.requireNonNull(entryType, MSG_ENTRY_TYPE_NON_NULL);

        if (entryType.isAssignableFrom(PublicKey.class)) {
            return (T) extractPublicKeyEntity(entry);
        } else if (entryType.isAssignableFrom(PrivateKey.class)) {
            return (T) extractPrivateKeyEntity(entry);
        } else if (entryType.isAssignableFrom(Certificate.class)) {
            return (T) extractCertificateEntity(entry);
        } else {
            throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]".formatted(entryType.getName()));
        }
    }

    /**
     * Helper method used by {@link #extractEntityOfType(Object, Class)} for extracting a {@link PublicKey} from the
     * specified {@code entry}.
     *
     * @param entry the entry loaded from the store.
     * @return the {@link PublicKey} extracted from the specified {@code entry}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the {@link PublicKey} from the
     *                              specified {@code entry}.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    @NonNull
    private PublicKey extractPublicKeyEntity(@NonNull final Object entry) throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);

        try {
            final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            final PEMDecryptorProvider decrypter = new JcePEMDecryptorProviderBuilder().build(keyStorePassphrase);

            return switch (entry) {
                case SubjectPublicKeyInfo spki -> converter.getPublicKey(spki);
                case PEMKeyPair kp -> converter.getPublicKey(kp.getPublicKeyInfo());
                case PEMEncryptedKeyPair ekp -> converter.getPublicKey(
                        ekp.decryptKeyPair(decrypter).getPublicKeyInfo());
                default -> throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                        .formatted(entry.getClass().getName()));
            };
        } catch (IOException e) {
            throw new KeyLoadingException(
                    "Unable to extract a public key from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    /**
     * Helper method used by {@link #extractEntityOfType(Object, Class)} for extracting a {@link PrivateKey} from the
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
                case PrivateKeyInfo pki -> converter.getPrivateKey(pki);
                case PKCS8EncryptedPrivateKeyInfo epki -> converter.getPrivateKey(
                        epki.decryptPrivateKeyInfo(inputDecrypter));
                case PEMKeyPair kp -> converter.getPrivateKey(kp.getPrivateKeyInfo());
                case PEMEncryptedKeyPair ekp -> converter.getPrivateKey(
                        ekp.decryptKeyPair(decrypter).getPrivateKeyInfo());
                default -> throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                        .formatted(entry.getClass().getName()));
            };
        } catch (IOException | PKCSException e) {
            throw new KeyLoadingException(
                    "Unable to extract a private key from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    /**
     * Helper method used by {@link #extractEntityOfType(Object, Class)} for extracting a {@link Certificate} from the
     * specified {@code entry}.
     *
     * @param entry the entry loaded from the store.
     * @return the {@link Certificate} extracted from the specified {@code entry}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the {@link Certificate} from the
     *                              specified {@code entry}.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    @NonNull
    private Certificate extractCertificateEntity(@NonNull Object entry) throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);

        try {
            if (entry instanceof X509CertificateHolder ch) {
                return new JcaX509CertificateConverter().getCertificate(ch);
            }

            throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                    .formatted(entry.getClass().getName()));
        } catch (CertificateException e) {
            throw new KeyLoadingException(
                    "Unable to extract a certificate from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    /**
     * Utility method for determining if the specified {@code entry} is compatible with the specified
     * {@code entryType}.
     *
     * @param entry     the entry loaded from the store.
     * @param entryType the {@link Class} instance of the requested entry type.
     * @param <T>       the type of entry to load from the key store.
     * @return {@code true} if the specified {@code entry} is compatible with the specified {@code entryType};
     * otherwise, {@code false}.
     * @throws NullPointerException if {@code entry} or {@code entryType} is {@code null}.
     */
    private static <T> boolean isCompatibleStoreEntry(@NonNull final Object entry, @NonNull final Class<T> entryType) {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);
        Objects.requireNonNull(entryType, MSG_ENTRY_TYPE_NON_NULL);

        if (entryType.isAssignableFrom(PublicKey.class)
                && (entry instanceof SubjectPublicKeyInfo
                        || entry instanceof PEMKeyPair
                        || entry instanceof PEMEncryptedKeyPair)) {
            return true;
        } else if (entryType.isAssignableFrom(PrivateKey.class)
                && (entry instanceof PEMKeyPair
                        || entry instanceof PrivateKeyInfo
                        || entry instanceof PKCS8EncryptedPrivateKeyInfo
                        || entry instanceof PEMEncryptedKeyPair)) {
            return true;
        } else if (entryType.isAssignableFrom(KeyPair.class)
                && (entry instanceof PEMKeyPair || entry instanceof PEMEncryptedKeyPair)) {
            return true;
        } else {
            return entryType.isAssignableFrom(Certificate.class) && entry instanceof X509CertificateHolder;
        }
    }

    /**
     * Helper method for iterating over the address book in index order.
     *
     * @param addressBook the address book to iterate over.
     * @param function    the function to apply to each entry in the address book.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not
     *                              initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code addressBook} or {@code function} is {@code null}.
     */
    private static void iterateAddressBook(
            @NonNull final AddressBook addressBook, @NonNull AddressBookCallback function)
            throws KeyStoreException, KeyLoadingException {
        Objects.requireNonNull(addressBook, MSG_ADDRESS_BOOK_NON_NULL);
        Objects.requireNonNull(function, MSG_FUNCTION_NON_NULL);

        for (int i = 0; i < addressBook.getSize(); i++) {
            final NodeId nodeId = addressBook.getNodeId(i);
            final Address address = addressBook.getAddress(nodeId);
            function.apply(nodeId, address);
        }
    }

    /**
     * The contract for the function used by {@link #iterateAddressBook(AddressBook, AddressBookCallback)} to iterate
     * over the address book in index order.
     */
    @FunctionalInterface
    private interface AddressBookCallback {
        void apply(NodeId nodeId, Address address) throws KeyStoreException, KeyLoadingException;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////// MIGRATION METHODS //////////////////////////////////////////
    /////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Performs any necessary migration steps to ensure the key storage is up-to-date.
     * <p>
     * As of release 0.56 the on-disk cryptography should reflect the following structure:
     * <ul>
     *     <li>s-private-alias.pem - the private signing key </li>
     *     <li>s-public-alias.pem - the public signing certificates of each node</li>
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
        final Map<NodeId, Certificate> pfxCertificates = new HashMap<>();

        // delete agreement keys permanently.  They are being created at startup by generateIfNecessary() after scan().
        deleteAgreementKeys();

        // create PEM files for signing keys and certs.
        long errorCount = extractPrivateKeysAndCertsFromPfxFiles(pfxPrivateKeys, pfxCertificates);

        if (errorCount == 0) {
            // validate only when there are no errors extracting pem files.
            errorCount = validateKeysAndCertsAreLoadableFromPemFiles(pfxPrivateKeys, pfxCertificates);
        }

        if (errorCount > 0) {
            // roll back due to errors.
            // this deletes any pem files created, but leaves the agreement keys deleted.
            logger.error(STARTUP.getMarker(), "Due to {} errors, reverting pem file creation.", errorCount);
            rollBackSigningKeysAndCertsChanges(pfxPrivateKeys, pfxCertificates);
        } else {
            // cleanup pfx files by moving them to subdirectory
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
     * Extracts the private keys and certificates from the PFX files and writes them to PEM files.
     *
     * @param pfxPrivateKeys  the map of private keys being extracted (Updated By Method Call)
     * @param pfxCertificates the map of certificates being extracted (Updated By Method Call)
     * @return the number of errors encountered during the extraction process.
     * @throws KeyStoreException   if the underlying method calls throw this exception.
     * @throws KeyLoadingException if the underlying method calls throw this exception.
     */
    private long extractPrivateKeysAndCertsFromPfxFiles(
            final Map<NodeId, PrivateKey> pfxPrivateKeys, final Map<NodeId, Certificate> pfxCertificates)
            throws KeyStoreException, KeyLoadingException {
        final KeyStore legacyPublicStore = resolveLegacyPublicStore();
        final AtomicLong errorCount = new AtomicLong(0);

        iterateAddressBook(addressBook, (nodeId, address) -> {
            if (localNodes.contains(nodeId)) {
                // extract private keys for local nodes
                final Path sPrivateKeyLocation = keyStoreDirectory.resolve(
                        String.format("s-private-%s.pem", RosterUtils.formatNodeName(nodeId)));
                final Path ksLocation = legacyPrivateKeyStore(nodeId);
                if (!Files.exists(sPrivateKeyLocation) && Files.exists(ksLocation)) {
                    logger.info(
                            STARTUP.getMarker(),
                            "Extracting private signing key for nodeId: {} from file {}",
                            nodeId,
                            ksLocation.getFileName());
                    final PrivateKey privateKey =
                            readLegacyPrivateKey(nodeId, ksLocation, KeyCertPurpose.SIGNING.storeName(nodeId));
                    pfxPrivateKeys.put(nodeId, privateKey);
                    if (privateKey == null) {
                        logger.error(
                                ERROR.getMarker(),
                                "Failed to extract private signing key for nodeId: {} from file {}",
                                nodeId,
                                ksLocation.getFileName());
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

            // extract certificates for all nodes
            final Path sCertificateLocation =
                    keyStoreDirectory.resolve(String.format("s-public-%s.pem", RosterUtils.formatNodeName(nodeId)));
            final Path ksLocation = legacyCertificateStore();
            if (!Files.exists(sCertificateLocation) && Files.exists(ksLocation)) {
                logger.info(
                        STARTUP.getMarker(),
                        "Extracting signing certificate for nodeId: {} from file {} ",
                        nodeId,
                        ksLocation.getFileName());
                final Certificate certificate =
                        readLegacyCertificate(nodeId, KeyCertPurpose.SIGNING, legacyPublicStore);
                pfxCertificates.put(nodeId, certificate);
                if (certificate == null) {
                    logger.error(
                            ERROR.getMarker(),
                            "Failed to extract signing certificate for nodeId: {} from file {}",
                            nodeId,
                            ksLocation.getFileName());
                    errorCount.incrementAndGet();
                } else {
                    logger.info(
                            STARTUP.getMarker(),
                            "Writing signing certificate for nodeId: {} to PEM file {}",
                            nodeId,
                            sCertificateLocation.getFileName());
                    try {
                        writePemFile(false, sCertificateLocation, certificate.getEncoded());
                    } catch (final CertificateEncodingException | IOException e) {
                        logger.error(
                                ERROR.getMarker(),
                                "Failed to write signing certificate for nodeId: {} to PEM file {}",
                                nodeId,
                                sCertificateLocation.getFileName());
                        errorCount.incrementAndGet();
                    }
                }
            }
        });
        return errorCount.get();
    }

    /**
     * Validates that the private keys and certs in PEM files are loadable and match the PFX loaded keys and certs.
     *
     * @param pfxPrivateKeys  the map of private keys being extracted.
     * @param pfxCertificates the map of certificates being extracted.
     * @return the number of errors encountered during the validation process.
     * @throws KeyStoreException   if the underlying method calls throw this exception.
     * @throws KeyLoadingException if the underlying method calls throw this exception.
     */
    private long validateKeysAndCertsAreLoadableFromPemFiles(
            final Map<NodeId, PrivateKey> pfxPrivateKeys, final Map<NodeId, Certificate> pfxCertificates)
            throws KeyStoreException, KeyLoadingException {
        final AtomicLong errorCount = new AtomicLong(0);
        iterateAddressBook(addressBook, (nodeId, address) -> {
            if (localNodes.contains(nodeId) && pfxCertificates.containsKey(nodeId)) {
                // validate private keys for local nodes
                final Path ksLocation = privateKeyStore(nodeId, KeyCertPurpose.SIGNING);
                final PrivateKey pemPrivateKey = readPrivateKey(nodeId, ksLocation);
                if (pemPrivateKey == null
                        || !Arrays.equals(
                                pemPrivateKey.getEncoded(),
                                pfxPrivateKeys.get(nodeId).getEncoded())) {
                    logger.error(
                            ERROR.getMarker(), "Private key for nodeId: {} does not match the migrated key", nodeId);
                    errorCount.incrementAndGet();
                }
            }

            // validate certificates for all nodes PEM files were created for.
            if (pfxCertificates.containsKey(nodeId)) {
                final Path ksLocation = certificateStore(nodeId, KeyCertPurpose.SIGNING);
                final Certificate pemCertificate = readCertificate(nodeId, ksLocation);
                try {
                    if (pemCertificate == null
                            || !Arrays.equals(
                                    pemCertificate.getEncoded(),
                                    pfxCertificates.get(nodeId).getEncoded())) {
                        logger.error(
                                ERROR.getMarker(),
                                "Certificate for nodeId: {} does not match the migrated certificate",
                                nodeId);
                        errorCount.incrementAndGet();
                    }
                } catch (final CertificateEncodingException e) {
                    logger.error(
                            ERROR.getMarker(), "Encoding error while validating certificate for nodeId: {}.", nodeId);
                    errorCount.incrementAndGet();
                }
            }
        });
        return errorCount.get();
    }

    /**
     * Rollback the creation of PEM files for signing keys and certificates.
     *
     * @param pfxPrivateKeys  the map of private keys being extracted.
     * @param pfxCertificates the map of certificates being extracted.
     * @throws KeyStoreException   if the underlying method calls throw this exception.
     * @throws KeyLoadingException if the underlying method calls throw this exception.
     */
    private void rollBackSigningKeysAndCertsChanges(
            final Map<NodeId, PrivateKey> pfxPrivateKeys, final Map<NodeId, Certificate> pfxCertificates)
            throws KeyStoreException, KeyLoadingException {

        final AtomicLong cleanupErrorCount = new AtomicLong(0);
        iterateAddressBook(addressBook, (nodeId, address) -> {
            // private key rollback
            if (localNodes.contains(nodeId) && pfxPrivateKeys.containsKey(address.getNodeId())) {
                try {
                    Files.deleteIfExists(privateKeyStore(nodeId, KeyCertPurpose.SIGNING));
                } catch (final IOException e) {
                    cleanupErrorCount.incrementAndGet();
                }
            }
            // certificate rollback
            if (pfxCertificates.containsKey(address.getNodeId())) {
                try {
                    Files.deleteIfExists(certificateStore(nodeId, KeyCertPurpose.SIGNING));
                } catch (final IOException e) {
                    cleanupErrorCount.incrementAndGet();
                }
            }
        });
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
     *
     * @throws KeyStoreException   if the underlying method calls throw this exception.
     * @throws KeyLoadingException if the underlying method calls throw this exception.
     */
    private void cleanupByMovingPfxFilesToSubDirectory() throws KeyStoreException, KeyLoadingException {
        final AtomicLong cleanupErrorCount = new AtomicLong(0);
        final AtomicBoolean doCleanup = new AtomicBoolean(false);
        iterateAddressBook(addressBook, (nodeId, address) -> {
            if (localNodes.contains(nodeId)) {
                // move private key PFX files per local node
                final File sPrivatePfx = legacyPrivateKeyStore(nodeId).toFile();
                if (sPrivatePfx.exists() && sPrivatePfx.isFile()) {
                    doCleanup.set(true);
                }
            }
        });

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
        iterateAddressBook(addressBook, (nodeId, address) -> {
            if (localNodes.contains(nodeId)) {
                // move private key PFX files per local node
                final File sPrivatePfx = legacyPrivateKeyStore(nodeId).toFile();
                if (sPrivatePfx.exists()
                        && sPrivatePfx.isFile()
                        && !sPrivatePfx.renameTo(
                                pfxDateDirectory.resolve(sPrivatePfx.getName()).toFile())) {
                    cleanupErrorCount.incrementAndGet();
                }
            }
        });
        final File sPublicPfx = legacyCertificateStore().toFile();
        if (sPublicPfx.exists()
                && sPublicPfx.isFile()
                && !sPublicPfx.renameTo(
                        pfxDateDirectory.resolve(sPublicPfx.getName()).toFile())) {
            cleanupErrorCount.incrementAndGet();
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
