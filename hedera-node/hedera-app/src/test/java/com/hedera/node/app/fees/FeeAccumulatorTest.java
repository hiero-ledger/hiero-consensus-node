// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.stack.Savepoint;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeeAccumulatorTest {
    private static final Fees FEES = new Fees(1, 2, 3);
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID SECOND_PAYER_ID =
            AccountID.newBuilder().accountNum(1002L).build();
    private static final AccountID NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(101L).build();
    private static final AccountID SECOND_NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(102L).build();

    @Mock
    private TokenServiceApi tokenApi;

    @Mock(extraInterfaces = StreamBuilder.class)
    private FeeStreamBuilder recordBuilder;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private Savepoint savepoint;

    private FeeAccumulator subject;

    @BeforeEach
    void setUp() {
        subject = new FeeAccumulator(tokenApi, recordBuilder, stack);
    }

    @Test
    void automaticallyTracksNodeFeesToTopSavepointIfNodeAccountIsEligible() {
        final var captor = ArgumentCaptor.forClass(LongConsumer.class);

        given(tokenApi.chargeFees(
                        eq(AccountID.DEFAULT),
                        eq(NODE_ACCOUNT_ID),
                        eq(FEES),
                        eq(recordBuilder),
                        isNull(),
                        any(LongConsumer.class)))
                .willReturn(FEES);

        subject.chargeFees(AccountID.DEFAULT, NODE_ACCOUNT_ID, FEES, null);

        verify(tokenApi)
                .chargeFees(
                        eq(AccountID.DEFAULT),
                        eq(NODE_ACCOUNT_ID),
                        eq(FEES),
                        eq(recordBuilder),
                        isNull(),
                        captor.capture());

        final var onNodeFee = captor.getValue();
        given(stack.peek()).willReturn(savepoint);
        onNodeFee.accept(42L);

        verify(savepoint).trackCollectedNodeFee(42L);
    }

    @Test
    void refusesToRefundUnchargedFees() {
        assertThatThrownBy(() -> subject.refundFee(PAYER_ID, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot refund more fees");

        verify(tokenApi, never()).refundFee(eq(PAYER_ID), eq(1L), eq(recordBuilder));
    }

    @Test
    void refundsOnlyRemainingFeesForPrimaryPayer() {
        given(tokenApi.chargeFee(eq(PAYER_ID), eq(10L), eq((StreamBuilder) recordBuilder), isNull()))
                .willReturn(new Fees(0, 10, 0));

        subject.chargeFee(PAYER_ID, 10L, null);
        subject.refundFee(PAYER_ID, 7L);

        assertThatThrownBy(() -> subject.refundFee(PAYER_ID, 4L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested node/non-node fees 0/4");

        subject.refundFee(PAYER_ID, 3L);

        verify(tokenApi).refundFee(PAYER_ID, 7L, recordBuilder);
        verify(tokenApi).refundFee(PAYER_ID, 3L, recordBuilder);
        verify(tokenApi, never()).refundFee(PAYER_ID, 4L, recordBuilder);
    }

    @Test
    void creditsOnlyActuallyChargedNodeFees() {
        final var requestedFees = new Fees(10, 20, 30);
        final var chargedFees = new Fees(4, 20, 30);
        given(tokenApi.chargeFees(
                        eq(PAYER_ID),
                        eq(NODE_ACCOUNT_ID),
                        eq(requestedFees),
                        eq(recordBuilder),
                        isNull(),
                        any(LongConsumer.class)))
                .willReturn(chargedFees);

        subject.chargeFees(PAYER_ID, NODE_ACCOUNT_ID, requestedFees, null);

        assertThatThrownBy(() -> subject.refundFees(PAYER_ID, requestedFees, NODE_ACCOUNT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested node/non-node fees 10/50");

        verify(tokenApi, never())
                .refundFees(
                        eq(PAYER_ID),
                        eq(NODE_ACCOUNT_ID),
                        eq(requestedFees),
                        eq(recordBuilder),
                        any(LongConsumer.class));

        subject.refundFees(PAYER_ID, chargedFees, NODE_ACCOUNT_ID);

        verify(tokenApi)
                .refundFees(
                        eq(PAYER_ID), eq(NODE_ACCOUNT_ID), eq(chargedFees), eq(recordBuilder), any(LongConsumer.class));
    }

    @Test
    void tracksOverflowPayersSeparately() {
        given(tokenApi.chargeFee(eq(PAYER_ID), eq(5L), eq((StreamBuilder) recordBuilder), isNull()))
                .willReturn(new Fees(0, 5, 0));
        given(tokenApi.chargeFee(eq(SECOND_PAYER_ID), eq(7L), eq((StreamBuilder) recordBuilder), isNull()))
                .willReturn(new Fees(0, 7, 0));

        subject.chargeFee(PAYER_ID, 5L, null);
        subject.chargeFee(SECOND_PAYER_ID, 7L, null);

        assertThatThrownBy(() -> subject.refundFee(PAYER_ID, 6L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested node/non-node fees 0/6");
        assertThatThrownBy(() -> subject.refundFee(SECOND_PAYER_ID, 8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested node/non-node fees 0/8");

        subject.refundFee(PAYER_ID, 5L);
        subject.refundFee(SECOND_PAYER_ID, 7L);

        verify(tokenApi).refundFee(PAYER_ID, 5L, recordBuilder);
        verify(tokenApi).refundFee(SECOND_PAYER_ID, 7L, recordBuilder);
    }

    @Test
    void enforcesSameNodeAccountForLifecycle() {
        given(tokenApi.chargeFees(
                        eq(PAYER_ID),
                        eq(NODE_ACCOUNT_ID),
                        eq(FEES),
                        eq(recordBuilder),
                        isNull(),
                        any(LongConsumer.class)))
                .willReturn(FEES);

        subject.chargeFees(PAYER_ID, NODE_ACCOUNT_ID, FEES, null);

        assertThatThrownBy(() -> subject.chargeFees(PAYER_ID, SECOND_NODE_ACCOUNT_ID, FEES, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot change node account");

        verify(tokenApi, never())
                .chargeFees(
                        eq(PAYER_ID),
                        eq(SECOND_NODE_ACCOUNT_ID),
                        eq(FEES),
                        eq(recordBuilder),
                        isNull(),
                        any(LongConsumer.class));
    }

    @Test
    void resetClearsRefundableFeesAndNodeAccountInvariant() {
        given(tokenApi.chargeFee(eq(PAYER_ID), eq(10L), eq((StreamBuilder) recordBuilder), isNull()))
                .willReturn(new Fees(0, 10, 0));
        given(tokenApi.chargeFees(
                        eq(PAYER_ID),
                        eq(SECOND_NODE_ACCOUNT_ID),
                        eq(FEES),
                        eq(recordBuilder),
                        isNull(),
                        any(LongConsumer.class)))
                .willReturn(FEES);

        subject.chargeFee(PAYER_ID, 10L, null);
        subject.resetRefundableFees();

        assertThatThrownBy(() -> subject.refundFee(PAYER_ID, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested node/non-node fees 0/1");
        subject.chargeFees(PAYER_ID, SECOND_NODE_ACCOUNT_ID, FEES, null);

        verify(tokenApi, never()).refundFee(PAYER_ID, 1L, recordBuilder);
        verify(tokenApi)
                .chargeFees(
                        eq(PAYER_ID),
                        eq(SECOND_NODE_ACCOUNT_ID),
                        eq(FEES),
                        eq(recordBuilder),
                        isNull(),
                        any(LongConsumer.class));
    }
}
