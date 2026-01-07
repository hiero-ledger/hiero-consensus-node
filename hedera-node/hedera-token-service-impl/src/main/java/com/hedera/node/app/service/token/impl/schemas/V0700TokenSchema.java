// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.StakePeriodInfo;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Set;

public class V0700TokenSchema extends Schema<SemanticVersion> {

    public static final String NODE_PAYMENTS_KEY = "NODE_PAYMENTS";
    public static final int NODE_PAYMENTS_STATE_ID = SingletonType.TOKENSERVICE_I_NODE_PAYMENTS.protoOrdinal();
    public static final String NODE_PAYMENTS_STATE_LABEL = computeLabel(TokenService.NAME, NODE_PAYMENTS_KEY);

    public static final String STAKE_PERIOD_INFO_KEY = "STAKE_PERIOD_INFO";
    public static final int STAKE_PERIOD_INFO_STATE_ID = SingletonType.TOKENSERVICE_I_STAKE_PERIOD_INFO.protoOrdinal();
    public static final String STAKE_PERIOD_INFO_STATE_LABEL = computeLabel(TokenService.NAME, STAKE_PERIOD_INFO_KEY);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(70).patch(0).build();

    public V0700TokenSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_KEY, NodePayments.PROTOBUF),
                StateDefinition.singleton(STAKE_PERIOD_INFO_STATE_ID, STAKE_PERIOD_INFO_KEY, StakePeriodInfo.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        final var previousStates = ctx.previousStates();
        if (ctx.isGenesis() || !previousStates.contains(NODE_PAYMENTS_STATE_ID)) {
            final var nodePaymentsState = ctx.newStates().getSingleton(NODE_PAYMENTS_STATE_ID);
            nodePaymentsState.put(NodePayments.DEFAULT);
        }
        final var stakePeriodInfoState = ctx.newStates().getSingleton(STAKE_PERIOD_INFO_STATE_ID);
        if (ctx.isGenesis() || !previousStates.contains(STAKE_PERIOD_INFO_STATE_ID)) {
            stakePeriodInfoState.put(StakePeriodInfo.DEFAULT);
        } else {
            final var nodeRewardsState = ctx.newStates().getSingleton(STAKING_NETWORK_REWARDS_STATE_ID);
            final var lastNodeRewardsPayment = ((NetworkStakingRewards) Objects.requireNonNull(nodeRewardsState.get()))
                    .lastNodeRewardPaymentsTime();
            stakePeriodInfoState.put(StakePeriodInfo.newBuilder()
                    .lastStakePeriodCalculationTime(lastNodeRewardsPayment)
                    .build());
        }
    }
}
