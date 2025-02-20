// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import static java.util.stream.Collectors.toList;

import com.hederahashgraph.api.proto.java.Key;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class KeyTreeListNode implements KeyTreeNode {
    protected final List<KeyTreeNode> children;

    public KeyTreeListNode(List<KeyTreeNode> children) {
        this.children = children;
    }

    public List<KeyTreeNode> getChildren() {
        return children;
    }

    @Override
    public Key asKey(KeyFactory factory) {
        return factory.newList(
                children.stream().map(node -> node.asKey(factory)).collect(toList()));
    }

    @Override
    public int numLeaves() {
        return children.stream().mapToInt(KeyTreeNode::numLeaves).sum();
    }

    @Override
    public void traverse(Predicate<KeyTreeNode> shouldVisit, Consumer<KeyTreeNode> visitor) {
        if (shouldVisit.test(this)) {
            visitor.accept(this);
        }
        for (KeyTreeNode child : children) {
            child.traverse(shouldVisit, visitor);
        }
    }
}
