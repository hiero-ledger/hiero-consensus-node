// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.IndirectKeyUsersKey;
import com.hedera.hapi.node.state.token.IndirectKeyUsersValue;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema V0620: Adds the INDIRECT_KEY_USERS KV state for tracking doubly-linked lists of indirect key users.
 */
public class V069TokenSchema extends Schema<SemanticVersion> {
    private static final long MAX_INDIRECT_KEY_USERS = 1_000_000L;

    public static final int INDIRECT_KEY_USERS_STATE_ID =
            StateKey.KeyOneOfType.TOKENSERVICE_I_INDIRECT_KEY_USERS.protoOrdinal();
    public static final String INDIRECT_KEY_USERS_KEY = "INDIRECT_KEY_USERS";
    public static final String INDIRECT_KEY_USERS_STATE_LABEL = computeLabel(TokenService.NAME, INDIRECT_KEY_USERS_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(69).build();

    public V069TokenSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                INDIRECT_KEY_USERS_STATE_ID,
                INDIRECT_KEY_USERS_KEY,
                IndirectKeyUsersKey.PROTOBUF,
                IndirectKeyUsersValue.PROTOBUF,
                MAX_INDIRECT_KEY_USERS));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        // No migration actions required for this schema
    }
}
