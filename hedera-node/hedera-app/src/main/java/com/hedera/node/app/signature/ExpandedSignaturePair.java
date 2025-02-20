// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.extractEvmAddressFromDecompressedECDSAKey;
import static com.hedera.node.app.signature.impl.SignatureExpanderImpl.decompressKey;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Represents a {@link SignaturePair} where the {@link SignaturePair#pubKeyPrefix()} has been fully "expanded" to a
 * full, uncompressed, public key.
 *
 * @param key The public key, compressed if ECDSA_SECP256K1, or the normal key for ED25519
 * @param keyBytes The key bytes, uncompressed if ECDSA_SECP256K1, or the normal key bytes for ED25519
 * @param evmAlias An optional computed evm alias if the key was ECDSA_SECP256K1, and we decided to compute the alias
 * @param sigPair The original signature pair
 */
public record ExpandedSignaturePair(
        @NonNull Key key, @NonNull Bytes keyBytes, @Nullable Bytes evmAlias, @NonNull SignaturePair sigPair) {
    /**
     * Gets the {@link Bytes} representing the signature signed by the private key matching the fully expanded public
     * key.
     *
     * @return The signature bytes.
     */
    @NonNull
    public Bytes signature() {
        return sigPair.signature().as();
    }

    /**
     * Given a (putative) compressed ECDSA public key and a {@link SignaturePair},
     * returns the implied {@link ExpandedSignaturePair} if the key can be decompressed.
     * Returns null if the key is not a valid compressed ECDSA public key.
     *
     * @param compressedEcdsaPubKey the compressed ECDSA public key
     * @param sigPair the signature pair
     * @return the expanded signature pair, or null if the key is not a valid compressed ECDSA public key
     */
    public static @Nullable ExpandedSignaturePair maybeFrom(
            @NonNull final Bytes compressedEcdsaPubKey, @NonNull final SignaturePair sigPair) {
        final var ecdsaPubKey = decompressKey(compressedEcdsaPubKey);
        return ecdsaPubKey != null ? from(ecdsaPubKey, compressedEcdsaPubKey, sigPair) : null;
    }

    private static @NonNull ExpandedSignaturePair from(
            @NonNull final Bytes ecdsaPubKey,
            @NonNull final Bytes compressedEcdsaPubKey,
            @NonNull final SignaturePair sigPair) {
        final var evmAddress = extractEvmAddressFromDecompressedECDSAKey(ecdsaPubKey.toByteArray());
        return new ExpandedSignaturePair(
                Key.newBuilder().ecdsaSecp256k1(compressedEcdsaPubKey).build(),
                ecdsaPubKey,
                Bytes.wrap(evmAddress),
                sigPair);
    }
}
