// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.clpr.impl.ClprSlashingUtils;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprSlashingUtilsTest {

    private static final long BASE_PENALTY = 10_000_000L; // 0.1 hbar
    private static final int MULTIPLIER = 2;

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final Bytes CONNECTOR_ID = Bytes.wrap(new byte[] {10, 20, 30});

    private static final long STAKING_ACCOUNT_NUM = 803L;
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID STAKING_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(STAKING_ACCOUNT_NUM).build();

    @Mock
    private WritableStates writableStates;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableAccountStore accountStore;

    private WritableConnectorStore connectorStore;
    private ClprConfig config;

    @BeforeEach
    void setUp() {
        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);

        config = HederaTestConfigBuilder.create()
                .withValue("clpr.slashBasePenalty", "10000000")
                .withValue("clpr.slashMultiplier", "2")
                .withValue("clpr.slashBanThreshold", "5")
                .withValue("clpr.stakingAccount", STAKING_ACCOUNT_NUM)
                .getOrCreateConfig()
                .getConfigData(ClprConfig.class);

        lenient().when(entityIdFactory.newAccountId(STAKING_ACCOUNT_NUM)).thenReturn(STAKING_ACCOUNT_ID);
    }

    @Test
    @DisplayName("computePenalty at slash count 0 returns base penalty")
    void computePenaltyAtZero() {
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 0, 1_000_000_000L))
                .isEqualTo(BASE_PENALTY);
    }

    @Test
    @DisplayName("computePenalty scales geometrically")
    void computePenaltyGeometric() {
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 1, 1_000_000_000L))
                .isEqualTo(20_000_000L);
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 2, 1_000_000_000L))
                .isEqualTo(40_000_000L);
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 3, 1_000_000_000L))
                .isEqualTo(80_000_000L);
    }

    @Test
    @DisplayName("computePenalty caps at lockedStake")
    void computePenaltyCappedAtStake() {
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 3, 50_000_000L))
                .isEqualTo(50_000_000L);
    }

    @Test
    @DisplayName("computePenalty handles overflow by capping at lockedStake")
    void computePenaltyOverflow() {
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 60, 100_000_000L))
                .isEqualTo(100_000_000L);
    }

    @Test
    @DisplayName("computePenalty returns 0 when basePenalty is 0")
    void computePenaltyZeroBase() {
        assertThat(ClprSlashingUtils.computePenalty(0, MULTIPLIER, 0, 100_000_000L))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("computePenalty returns 0 when lockedStake is 0")
    void computePenaltyZeroStake() {
        assertThat(ClprSlashingUtils.computePenalty(BASE_PENALTY, MULTIPLIER, 0, 0))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("applySlash below ban threshold deducts penalty, increments slashCount, persists connector")
    void applySlashBelowBanThresholdReducesStake() {
        final var connector = ClprConnector.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ID)
                .lockedStake(100_000_000L)
                .slashCount(0)
                .build();
        connectorStore.put(connector);

        final var result = ClprSlashingUtils.applySlash(connector, config, connectorStore);

        assertThat(result.banned()).isFalse();
        assertThat(result.penaltyAmount()).isEqualTo(10_000_000L);
        assertThat(result.updatedConnector()).isNotNull();
        assertThat(result.updatedConnector().slashCount()).isEqualTo(1);
        assertThat(result.updatedConnector().lockedStake()).isEqualTo(90_000_000L);

        final var persisted = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ID));
        assertThat(persisted).isNotNull();
        assertThat(persisted.slashCount()).isEqualTo(1);
        assertThat(persisted.lockedStake()).isEqualTo(90_000_000L);
    }

    @Test
    @DisplayName("applySlash at ban threshold forfeits remaining stake and removes connector")
    void applySlashAtBanThresholdRemovesConnector() {
        final var connector = ClprConnector.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ID)
                .lockedStake(100_000_000L)
                .slashCount(4) // banThreshold=5, so 4+1=5 → ban
                .build();
        connectorStore.put(connector);

        final var result = ClprSlashingUtils.applySlash(connector, config, connectorStore);

        assertThat(result.banned()).isTrue();
        assertThat(result.updatedConnector()).isNull();
        assertThat(result.penaltyAmount()).isEqualTo(100_000_000L); // full remaining stake, not 10M * 2^4

        assertThat(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ID)))
                .isNull();
    }

    @Test
    @DisplayName("reimburseEndpoint transfers from staking account to payer when fully backed")
    void reimburseEndpointTransfersFromStakingAccount() {
        givenStakingBalance(100_000_000L);

        final var reimbursed = ClprSlashingUtils.reimburseEndpoint(
                50_000_000L, PAYER_ID, config, entityIdFactory, accountStore, tokenServiceApi);

        assertThat(reimbursed).isEqualTo(50_000_000L);
        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT_ID, PAYER_ID, 50_000_000L);
    }

    @Test
    @DisplayName("reimburseEndpoint is a no-op when amount is zero")
    void reimburseEndpointIsNoOpWhenAmountIsZero() {
        final var reimbursed = ClprSlashingUtils.reimburseEndpoint(
                0L, PAYER_ID, config, entityIdFactory, accountStore, tokenServiceApi);

        assertThat(reimbursed).isZero();
        verifyNoInteractions(tokenServiceApi);
    }

    @Test
    @DisplayName("reimburseEndpoint is a no-op when amount is negative")
    void reimburseEndpointIsNoOpWhenAmountIsNegative() {
        final var reimbursed = ClprSlashingUtils.reimburseEndpoint(
                -1L, PAYER_ID, config, entityIdFactory, accountStore, tokenServiceApi);

        assertThat(reimbursed).isZero();
        verifyNoInteractions(tokenServiceApi);
    }

    @Test
    @DisplayName("reimburseEndpoint caps payout to the staking account's balance when underfunded")
    void reimburseEndpointCapsToStakingBalance() {
        // Staking account escrows only 3M, but the penalty is 10M. Paying the full 10M would credit
        // the endpoint 7M more than the staking account can back — the non-zero net hbar that trips
        // FAIL_INVALID in production. The reimbursement must be capped to what is actually backed.
        givenStakingBalance(3_000_000L);

        final var reimbursed = ClprSlashingUtils.reimburseEndpoint(
                10_000_000L, PAYER_ID, config, entityIdFactory, accountStore, tokenServiceApi);

        assertThat(reimbursed).isEqualTo(3_000_000L);
        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT_ID, PAYER_ID, 3_000_000L);
    }

    @Test
    @DisplayName("reimburseEndpoint pays nothing when the staking account does not exist")
    void reimburseEndpointPaysNothingWhenStakingAccountMissing() {
        // This is the observed production case: a well-known connector never posted stake, so
        // the staking account holds nothing for it (indeed 0.0.803 never existed on the ledger). The
        // slash still decrements the connector's locked_stake state field, but no hbar may be paid out.
        given(accountStore.getAccountById(STAKING_ACCOUNT_ID)).willReturn(null);

        final var reimbursed = ClprSlashingUtils.reimburseEndpoint(
                10_000_000L, PAYER_ID, config, entityIdFactory, accountStore, tokenServiceApi);

        assertThat(reimbursed).isZero();
        verifyNoInteractions(tokenServiceApi);
    }

    @Test
    @DisplayName("reimburseEndpoint pays nothing when the staking account balance is zero")
    void reimburseEndpointPaysNothingWhenStakingBalanceZero() {
        givenStakingBalance(0L);

        final var reimbursed = ClprSlashingUtils.reimburseEndpoint(
                10_000_000L, PAYER_ID, config, entityIdFactory, accountStore, tokenServiceApi);

        assertThat(reimbursed).isZero();
        verifyNoInteractions(tokenServiceApi);
    }

    private void givenStakingBalance(final long tinybars) {
        given(accountStore.getAccountById(STAKING_ACCOUNT_ID))
                .willReturn(Account.newBuilder()
                        .accountId(STAKING_ACCOUNT_ID)
                        .tinybarBalance(tinybars)
                        .build());
    }
}
