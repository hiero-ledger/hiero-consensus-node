// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.StakePeriodTime;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema for the StakePeriodTime singleton that tracks the last time stake period updates were done.
 */
public class V0710TokenSchema extends Schema<SemanticVersion> {

    public static final String STAKE_PERIOD_TIME_KEY = "STAKE_PERIOD_TIME";
    public static final int STAKE_PERIOD_TIME_STATE_ID =
            SingletonType.TOKENSERVICE_I_STAKE_PERIOD_TIME.protoOrdinal();
    public static final String STAKE_PERIOD_TIME_STATE_LABEL =
            computeLabel(TokenService.NAME, STAKE_PERIOD_TIME_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(71).patch(0).build();

    public V0710TokenSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(STAKE_PERIOD_TIME_STATE_ID, STAKE_PERIOD_TIME_KEY, StakePeriodTime.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var previousStates = ctx.previousStates();
        if (ctx.isGenesis() || !previousStates.contains(STAKE_PERIOD_TIME_STATE_ID)) {
            final var stakePeriodTimeState = ctx.newStates().getSingleton(STAKE_PERIOD_TIME_STATE_ID);
            stakePeriodTimeState.put(StakePeriodTime.DEFAULT);
        }
    }
}

