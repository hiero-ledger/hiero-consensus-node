// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.calculator.CryptoApproveAllowanceFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoDeleteAllowanceFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoGetAccountBalanceFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoGetAccountRecordsFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoGetInfoFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoTransferFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.CryptoUpdateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenAirdropFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenAssociateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenBurnFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenCancelAirdropFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenClaimAirdropFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenCreateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenDeleteFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenDissociateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenFeeScheduleUpdateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenFreezeAccountFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenGetInfoFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenGetNftInfoFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenGrantKycFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenMintFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenPauseFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenRejectFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenRevokeKycFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUnfreezeAccountFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUnpauseFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUpdateFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenUpdateNftsFeeCalculator;
import com.hedera.node.app.service.token.impl.calculator.TokenWipeFeeCalculator;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.ZoneId;
import java.util.Set;

/** An implementation of the {@link TokenService} interface. */
public class TokenServiceImpl implements TokenService {
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    public static final long MAX_SERIAL_NO_ALLOWED = 0xFFFFFFFFL;
    public static final long HBARS_TO_TINYBARS = 100_000_000L;
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    public TokenServiceImpl(@NonNull final AppContext appContext) {
        requireNonNull(appContext);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0490TokenSchema());
        registry.register(new V0530TokenSchema());
        registry.register(new V0610TokenSchema());
        registry.register(new V0700TokenSchema());
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        final var networkRewardsState = writableStates.getSingleton(STAKING_NETWORK_REWARDS_STATE_ID);
        final var networkRewards = NetworkStakingRewards.newBuilder()
                .pendingRewards(0)
                .totalStakedRewardStart(0)
                .totalStakedStart(0)
                .stakingRewardsActivated(true)
                .build();
        networkRewardsState.put(networkRewards);
        writableStates.<NodeRewards>getSingleton(NODE_REWARDS_STATE_ID).put(NodeRewards.DEFAULT);
        writableStates.<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID).put(NodePayments.DEFAULT);
        return true;
    }

    @Override
    public Set<ServiceFeeCalculator> serviceFeeCalculators() {
        return Set.of(
                new CryptoApproveAllowanceFeeCalculator(),
                new CryptoCreateFeeCalculator(),
                new CryptoDeleteAllowanceFeeCalculator(),
                new CryptoDeleteFeeCalculator(),
                new CryptoUpdateFeeCalculator(),
                new CryptoTransferFeeCalculator(),
                new TokenAirdropFeeCalculator(),
                new TokenAssociateFeeCalculator(),
                new TokenBurnFeeCalculator(),
                new TokenCancelAirdropFeeCalculator(),
                new TokenClaimAirdropFeeCalculator(),
                new TokenCreateFeeCalculator(),
                new TokenDeleteFeeCalculator(),
                new TokenDissociateFeeCalculator(),
                new TokenFeeScheduleUpdateFeeCalculator(),
                new TokenFreezeAccountFeeCalculator(),
                new TokenGrantKycFeeCalculator(),
                new TokenMintFeeCalculator(),
                new TokenPauseFeeCalculator(),
                new TokenRejectFeeCalculator(),
                new TokenRevokeKycFeeCalculator(),
                new TokenUpdateFeeCalculator(),
                new TokenUpdateNftsFeeCalculator(),
                new TokenUnfreezeAccountFeeCalculator(),
                new TokenUnpauseFeeCalculator(),
                new TokenWipeFeeCalculator());
    }

    @Override
    public Set<QueryFeeCalculator> queryFeeCalculators() {
        return Set.of(
                new CryptoGetInfoFeeCalculator(),
                new CryptoGetAccountRecordsFeeCalculator(),
                new CryptoGetAccountBalanceFeeCalculator(),
                new TokenGetInfoFeeCalculator(),
                new TokenGetNftInfoFeeCalculator());
    }
}
