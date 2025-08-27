// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static com.hedera.node.app.hapi.utils.keys.Ed25519Utils.isValidEd25519Key;
import static com.hedera.node.app.hapi.utils.keys.Secp256k1Utils.isValidEcdsaSecp256k1Key;
import static com.hedera.node.app.hapi.utils.keys.Secp256k1Utils.isValidEvmAddress;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.security.DrbgParameters;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

/**
 * Utility class for working with algorithm-agnostic cryptographic keys
 */
public final class KeyUtils {
    public static final Key IMMUTABILITY_SENTINEL_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();
    public static final String TEST_CLIENTS_PREFIX = "hedera-node" + File.separator + "test-clients" + File.separator;

    public static final Provider BC_PROVIDER = new BouncyCastleProvider();

    private static final int ENCRYPTOR_ITERATION_COUNT = 10_000;
    private static final String RESOURCE_PATH_SEGMENT = "src/main/resource";

    private KeyUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    private static final DrbgParameters.Instantiation DRBG_INSTANTIATION =
            DrbgParameters.instantiation(256, DrbgParameters.Capability.RESEED_ONLY, null);

    public static Path relocatedIfNotPresentInWorkingDir(final Path path) {
        return relocatedIfNotPresentInWorkingDir(path.toFile()).toPath();
    }

    public static File relocatedIfNotPresentInWorkingDir(final File file) {
        return relocatedIfNotPresentWithCurrentPathPrefix(file, RESOURCE_PATH_SEGMENT, TEST_CLIENTS_PREFIX);
    }

    public static File relocatedIfNotPresentWithCurrentPathPrefix(
            final File file, final String firstSegmentToRelocate, final String newPathPrefix) {
        if (!file.exists()) {
            final var absPath = withDedupedHederaNodePathSegments(file.getAbsolutePath());
            final var idx = absPath.indexOf(firstSegmentToRelocate);
            if (idx == -1) {
                return new File(absPath);
            }
            final var relocatedPath = newPathPrefix + absPath.substring(idx);
            return new File(relocatedPath);
        } else {
            return file;
        }
    }

    public static <T extends PrivateKey> T readKeyFrom(
            final File pem, final String passphrase, final Provider pemKeyProvider) {
        final var relocatedPem = KeyUtils.relocatedIfNotPresentInWorkingDir(pem);
        try (final var in = new FileInputStream(relocatedPem)) {
            return readKeyFrom(in, passphrase, pemKeyProvider);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Reads a private key from an {@link InputStream} that contains a PEM-encoded PKCS#8 encrypted private key.
     * @param in the input stream containing the PEM-encoded private key
     * @param passphrase the passphrase used to decrypt the private key
     * @param pemKeyProvider the provider to use for PEM key conversion
     * @return the decrypted private key
     * @param <T> the type of the private key, extending {@link PrivateKey}
     */
    public static <T extends PrivateKey> T readKeyFrom(
            @NonNull final InputStream in, @NonNull final String passphrase, @NonNull final Provider pemKeyProvider) {
        try {
            final var decryptProvider = new JceOpenSSLPKCS8DecryptorProviderBuilder()
                    .setProvider(BC_PROVIDER)
                    .build(passphrase.toCharArray());
            final var converter = new JcaPEMKeyConverter().setProvider(pemKeyProvider);
            try (final var parser = new PEMParser(new InputStreamReader(in))) {
                final var encryptedPrivateKeyInfo = (PKCS8EncryptedPrivateKeyInfo) parser.readObject();
                final var info = encryptedPrivateKeyInfo.decryptPrivateKeyInfo(decryptProvider);
                return (T) converter.getPrivateKey(info);
            }
        } catch (final IOException | OperatorCreationException | PKCSException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void writeKeyTo(final PrivateKey key, final String pemLoc, final String passphrase) {
        final var pem = new File(pemLoc);
        try (final var out = new FileOutputStream(pem)) {
            final var random = SecureRandom.getInstance("DRBG", DRBG_INSTANTIATION);
            final var encryptor = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                    .setPRF(PKCS8Generator.PRF_HMACSHA384)
                    .setIterationCount(ENCRYPTOR_ITERATION_COUNT)
                    .setRandom(random)
                    .setPassword(passphrase.toCharArray())
                    .setProvider(BC_PROVIDER)
                    .build();
            try (final var pemWriter = new JcaPEMWriter(new OutputStreamWriter(out))) {
                pemWriter.writeObject(new JcaPKCS8Generator(key, encryptor).generate());
                pemWriter.flush();
            }
        } catch (final IOException | NoSuchAlgorithmException | OperatorCreationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Checks if the given key is empty.
     * For a KeyList type checks if the list is empty.
     * For a ThresholdKey type checks if the list is empty.
     * For an Ed25519 or EcdsaSecp256k1 type checks if there are zero bytes.
     * @param pbjKey the key to check
     * @return true if the key is empty, false otherwise
     */
    public static boolean isEmpty(@Nullable final Key pbjKey) {
        return isEmptyInternal(pbjKey, false);
    }

    /**
     * Checks if the given key is empty.
     * For a KeyList type checks if the list is empty.
     * For a ThresholdKey type checks if the list is empty.
     * For an Ed25519 or EcdsaSecp256k1 type checks if there are zero bytes.
     * @param pbjKey the key to check
     * @param honorImmutable if true, the key is NOT considered EMPTY if it is the IMMUTABILITY_SENTINEL_KEY
     * @return true if the key is empty, false otherwise
     */
    private static boolean isEmptyInternal(@Nullable final Key pbjKey, boolean honorImmutable) {
        if (pbjKey == null) {
            return true;
        }
        final var key = pbjKey.key();
        if (key == null || Key.KeyOneOfType.UNSET.equals(key.kind())) {
            return true;
        }
        if (honorImmutable && IMMUTABILITY_SENTINEL_KEY.equals(pbjKey)) {
            return false;
        }
        if (pbjKey.hasKeyList()) {
            final var keyList = (KeyList) key.value();
            if (keyList.keys().isEmpty()) {
                return true;
            }
            for (final var k : keyList.keys()) {
                if (!isEmpty(k)) {
                    return false;
                }
            }
            return true;
        } else if (pbjKey.hasThresholdKey()) {
            final var thresholdKey = (ThresholdKey) key.value();
            if ((!thresholdKey.hasKeys() || thresholdKey.keys().keys().size() == 0)) {
                return true;
            }
            for (final var k : thresholdKey.keys().keys()) {
                if (!isEmpty(k)) {
                    return false;
                }
            }
            return true;
        } else if (pbjKey.hasEd25519()) {
            return ((Bytes) key.value()).length() == 0;
        } else if (pbjKey.hasEcdsaSecp256k1()) {
            return ((Bytes) key.value()).length() == 0;
        } else if (pbjKey.hasDelegatableContractId() || pbjKey.hasContractID()) {
            return ((ContractID) key.value()).contractNumOrElse(0L) == 0
                    && ((ContractID) key.value()).evmAddressOrElse(Bytes.EMPTY).length() == 0L;
        }
        // ECDSA_384 and RSA_3072 are not supported yet
        return true;
    }

    /**
     * Checks if the given key is valid. Based on the key type it checks the basic requirements
     * for the key type.
     * @param pbjKey the key to check
     * @return true if the key is valid, false otherwise
     */
    public static boolean isValid(@Nullable final Key pbjKey) {
        if (isEmpty(pbjKey)) {
            return false;
        }
        final var key = pbjKey.key();
        if (pbjKey.hasKeyList()) {
            for (Key keys : ((KeyList) key.value()).keys()) {
                if (!isValid(keys)) {
                    return false;
                }
            }
            return true;
        } else if (pbjKey.hasThresholdKey()) {
            final int length = ((ThresholdKey) key.value()).keys().keys().size();
            final int threshold = ((ThresholdKey) key.value()).threshold();
            boolean isKeyListValid = true;
            for (Key keys : ((ThresholdKey) key.value()).keys().keys()) {
                if (!isValid(keys)) {
                    isKeyListValid = false;
                    break;
                }
            }
            return (threshold >= 1 && threshold <= length && isKeyListValid);
        } else if (pbjKey.hasEd25519()) {
            return isValidEd25519Key((Bytes) key.value());
        } else if (pbjKey.hasEcdsaSecp256k1()) {
            return isValidEcdsaSecp256k1Key((Bytes) key.value());
        } else if (pbjKey.hasDelegatableContractId() || pbjKey.hasContractID()) {
            return isValidEvmAddress((ContractID) key.value());
        }
        // ECDSA_384 and RSA_3072 are not supported yet
        return true;
    }

    public static PrivateKey readUnknownTypeKeyFrom(final File pem, final String passphrase) {
        try {
            return Ed25519Utils.readKeyFrom(pem, passphrase);
        } catch (final Exception e) {
            // Try to read as ECDSA key; otherwise throw
            return Secp256k1Utils.readECKeyFrom(pem, passphrase);
        }
    }

    public static PrivateKey readUnknownTypeKeyFrom(final byte[] bytes) {
        try {
            return Ed25519Utils.keyFrom(bytes);
        } catch (final Exception e) {
            // Try to read as ECDSA key; otherwise throw
            return Secp256k1Utils.readECKeyFrom(bytes);
        }
    }

    private static String withDedupedHederaNodePathSegments(@NonNull final String loc) {
        final var firstSegmentI = loc.indexOf("hedera-node");
        if (firstSegmentI == -1) {
            return loc;
        }
        final var lastSegmentI = loc.lastIndexOf("hedera-node");
        if (lastSegmentI != firstSegmentI) {
            return loc.substring(0, firstSegmentI) + loc.substring(lastSegmentI);
        } else {
            return loc;
        }
    }
}
