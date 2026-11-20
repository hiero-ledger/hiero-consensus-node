// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import java.nio.ByteBuffer;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

final class CryptoTestHelpers {
    public static byte[] deriveEcdsaPublicKey(final byte[] secKey) {
        final var nativePubKey = new LibSecp256k1.secp256k1_pubkey();
        final var result = LibSecp256k1.secp256k1_ec_pubkey_create(LibSecp256k1.CONTEXT, nativePubKey, secKey);
        if (result != 1) {
            throw new IllegalStateException("Failed to derive public key");
        }
        final var outputBuffer = ByteBuffer.allocate(65);
        final var outputLength = new com.sun.jna.ptr.LongByReference(65);
        LibSecp256k1.secp256k1_ec_pubkey_serialize(
                LibSecp256k1.CONTEXT, outputBuffer, outputLength, nativePubKey, LibSecp256k1.SECP256K1_EC_UNCOMPRESSED);
        final var rawKey = new byte[64];
        outputBuffer.position(1);
        outputBuffer.get(rawKey);
        return rawKey;
    }

    public static byte[] deriveEd25519PublicKey(final byte[] seed) {
        return Ed25519Utils.extractEd25519PublicKey(Ed25519Utils.keyFrom(seed));
    }
}
