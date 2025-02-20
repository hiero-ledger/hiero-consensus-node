// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.CONTEXT;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.SECP256K1_EC_UNCOMPRESSED;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.sun.jna.ptr.LongByReference;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * Utility class for recovering EVM addresses from keys.
 */
public final class EthSigsUtils {
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
        LibSecp256k1.secp256k1_pubkey pubKey = new LibSecp256k1.secp256k1_pubkey();
        var parseResult = LibSecp256k1.secp256k1_ec_pubkey_create(CONTEXT, pubKey, privateKeyBytes);
        if (parseResult == 1) {
            return recoverAddressFromPubKey(pubKey);
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
        LibSecp256k1.secp256k1_pubkey pubKey = new LibSecp256k1.secp256k1_pubkey();
        var parseResult = LibSecp256k1.secp256k1_ec_pubkey_parse(CONTEXT, pubKey, pubKeyBytes, pubKeyBytes.length);
        if (parseResult == 1) {
            return recoverAddressFromPubKey(pubKey);
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
    public static byte[] recoverAddressFromPubKey(@NonNull final LibSecp256k1.secp256k1_pubkey pubKey) {
        requireNonNull(pubKey);
        final ByteBuffer recoveredFullKey = ByteBuffer.allocate(65);
        final LongByReference fullKeySize = new LongByReference(recoveredFullKey.limit());
        LibSecp256k1.secp256k1_ec_pubkey_serialize(
                CONTEXT, recoveredFullKey, fullKeySize, pubKey, SECP256K1_EC_UNCOMPRESSED);

        recoveredFullKey.get(); // read and discard - recoveryId is not part of the account hash
        var preHash = new byte[64];
        recoveredFullKey.get(preHash, 0, 64);
        var keyHash = new Keccak.Digest256().digest(preHash);
        var address = new byte[20];
        arraycopy(keyHash, 12, address, 0, 20);
        return address;
    }
}
