// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.clpr.impl.ClprBundleBilling;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprBundleBillingTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final AccountID SUBMITTER =
            AccountID.newBuilder().accountNum(3).build();
    private static final AccountID CONNECTOR_A =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID CONNECTOR_B =
            AccountID.newBuilder().accountNum(1002).build();
    private static final AccountID CONNECTOR_C =
            AccountID.newBuilder().accountNum(1003).build();
    // 12 tinycents buy 1 tinybar
    private static final ExchangeRate RATE =
            ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12).build();
    private static final long GAS_PRICE_TINYCENTS = 24L;
    private static final long BUNDLE_FEE = 100L;
    private static final long PROGRESS_REWARD_TINYCENTS = 120L;

    @Mock
    private HandleContext context;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ExchangeRateInfo exchangeRateInfo;

    private ClprBundleBilling subject;

    @BeforeEach
    void setUp() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(context.payer()).willReturn(SUBMITTER);
        given(context.consensusNow()).willReturn(NOW);
        given(context.exchangeRateInfo()).willReturn(exchangeRateInfo);
        given(exchangeRateInfo.activeRate(NOW)).willReturn(RATE);
        given(context.getGasPriceInTinycents()).willReturn(GAS_PRICE_TINYCENTS);
        given(context.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.progressRewardTinycents", PROGRESS_REWARD_TINYCENTS)
                        .getOrCreateConfig());
        subject = new ClprBundleBilling(context, BUNDLE_FEE);
    }

    @Test
    @DisplayName("gas cost is gas times the gas price, converted to tinybars at the active rate")
    void gasCostConvertsAtActiveRate() {
        // 1_000 gas * 24 tinycents / 12 tinycents per tinybar
        assertThat(subject.gasCost(1_000L)).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("escrow moves up to the requested amount from the connector to the submitter")
    void escrowIsCappedAtBalance() {
        assertThat(subject.escrowGas(account(CONNECTOR_A, 5_000L), 2_000L)).isEqualTo(2_000L);
        assertThat(subject.escrowGas(account(CONNECTOR_B, 700L), 2_000L)).isEqualTo(700L);

        verify(tokenServiceApi).transferFromTo(CONNECTOR_A, SUBMITTER, 2_000L);
        verify(tokenServiceApi).transferFromTo(CONNECTOR_B, SUBMITTER, 700L);
    }

    @Test
    @DisplayName("nothing is escrowed from an empty connector or from the submitter itself")
    void escrowSkipsEmptyConnectorAndSubmitter() {
        assertThat(subject.escrowGas(account(CONNECTOR_A, 0L), 2_000L)).isZero();
        assertThat(subject.escrowGas(account(SUBMITTER, 5_000L), 2_000L)).isZero();

        verifyNoInteractions(tokenServiceApi);
    }

    @Test
    @DisplayName("charging gas returns the unused escrow and pays the network for the used part")
    void chargeGasRefundsUnusedEscrow() {
        assertThat(subject.chargeGas(CONNECTOR_A, 2_000L, 600L)).isEqualTo(1_200L);

        verify(tokenServiceApi).transferFromTo(SUBMITTER, CONNECTOR_A, 800L);
        verify(context).tryToCharge(SUBMITTER, 1_200L);
    }

    @Test
    @DisplayName("gas beyond the escrow goes unpaid")
    void chargeGasIsCappedAtEscrow() {
        assertThat(subject.chargeGas(CONNECTOR_A, 500L, 600L)).isEqualTo(500L);

        verify(tokenServiceApi, never()).transferFromTo(any(), any(), anyLong());
        verify(context).tryToCharge(SUBMITTER, 500L);
    }

    @Test
    @DisplayName("settling a bundle with no billable messages pays the submitter nothing")
    void settleWithoutBillableMessagesPaysNothing() {
        assertThat(subject.settle(3L)).isZero();

        verifyNoInteractions(tokenServiceApi);
    }

    @Test
    @DisplayName("connectors' shares are proportional to their message counts and sum to the shared cost")
    void settleSplitsSharedCostExactly() {
        subject.recordBillableMessage(CONNECTOR_A);
        subject.recordBillableMessage(CONNECTOR_B);
        subject.recordBillableMessage(CONNECTOR_C);
        subject.recordBillableMessage(CONNECTOR_A);
        given(accountStore.getAccountById(any())).willAnswer(inv -> account(inv.getArgument(0), 1_000_000L));

        // Shared cost = Tm + X * Y = 100 + 3 * (120 / 12) = 130, over M = 4 messages (A: 2, B: 1, C: 1)
        assertThat(subject.settle(3L)).isEqualTo(130L);

        verify(tokenServiceApi).transferFromTo(CONNECTOR_A, SUBMITTER, 65L);
        verify(tokenServiceApi).transferFromTo(CONNECTOR_B, SUBMITTER, 32L);
        verify(tokenServiceApi).transferFromTo(CONNECTOR_C, SUBMITTER, 33L);
    }

    @Test
    @DisplayName("a connector short of its share pays its balance, and a missing one pays nothing")
    void settleIsBestEffort() {
        subject.recordBillableMessage(CONNECTOR_A);
        subject.recordBillableMessage(CONNECTOR_B);
        given(accountStore.getAccountById(CONNECTOR_A)).willReturn(account(CONNECTOR_A, 20L));
        given(accountStore.getAccountById(CONNECTOR_B)).willReturn(null);

        // Shared cost = 100 + 0 rewards, 50 each
        assertThat(subject.settle(0L)).isEqualTo(20L);

        verify(tokenServiceApi).transferFromTo(CONNECTOR_A, SUBMITTER, 20L);
        verify(tokenServiceApi, never()).transferFromTo(CONNECTOR_B, SUBMITTER, 50L);
    }

    private static Account account(final AccountID accountId, final long balance) {
        return Account.newBuilder().accountId(accountId).tinybarBalance(balance).build();
    }
}
