// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.schemas;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Initial mod-service schema for the token service.
 */
public class V0490TokenSchema extends Schema {

    // Initial virtual map capacity values. Previously these values had to be large enough to avoid
    // key hash collisions at the database level, which would result in low performance. With the
    // new feature of dynamic hash map resizing in the database, these capacity hints can be kept
    // low. These are just hints for initial virtual map sizes. Over time the maps will be able to
    // contain more elements, if needed
    private static final long MAX_STAKING_INFOS = 1_000L;
    private static final long MAX_TOKENS = 1_000_000L;
    private static final long MAX_ACCOUNTS = 1_000_000L;
    private static final long MAX_TOKEN_RELS = 1_000_000L;
    private static final long MAX_MINTABLE_NFTS = 1_000_000L;
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public static final String NFTS_KEY = "NFTS";
    public static final String TOKENS_KEY = "TOKENS";
    public static final String ALIASES_KEY = "ALIASES";
    public static final String ACCOUNTS_KEY = "ACCOUNTS";
    public static final String TOKEN_RELS_KEY = "TOKEN_RELS";
    public static final String STAKING_INFO_KEY = "STAKING_INFOS";
    public static final String STAKING_NETWORK_REWARDS_KEY = "STAKING_NETWORK_REWARDS";

    public V0490TokenSchema() {
        super(VERSION);
    }

    @NonNull
    @Override
    public Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.onDisk(TOKENS_KEY, TokenID.PROTOBUF, Token.PROTOBUF, MAX_TOKENS),
                StateDefinition.onDisk(ACCOUNTS_KEY, AccountID.PROTOBUF, Account.PROTOBUF, MAX_ACCOUNTS),
                StateDefinition.onDisk(ALIASES_KEY, ProtoBytes.PROTOBUF, AccountID.PROTOBUF, MAX_ACCOUNTS),
                StateDefinition.onDisk(NFTS_KEY, NftID.PROTOBUF, Nft.PROTOBUF, MAX_MINTABLE_NFTS),
                StateDefinition.onDisk(TOKEN_RELS_KEY, EntityIDPair.PROTOBUF, TokenRelation.PROTOBUF, MAX_TOKEN_RELS),
                StateDefinition.onDisk(
                        STAKING_INFO_KEY, EntityNumber.PROTOBUF, StakingNodeInfo.PROTOBUF, MAX_STAKING_INFOS),
                StateDefinition.singleton(STAKING_NETWORK_REWARDS_KEY, NetworkStakingRewards.PROTOBUF));
    }

    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis()) {
            final var networkRewardsState = ctx.newStates().getSingleton(STAKING_NETWORK_REWARDS_KEY);
            final var networkRewards = NetworkStakingRewards.newBuilder()
                    .pendingRewards(0)
                    .totalStakedRewardStart(0)
                    .totalStakedStart(0)
                    .stakingRewardsActivated(true)
                    .build();
            networkRewardsState.put(networkRewards);
        }
    }
}
