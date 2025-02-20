// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.signature;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

public class ExpandedSignaturePairFactory {

    private ExpandedSignaturePairFactory() {}

    /** Simple utility to create an ECDSA_SECP256K1 expanded signature */
    public static ExpandedSignaturePair ecdsaPair(final Key key) {
        final var compressed = key.ecdsaSecp256k1OrThrow();
        final var array = new byte[(int) compressed.length()];
        compressed.getBytes(0, array);
        final var decompressed = com.hedera.node.app.hapi.utils.MiscCryptoUtils.decompressSecp256k1(array);
        final var sigPair = SignaturePair.newBuilder()
                .pubKeyPrefix(key.ecdsaSecp256k1OrThrow())
                .ecdsaSecp256k1(key.ecdsaSecp256k1OrThrow())
                .build();
        return new ExpandedSignaturePair(key, Bytes.wrap(decompressed), null, sigPair);
    }

    /** Simple utility to create an ED25519 expanded signature */
    public static ExpandedSignaturePair ed25519Pair(final Key key) {
        final var sigPair = SignaturePair.newBuilder()
                .pubKeyPrefix(key.ed25519OrThrow())
                .ed25519(key.ed25519OrThrow())
                .build();
        return new ExpandedSignaturePair(key, key.ed25519OrThrow(), null, sigPair);
    }

    /** Simple utility to create an ECDSA_SECP256K1 hollow account based expanded signature */
    public static ExpandedSignaturePair hollowPair(final Key key, @NonNull final Account hollowAccount) {
        final var compressed = key.ecdsaSecp256k1OrThrow();
        final var array = new byte[(int) compressed.length()];
        compressed.getBytes(0, array);
        final var decompressed = com.hedera.node.app.hapi.utils.MiscCryptoUtils.decompressSecp256k1(array);
        final var sigPair = SignaturePair.newBuilder()
                .pubKeyPrefix(key.ecdsaSecp256k1OrThrow())
                .ecdsaSecp256k1(key.ecdsaSecp256k1OrThrow())
                .build();
        return new ExpandedSignaturePair(key, Bytes.wrap(decompressed), hollowAccount.alias(), sigPair);
    }
}
