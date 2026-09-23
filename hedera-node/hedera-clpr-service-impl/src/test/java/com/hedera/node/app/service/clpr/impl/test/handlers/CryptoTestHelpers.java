// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import net.i2p.crypto.eddsa.EdDSAEngine;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

final class CryptoTestHelpers {
    static final byte[] CLPR_SERVICE_ADDRESS = CLPR_EVM_ADDRESS_BYTES.toByteArray();

    public static Bytes computeConnectorId(final byte[] channelId, final byte[] pubKey, final byte[] salt) {
        final var preimage = new byte[channelId.length + pubKey.length + salt.length];
        System.arraycopy(channelId, 0, preimage, 0, channelId.length);
        System.arraycopy(pubKey, 0, preimage, channelId.length, pubKey.length);
        System.arraycopy(salt, 0, preimage, channelId.length + pubKey.length, salt.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    public static Bytes computeCommitment(final byte[] connectorId, final byte[] pubKey) {
        final var preimage = new byte[connectorId.length + pubKey.length];
        System.arraycopy(connectorId, 0, preimage, 0, connectorId.length);
        System.arraycopy(pubKey, 0, preimage, connectorId.length, pubKey.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    public static Bytes signEcdsa(final byte[] secretKey, final byte[] connectorId) {
        // Sign keccak256(connectorId || CLPR_SERVICE_ADDRESS)
        final var msgPreimage = new byte[connectorId.length + CLPR_SERVICE_ADDRESS.length];
        System.arraycopy(connectorId, 0, msgPreimage, 0, connectorId.length);
        System.arraycopy(CLPR_SERVICE_ADDRESS, 0, msgPreimage, connectorId.length, CLPR_SERVICE_ADDRESS.length);
        final byte[] msgHash =
                MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(msgPreimage)).toByteArray();

        final var recoverableSig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        final var signResult = LibSecp256k1.secp256k1_ecdsa_sign_recoverable(
                LibSecp256k1.CONTEXT, recoverableSig, msgHash, secretKey, null, null);
        if (signResult != 1) {
            throw new IllegalStateException("Failed to sign message");
        }
        final var compactSigBuffer = ByteBuffer.allocate(64);
        final var recId = new com.sun.jna.ptr.IntByReference(0);
        LibSecp256k1.secp256k1_ecdsa_recoverable_signature_serialize_compact(
                LibSecp256k1.CONTEXT, compactSigBuffer, recId, recoverableSig);
        return Bytes.wrap(compactSigBuffer.array());
    }

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

    public static Bytes signEd25519(final byte[] seed, final byte[] connectorId) {
        final var msgPreimage = new byte[connectorId.length + CLPR_SERVICE_ADDRESS.length];
        System.arraycopy(connectorId, 0, msgPreimage, 0, connectorId.length);
        System.arraycopy(CLPR_SERVICE_ADDRESS, 0, msgPreimage, connectorId.length, CLPR_SERVICE_ADDRESS.length);
        final byte[] msgHash =
                MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(msgPreimage)).toByteArray();
        try {
            final var engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initSign(Ed25519Utils.keyFrom(seed));
            engine.update(msgHash);
            return Bytes.wrap(engine.sign());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to sign with Ed25519", e);
        }
    }
}
