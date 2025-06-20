// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.keys.deterministic;

import static com.hedera.services.bdd.spec.keys.deterministic.Bip0039.randomMnemonic;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Key;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

public class Ed25519Factory {
    public static EdDSAPrivateKey ed25519From(byte[] privateKey) {
        var params = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        var privateKeySpec = new EdDSAPrivateKeySpec(privateKey, params);
        return new EdDSAPrivateKey(privateKeySpec);
    }

    public static Key populatedFrom(byte[] pubKey) {
        return Key.newBuilder().setEd25519(ByteString.copyFrom(pubKey)).build();
    }

    public static void main(String... args) {
        System.out.println(randomMnemonic());
    }
}
