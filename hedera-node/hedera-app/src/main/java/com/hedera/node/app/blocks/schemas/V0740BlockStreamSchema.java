// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;

/**
 * Marker schema for block stream state as of release 0.74.0.
 */
public class V0740BlockStreamSchema extends Schema<SemanticVersion> {

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(74).patch(0).build();

    public V0740BlockStreamSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }
}
