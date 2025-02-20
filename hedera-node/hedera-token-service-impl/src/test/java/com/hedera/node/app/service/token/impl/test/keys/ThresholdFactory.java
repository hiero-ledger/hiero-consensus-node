// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import java.util.List;

public class ThresholdFactory extends ListFactory {
    final int M;

    public ThresholdFactory(List<NodeFactory> childFactories, int M) {
        super(childFactories);
        this.M = M;
    }
}
