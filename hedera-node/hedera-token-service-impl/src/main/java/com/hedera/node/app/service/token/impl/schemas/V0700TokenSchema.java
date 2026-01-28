// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0700TokenSchema extends Schema<SemanticVersion> {

    public static final String NODE_PAYMENTS_KEY = "NODE_PAYMENTS";
    public static final int NODE_PAYMENTS_STATE_ID = SingletonType.TOKENSERVICE_I_NODE_PAYMENTS.protoOrdinal();
    public static final String NODE_PAYMENTS_STATE_LABEL = computeLabel(TokenService.NAME, NODE_PAYMENTS_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(70).patch(0).build();

    public V0700TokenSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.singleton(NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_KEY, NodePayments.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (!ctx.isGenesis() && !ctx.previousStates().contains(NODE_PAYMENTS_STATE_ID)) {
            ctx.newStates().<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID).put(NodePayments.DEFAULT);
        }
    }
}
