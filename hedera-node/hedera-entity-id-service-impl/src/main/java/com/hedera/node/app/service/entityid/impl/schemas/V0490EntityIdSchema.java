// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.entityid.impl.EntityIdServiceImpl;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0490EntityIdSchema extends Schema<SemanticVersion> {
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public static final String ENTITY_ID_KEY = "ENTITY_ID";
    public static final int ENTITY_ID_STATE_ID = SingletonType.ENTITYIDSERVICE_I_ENTITY_ID.protoOrdinal();
    public static final String ENTITY_ID_STATE_LABEL = computeLabel(EntityIdServiceImpl.NAME, ENTITY_ID_KEY);

    public V0490EntityIdSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    /**
     * Gets a {@link Set} of state definitions for states to create in this schema. For example,
     * perhaps in this version of the schema, you need to create a new state FOO. The set will have
     * a {@link StateDefinition} specifying the metadata for that state.
     *
     * @return A map of all states to be created. Possibly empty.
     */
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(ENTITY_ID_STATE_ID, ENTITY_ID_KEY, EntityNumber.PROTOBUF));
    }
}
