// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType.ECDSA_SECP256K1;
import static com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType.ED25519;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.KeyOneOfType;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.SignatureExpander;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Implementation of {@link SignatureExpander}. */
public final class SignatureExpanderImpl implements SignatureExpander {
    private static final Logger logger = LogManager.getLogger(SignatureExpanderImpl.class);
    /** All ED25519 keys have a length of 32 bytes. */
    private static final int ED25519_KEY_LENGTH = 32;
    /** All ECDSA_SECP256K1 keys have a COMPRESSED length of 33 bytes */
    private static final int ECDSA_COMPRESSED_KEY_LENGTH = 33;

    @Inject
    public SignatureExpanderImpl() {
        // Exists for DI
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation <b>assumes</b> that all duplicate {@link SignaturePair}s have been removed from the
     * {@code sigPairs} list prior to calling this method. In addition, all {@link SignaturePair}s that are a strict
     * subset of other pairs in the list have also been removed.
     */
    @Override
    public void expand(
            @NonNull final List<SignaturePair> sigPairs, @NonNull final Set<ExpandedSignaturePair> expanded) {
        requireNonNull(sigPairs);
        requireNonNull(expanded);
        // Find every signature pair where the prefix is a "full key" prefix, meaning the entire key is included
        // in the prefix, and include every single one of them in the expanded signature pair set.
        for (final var pair : sigPairs) {
            final var prefixLength = pair.pubKeyPrefix().length();
            if (prefixLength == ED25519_KEY_LENGTH && pair.signature().kind() == ED25519) {
                // Public Key Prefix in this case has the full key bytes, so we can just reuse it.
                expanded.add(new ExpandedSignaturePair(asKey(pair), pair.pubKeyPrefix(), null, pair));
            } else if (prefixLength == ECDSA_COMPRESSED_KEY_LENGTH
                    && pair.signature().kind() == ECDSA_SECP256K1) {
                // The prefix will be the key but in compressed form. We also need the decompressed form because that
                // is required by the cryptographic engine. We will also compute the evm address, in case this is
                // associated with a hollow account. It should be that the prefix will only be full if needed, and for
                // hollow accounts it is needed, but otherwise it can typically not be the full prefix. In that case,
                // we won't waste much work. And the payer pays for the whole thing anyway, so we're compensated for the
                // CPU cycles in any event. Doing it in the background threads seems to be a better tradeoff.
                final var maybeExpandedSigPair = ExpandedSignaturePair.maybeFrom(pair.pubKeyPrefix(), pair);
                if (maybeExpandedSigPair != null) {
                    expanded.add(maybeExpandedSigPair);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation <b>assumes</b> that all duplicate {@link SignaturePair}s have been removed from the
     * {@code sigPairs} list prior to calling this method. In addition, all {@link SignaturePair}s that are a strict
     * subset of other pairs in the list have also been removed.
     *
     * <p>It is absolutely essential in the implementation that there are NO DUPLICATE ENTRIES in the {@code sigPairs},
     * and that this implementation therefore will only ever match at most a single item. It is essential that every
     * node deterministically expand the same set of {@link SignaturePair}s into the same set of
     * {@link ExpandedSignaturePair}s. Our API uses a Set in a few places (such as the set of required keys), and if we
     * were to iterate out of order, or if the sigPairs were out of order, or if we removed elements from the sig pair
     * as we found them, then we might have subtle cases where we expand the same set of sigPairs into different sets of
     * ExpandedSignaturePairs.
     */
    @Override
    public void expand(
            @NonNull final Key key,
            @NonNull final List<SignaturePair> originals,
            @NonNull final Set<ExpandedSignaturePair> expanded) {
        requireNonNull(key);
        requireNonNull(originals);
        requireNonNull(expanded);

        // The key may be of some arbitrary depth and complexity, so we need to recursively expand it.
        switch (key.key().kind()) {
                // If the key is an ED25519 cryptographic key, then we simply iterate through the list of signature
                // pairs and find the one that matches the key.
            case ED25519 -> {
                final var match = findMatch(key, originals);
                if (match != null) {
                    expanded.add(new ExpandedSignaturePair(key, key.ed25519OrThrow(), null, match));
                }
            }
                // If the key is an ECDSA_SECP256K1 cryptographic key, then we simply iterate through the list of
                // signature pairs and find the one that matches the key, **and then decompress it**.
            case ECDSA_SECP256K1 -> {
                final var match = findMatch(key, originals);
                if (match != null) {
                    final var maybeExpandedSigPair =
                            ExpandedSignaturePair.maybeFrom(key.ecdsaSecp256k1OrThrow(), match);
                    if (maybeExpandedSigPair != null) {
                        expanded.add(maybeExpandedSigPair);
                    }
                }
            }
                // If the key is a key list, then we need to recursively expand each key in the list.
            case KEY_LIST -> key.keyListOrElse(KeyList.DEFAULT).keys().forEach(k -> expand(k, originals, expanded));
                // If the key is a threshold key, then we need to recursively expand each key in the threshold key's
                // list. At this point in the process we don't care whether we have enough keys for the threshold or
                // not, we just expand whatever we find.
            case THRESHOLD_KEY -> key.thresholdKeyOrElse(ThresholdKey.DEFAULT)
                    .keysOrElse(KeyList.DEFAULT)
                    .keys()
                    .forEach(k -> expand(k, originals, expanded));
            case ECDSA_384, RSA_3072, CONTRACT_ID, DELEGATABLE_CONTRACT_ID, UNSET -> {
                // We don't support these, so we won't expand them
            }
        }
    }

    /**
     * Decompresses the ECDSA_SECP256K1 key.
     *
     * @param keyBytes The compressed key bytes
     * @return The decompressed key bytes, or null if the key was not a valid compressed ECDSA_SECP256K1 key
     */
    @Nullable
    public static Bytes decompressKey(@Nullable final Bytes keyBytes) {
        if (keyBytes == null) return null;
        // If the compressed key begins with a prefix byte other than 0x02 or 0x03, decompressing will throw.
        // We don't want it to throw, because that is a waste of CPU cycles. So we'll check the first byte
        // first. But the compiler still requires the try/catch. We hope it is never thrown, but if it is,
        // it isn't the end of the world.
        final byte firstByte = keyBytes.getByte(0);
        if (firstByte == 0x02 || firstByte == 0x03) {
            try {
                return Bytes.wrap(MiscCryptoUtils.decompressSecp256k1(keyBytes.toByteArray()));
            } catch (IllegalArgumentException e) {
                // This isn't the key we're looking for. Move along.
                logger.warn("Unable to decompress ECDSA(secp256k1) key. Should never happen", e);
            }
        }

        return null;
    }

    /**
     * Given a cryptographic {@link Key} and a list of {@link SignaturePair}s, find any {@link SignaturePair} such that
     * the type of the pair matches the type of the cryptographic key, and the prefix of the pair matches the initial
     * bytes of the cryptographic key.
     *
     * <p>Note that since the list has already been verified to contain no duplicates, and no pairs that are a strict
     * subset of other pairs, we can safely return the first match we find, since only one pair at most can possibly
     * match this key.
     *
     * @param key The cryptographic key to match against
     * @param pairs The list of signature pairs to search
     * @return The matching signature pair, or {@code null} if no match is found
     */
    @Nullable
    private SignaturePair findMatch(@NonNull final Key key, @NonNull final List<SignaturePair> pairs) {
        for (final var pair : pairs) {
            final var prefix = pair.pubKeyPrefix();
            final var sigType = pair.signature().kind();
            switch (sigType) {
                case ED25519 -> {
                    // Only match ED25519 signatures with ED25519 keys.
                    final var matchingKeyType = key.key().kind() == KeyOneOfType.ED25519;
                    // Valid ED25519 keys have a max length of 32 bytes.
                    final var validPrefixLength = prefix.length() <= ED25519_KEY_LENGTH;
                    if (matchingKeyType
                            && validPrefixLength
                            && key.ed25519OrThrow().matchesPrefix(prefix)) {
                        return pair;
                    }
                }
                case ECDSA_SECP256K1 -> {
                    // Only match ECDSA_SECP256K1 signatures with ECDSA_SECP256K1 keys.
                    final var matchingKeyType = key.key().kind() == KeyOneOfType.ECDSA_SECP256K1;
                    // Valid ECDSA_SECP256K1 keys have a max length of 33 bytes.
                    final var validPrefixLength = prefix.length() <= ECDSA_COMPRESSED_KEY_LENGTH;
                    if (matchingKeyType
                            && validPrefixLength
                            && key.ecdsaSecp256k1OrThrow().matchesPrefix(prefix)) {
                        return pair;
                    }
                }
                case CONTRACT, ECDSA_384, RSA_3072, UNSET -> {
                    // Skip these signature types. They never match.
                }
            }
        }

        return null;
    }

    /**
     * A simple utility method that extracts the key from the given {@link SignaturePair}.
     *
     * @param pair The pair from which to extract the key.
     * @return The extracted key.
     */
    @NonNull
    public static Key asKey(@NonNull final SignaturePair pair) {
        return switch (pair.signature().kind()) {
            case ED25519 -> Key.newBuilder().ed25519(pair.pubKeyPrefix()).build();
            case ECDSA_SECP256K1 -> Key.newBuilder()
                    .ecdsaSecp256k1(pair.pubKeyPrefix())
                    .build();
            case RSA_3072, ECDSA_384, CONTRACT, UNSET -> throw new IllegalArgumentException(
                    "Unsupported cryptographic key: " + pair.signature().kind());
        };
    }
}
