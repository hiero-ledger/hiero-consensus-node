// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.base.crypto.ECDSASecp256k1;
import org.hiero.base.crypto.Ed25519;

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
        return Bytes.wrap(ECDSASecp256k1.signDigestWithEcdsaSecp256k1(secretKey, msgHash));
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
            return Bytes.wrap(Ed25519.signWithSeed(msgHash, seed));
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to sign with Ed25519", e);
        }
    }
}
