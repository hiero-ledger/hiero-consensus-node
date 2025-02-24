// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import com.hedera.hapi.node.base.Key;

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

    public Key asKey(final KeyFactory factoryToUse) {
        return root.asKey(factoryToUse);
    }
}
