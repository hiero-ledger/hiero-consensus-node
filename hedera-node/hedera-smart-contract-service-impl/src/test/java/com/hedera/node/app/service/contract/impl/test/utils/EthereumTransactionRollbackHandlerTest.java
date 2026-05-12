// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations.GasChargingAction.CHARGE;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations.GasChargingAction.REFUND;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations.GasChargingEvent;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.EthereumTransactionRollbackHandler;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.EntitiesConfig;
import com.swirlds.config.api.Configuration;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class EthereumTransactionRollbackHandlerTest {

    @Test
    public void gasChargesWithNonceIncrementAreCorrectlyReplayed() {
        // Given
        final var chargeAndRefundAccount =
                AccountID.newBuilder().accountNum(10_001L).build();
        final var chargeAmount = 90_000L;
        final var refundAmount = 2_000L;

        final var chargeOnlyAccount = AccountID.newBuilder().accountNum(10_002L).build();
        final var chargeOnlyAmount = 20_000L;

        final var gasChargingEvents = List.of(
                new GasChargingEvent(CHARGE, chargeAndRefundAccount, chargeAmount, true),
                new GasChargingEvent(CHARGE, chargeOnlyAccount, chargeOnlyAmount, false),
                new GasChargingEvent(REFUND, chargeAndRefundAccount, refundAmount, false));

        final var rootProxyWorldUpdater = mock(RootProxyWorldUpdater.class);
        when(rootProxyWorldUpdater.entityIdFactory()).thenReturn(new FakeEntityIdFactoryImpl(0, 0));
        final var subject = new EthereumTransactionRollbackHandler(
                mock(CallOutcome.class), gasChargingEvents, rootProxyWorldUpdater);

        final var feeChargingContext = mock(FeeCharging.Context.class);
        final var handleContext = mock(HandleContext.class);
        final var tokenServiceApi = mock(TokenServiceApi.class);
        final var storeFactory = mock(StoreFactory.class);
        when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);
        when(handleContext.storeFactory()).thenReturn(storeFactory);
        final var configuration = mock(Configuration.class);
        when(configuration.getConfigData(EntitiesConfig.class)).thenReturn(new EntitiesConfig(0, false, false));
        when(handleContext.configuration()).thenReturn(configuration);

        // When
        subject.replay(feeChargingContext, handleContext);

        // Then #1 - verify charges
        verify(feeChargingContext)
                .charge(eq(chargeAndRefundAccount), eq(new Fees(0, chargeAmount - refundAmount, 0)), any());
        verify(feeChargingContext).charge(eq(chargeOnlyAccount), eq(new Fees(0, chargeOnlyAmount, 0)), any());
        verifyNoMoreInteractions(feeChargingContext);

        // Then #2 - verify nonce updates
        verify(tokenServiceApi).incrementSenderNonce(chargeAndRefundAccount);
        verifyNoMoreInteractions(tokenServiceApi);
    }
}
