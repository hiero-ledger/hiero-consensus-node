// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFO_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V0530TokenSchema extends Schema {
    private static final Logger logger = LogManager.getLogger(V0530TokenSchema.class);
    private static final long MAX_PENDING_AIRDROPS = 1_000_000_000L;
    public static final String AIRDROPS_KEY = "PENDING_AIRDROPS";

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(53).patch(0).build();

    public V0530TokenSchema() {
        super(VERSION);
    }

    @SuppressWarnings("rawtypes")
    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(StateDefinition.onDisk(
                AIRDROPS_KEY, PendingAirdropId.PROTOBUF, AccountPendingAirdrop.PROTOBUF, MAX_PENDING_AIRDROPS));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        setMinStakeToZero(ctx);
    }

    private void setMinStakeToZero(final MigrationContext ctx) {
        final WritableKVState<EntityNumber, StakingNodeInfo> stakingInfos =
                ctx.newStates().get(STAKING_INFO_KEY);
        logger.info("Setting minStake to 0 for all nodes in the address book");
        final var nodeIds = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(stakingInfos.keys(), Spliterator.NONNULL), false)
                .sorted(Comparator.comparingLong(EntityNumber::number))
                .toList();
        for (final var nodeId : nodeIds) {
            final var stakingInfo = requireNonNull(stakingInfos.get(nodeId));
            stakingInfos.put(nodeId, stakingInfo.copyBuilder().minStake(0).build());
        }
    }
}
