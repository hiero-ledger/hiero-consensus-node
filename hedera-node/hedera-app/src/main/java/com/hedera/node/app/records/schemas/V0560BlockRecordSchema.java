// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.Schema;

public class V0560BlockRecordSchema extends Schema<SemanticVersion> {
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(56).patch(0).build();

    public V0560BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }
}
