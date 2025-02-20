// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import java.util.List;

public class ListFactory implements NodeFactory {
    final List<NodeFactory> childFactories;

    public ListFactory(List<NodeFactory> childFactories) {
        this.childFactories = childFactories;
    }
}
