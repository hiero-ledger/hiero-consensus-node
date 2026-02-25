// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import java.util.List;
import org.hiero.base.crypto.SignatureType;

public interface NodeFactory {
    static NodeFactory ecdsa384Secp256k1(boolean usedToSign) {
        return new LeafFactory(null, usedToSign, SignatureType.ECDSA_SECP256K1);
    }

    static NodeFactory ecdsa384Secp256k1() {
        return new LeafFactory(null, true, SignatureType.ECDSA_SECP256K1);
    }

    static NodeFactory ed25519() {
        return LeafFactory.DEFAULT_FACTORY;
    }

    static NodeFactory ed25519(boolean usedToSign) {
        return new LeafFactory(null, usedToSign, SignatureType.ED25519);
    }

    static NodeFactory ed25519(String label) {
        return new LeafFactory(label, true, SignatureType.ED25519);
    }

    static NodeFactory ed25519(String label, boolean usedToSign) {
        return new LeafFactory(label, usedToSign, SignatureType.ED25519);
    }

    static NodeFactory list(NodeFactory... childFactories) {
        return new ListFactory(List.of(childFactories));
    }

    static NodeFactory threshold(int M, NodeFactory... childFactories) {
        return new ThresholdFactory(List.of(childFactories), M);
    }
}
