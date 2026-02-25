// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import org.hiero.base.crypto.SignatureType;

public class LeafFactory implements NodeFactory {
    private final String label;
    private final boolean usedToSign;
    private final SignatureType sigType;

    public LeafFactory(String label, boolean usedToSign, SignatureType sigType) {
        this.label = label;
        this.sigType = sigType;
        this.usedToSign = usedToSign;
    }

    public static final LeafFactory DEFAULT_FACTORY = new LeafFactory(null, true, SignatureType.ED25519);

    public String getLabel() {
        return label;
    }

    public boolean isUsedToSign() {
        return usedToSign;
    }

    public SignatureType getSigType() {
        return sigType;
    }
}
