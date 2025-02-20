// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hederahashgraph.api.proto.java.Key;

public class KeyTree {
    private final KeyTreeNode root;

    private KeyTree(final KeyTreeNode root) {
        this.root = root;
    }

    public static KeyTree withRoot(final NodeFactory rootFactory) {
        return new KeyTree(KeyTreeNode.from(rootFactory));
    }

    public Key asKey() {
        return asKey(KeyFactory.getDefaultInstance());
    }

    public com.hedera.hapi.node.base.Key asPbjKey() {
        return CommonPbjConverters.protoToPbj(asKey(), com.hedera.hapi.node.base.Key.class);
    }

    public Key asKey(final KeyFactory factoryToUse) {
        return root.asKey(factoryToUse);
    }
}
