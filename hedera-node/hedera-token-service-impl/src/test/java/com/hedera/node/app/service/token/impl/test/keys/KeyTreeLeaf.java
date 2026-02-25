// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Key;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.hiero.base.crypto.SignatureType;

public class KeyTreeLeaf implements KeyTreeNode {
    private String label;
    private boolean usedToSign = true;
    private SignatureType sigType = SignatureType.ED25519;
    private final Key NONSENSE_RSA_KEY =
            Key.newBuilder().setRSA3072(ByteString.copyFrom("MOME".getBytes())).build();
    private final Map<KeyFactory, Key> keyCache = new HashMap<>();

    public KeyTreeLeaf(boolean usedToSign, String label, SignatureType sigType) {
        this.label = label;
        this.sigType = sigType;
        this.usedToSign = usedToSign;
    }

    public KeyTreeLeaf() {}

    public boolean isUsedToSign() {
        return usedToSign;
    }

    public SignatureType getSigType() {
        return sigType;
    }

    @Override
    public Key asKey(KeyFactory factory) {
        return keyCache.computeIfAbsent(factory, this::customKey);
    }

    private Key customKey(KeyFactory factory) {
        if (sigType == SignatureType.ED25519) {
            return Optional.ofNullable(label).map(factory::labeledEd25519).orElse(factory.newEd25519());
        } else if (sigType == SignatureType.ECDSA_SECP256K1) {
            return Optional.ofNullable(label)
                    .map(factory::labeledEcdsaSecp256k1)
                    .orElse(factory.newEcdsaSecp256k1());
        } else if (sigType == SignatureType.RSA) {
            return NONSENSE_RSA_KEY;
        }
        throw new AssertionError("Impossible signature type: " + sigType + "!");
    }

    @Override
    public int numLeaves() {
        return 1;
    }

    @Override
    public void traverse(Predicate<KeyTreeNode> shouldVisit, Consumer<KeyTreeNode> visitor) {
        if (shouldVisit.test(this)) {
            visitor.accept(this);
        }
    }
}
