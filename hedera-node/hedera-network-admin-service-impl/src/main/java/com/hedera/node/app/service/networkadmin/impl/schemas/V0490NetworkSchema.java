// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;

/**
 * General schema for the network service. Currently a placeholder.
 */
public class V0490NetworkSchema extends Schema<SemanticVersion> {
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    /**
     * Constructs a new {@link V0490NetworkSchema}.
     */
    public V0490NetworkSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }
}
