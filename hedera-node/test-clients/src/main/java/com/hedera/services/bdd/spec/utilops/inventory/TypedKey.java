// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;

import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.keys.Secp256k1Utils;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;

public record TypedKey(PrivateKey privateKey, byte[] pubKey, SigControl type) {
    public static TypedKey from(final PrivateKey privateKey) {
        final var type = deriveTypeFrom(privateKey);
        final var pubKey = type == SigControl.SECP256K1_ON
                ? Secp256k1Utils.extractEcdsaPublicKey((ECPrivateKey) privateKey)
                : Ed25519Utils.extractEd25519PublicKey((EdDSAPrivateKey) privateKey);
        return new TypedKey(privateKey, pubKey, type);
    }

    private static SigControl deriveTypeFrom(final PrivateKey pk) {
        return (pk instanceof ECPrivateKey) ? SigControl.SECP256K1_ON : ED25519_ON;
    }
}
