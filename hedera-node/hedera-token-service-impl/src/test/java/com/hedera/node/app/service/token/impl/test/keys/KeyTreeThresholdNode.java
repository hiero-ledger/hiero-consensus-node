// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import static java.util.stream.Collectors.toList;

import com.hederahashgraph.api.proto.java.Key;
import java.util.List;

public class KeyTreeThresholdNode extends KeyTreeListNode {
    private final int M;

    public KeyTreeThresholdNode(List<KeyTreeNode> children, int M) {
        super(children);
        this.M = M;
    }

    @Override
    public Key asKey(KeyFactory factory) {
        return factory.newThreshold(
                children.stream().map(node -> node.asKey(factory)).collect(toList()), M);
    }
}
