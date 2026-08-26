// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.foreign.MemorySegment;

/**
 * Utility class for recovering EVM addresses from keys.
 */
public final class EthSigsUtils {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();

    private EthSigsUtils() {}

    /**
     * Recover the address from a private key.
     * @param privateKeyBytes The private key bytes.
     * @return The address.
     */
    public static byte[] recoverAddressFromPrivateKey(@NonNull final byte[] privateKeyBytes) {
        requireNonNull(privateKeyBytes);
        // Create public key from private key
        // Return address from public key
        byte[] pubKey = new byte[Libsecp256k1.PUBLIC_KEY_BYTES];
        var parseResult = LIBSECP256K1.secp256k1EcPubkeyCreate(
                MemorySegment.ofArray(pubKey), MemorySegment.ofArray(privateKeyBytes));
        if (parseResult == 1) {
            return recoverAddressFromParsedPubKey(pubKey);
        } else {
            return new byte[0];
        }
    }

    /**
     * Recover the address from a public key.
     * @param pubKeyBytes The public key bytes.
     * @return The address.
     */
    public static byte[] recoverAddressFromPubKey(@NonNull final byte[] pubKeyBytes) {
        requireNonNull(pubKeyBytes);
        byte[] pubKey = new byte[Libsecp256k1.PUBLIC_KEY_BYTES];
        var parseResult = LIBSECP256K1.secp256k1EcPubkeyParse(
                MemorySegment.ofArray(pubKey), MemorySegment.ofArray(pubKeyBytes), pubKeyBytes.length);
        if (parseResult == 1) {
            return recoverAddressFromParsedPubKey(pubKey);
        } else {
            return new byte[0];
        }
    }

    /**
     * Recover the address from a public key.
     * @param pubKeyBytes The public key bytes.
     * @return The address.
     */
    public static Bytes recoverAddressFromPubKey(@NonNull final Bytes pubKeyBytes) {
        requireNonNull(pubKeyBytes);
        return Bytes.wrap(recoverAddressFromPubKey(pubKeyBytes.toByteArray()));
    }

    /**
     * Recover the address from a public key.
     * @param pubKey The public key.
     * @return The address.
     */
    public static byte[] recoverAddressFromParsedPubKey(@NonNull final byte[] pubKey) {
        requireNonNull(pubKey);
        final byte[] recoveredFullKey = new byte[65];
        final long[] fullKeySize = new long[] {recoveredFullKey.length};
        LIBSECP256K1.secp256k1EcPubkeySerialize(
                MemorySegment.ofArray(recoveredFullKey),
                MemorySegment.ofArray(fullKeySize),
                MemorySegment.ofArray(pubKey),
                Libsecp256k1.SECP256K1_EC_UNCOMPRESSED);

        var preHash = new byte[64];
        System.arraycopy(recoveredFullKey, 1, preHash, 0, 64);
        var keyHash = MiscCryptoUtils.keccak256DigestOf(preHash);
        var address = new byte[20];
        arraycopy(keyHash, 12, address, 0, 20);
        return address;
    }
}
