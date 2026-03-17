// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.platform.state.StateKey;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Defines the schema for registered nodes.
 */
public class V073AddressBookSchema extends Schema<SemanticVersion> {
    public static final String REGISTERED_NODES_KEY = "REGISTERED_NODES";
    public static final int REGISTERED_NODES_STATE_ID =
            StateKey.KeyOneOfType.ADDRESSBOOKSERVICE_I_REGISTERED_NODES.protoOrdinal();

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    /**
     * Constructs a new schema instance for version 0.73.0,
     * using the semantic version comparator for version management.
     */
    public V073AddressBookSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.keyValue(
                REGISTERED_NODES_STATE_ID, REGISTERED_NODES_KEY, EntityNumber.PROTOBUF, RegisteredNode.PROTOBUF));
    }
}
