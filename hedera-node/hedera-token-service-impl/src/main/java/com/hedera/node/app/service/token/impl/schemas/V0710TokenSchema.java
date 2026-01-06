// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.StakePeriodInfo;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Schema for the StakePeriodInfo singleton that tracks information about stake period calculations.
 */
public class V0710TokenSchema extends Schema<SemanticVersion> {

    public static final String STAKE_PERIOD_INFO_KEY = "STAKE_PERIOD_INFO";
    public static final int STAKE_PERIOD_INFO_STATE_ID = SingletonType.TOKENSERVICE_I_STAKE_PERIOD_INFO.protoOrdinal();
    public static final String STAKE_PERIOD_INFO_STATE_LABEL = computeLabel(TokenService.NAME, STAKE_PERIOD_INFO_KEY);

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
                StateDefinition.singleton(STAKE_PERIOD_INFO_STATE_ID, STAKE_PERIOD_INFO_KEY, StakePeriodInfo.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var previousStates = ctx.previousStates();
        if (ctx.isGenesis() || !previousStates.contains(STAKE_PERIOD_INFO_STATE_ID)) {
            final var stakePeriodInfoState = ctx.newStates().getSingleton(STAKE_PERIOD_INFO_STATE_ID);
            stakePeriodInfoState.put(StakePeriodInfo.DEFAULT);
        }
    }
}
