// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.workflows.handle.steps.HollowAccountCompletionsTest.asTxn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.handlers.FinalizeRecordHandler;
import com.hedera.node.app.service.token.records.FinalizeContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class RecordFinalizerTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private FinalizeRecordHandler finalizeRecordHandler;

    @Mock
    private Dispatch dispatch;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private FinalizeContext finalizeContext;

    @Mock
    private HandleContext handleContext;

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1_234L).build();
    private static final CryptoTransferTransactionBody TRANSFER_BODY = CryptoTransferTransactionBody.newBuilder()
            .transfers(TransferList.newBuilder()
                    .accountAmounts(
                            AccountAmount.newBuilder()
                                    .accountID(
                                            AccountID.newBuilder().accountNum(1).build())
                                    .amount(0)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(
                                            AccountID.newBuilder().accountNum(2).build())
                                    .amount(10)
                                    .build()))
            .build();
    private static final TransactionBody TX_BODY = asTxn(TRANSFER_BODY, PAYER_ID, CONSENSUS_NOW);
    private static final TransactionInfo TXN_INFO = new TransactionInfo(
            SignedTransaction.newBuilder()
                    .bodyBytes(TransactionBody.PROTOBUF.toBytes(TX_BODY))
                    .build(),
            TX_BODY,
            SignatureMap.DEFAULT,
            Bytes.EMPTY,
            HederaFunctionality.CRYPTO_TRANSFER,
            null);

    private RecordStreamBuilder recordBuilder = new RecordStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
    private RecordFinalizer subject;

    @BeforeEach
    void setUp() {
        subject = new RecordFinalizer(finalizeRecordHandler);

        lenient().when(dispatch.txnInfo()).thenReturn(TXN_INFO);
        lenient().when(dispatch.streamBuilder()).thenReturn(recordBuilder);
        lenient().when(dispatch.finalizeContext()).thenReturn(finalizeContext);
        lenient().when(dispatch.handleContext()).thenReturn(handleContext);
    }

    @Test
    public void finalizesStakingRecordForScheduledDispatchOfUserTxn() {
        given(dispatch.stack()).willReturn(stack);
        given(stack.permitsStakingRewards()).willReturn(true);

        when(dispatch.handleContext().dispatchPaidRewards()).thenReturn(Map.of());

        subject.finalizeRecord(dispatch);

        verify(finalizeRecordHandler)
                .finalizeStakingRecord(
                        eq(finalizeContext), eq(HederaFunctionality.CRYPTO_TRANSFER), eq(TX_BODY), any(), any());
        verify(finalizeRecordHandler, never()).finalizeNonStakingRecord(any(), any(), any());
    }

    @Test
    void includesExtraRewardReceiversOfScheduleExecutedByItsTrigger() {
        given(dispatch.stack()).willReturn(stack);
        given(stack.permitsStakingRewards()).willReturn(true);
        given(dispatch.txnInfo()).willReturn(txnInfoFor(HederaFunctionality.SCHEDULE_CREATE));
        when(dispatch.handleContext().dispatchPaidRewards()).thenReturn(Map.of());
        final var scheduled = successfulTransferBuilder(SCHEDULED);
        final var otherChild = successfulTransferBuilder(CHILD);
        doAnswer(invocation -> {
                    final Consumer<StreamBuilder> consumer = invocation.getArgument(1);
                    consumer.accept(scheduled);
                    consumer.accept(otherChild);
                    return null;
                })
                .when(stack)
                .forEachNonBaseBuilder(eq(StreamBuilder.class), any());

        subject.finalizeRecord(dispatch);

        // Only the scheduled child's zero-amount account is added; a non-scheduled child's is not
        verify(finalizeRecordHandler)
                .finalizeStakingRecord(
                        any(),
                        any(),
                        any(),
                        eq(Set.of(AccountID.newBuilder().accountNum(1).build())),
                        any());
    }

    @Test
    void doesNotLookAtChildrenOfNonScheduleTransactions() {
        given(dispatch.stack()).willReturn(stack);
        given(stack.permitsStakingRewards()).willReturn(true);
        when(dispatch.handleContext().dispatchPaidRewards()).thenReturn(Map.of());

        subject.finalizeRecord(dispatch);

        verify(stack, never()).forEachNonBaseBuilder(any(), any());
    }

    private static TransactionInfo txnInfoFor(final HederaFunctionality function) {
        return new TransactionInfo(TXN_INFO.signedTx(), TX_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, function, null);
    }

    private static StreamBuilder successfulTransferBuilder(final HandleContext.TransactionCategory category) {
        final var builder = mock(StreamBuilder.class);
        given(builder.category()).willReturn(category);
        lenient().when(builder.functionality()).thenReturn(HederaFunctionality.CRYPTO_TRANSFER);
        lenient().when(builder.transactionBody()).thenReturn(TX_BODY);
        lenient().when(builder.status()).thenReturn(SUCCESS);
        return builder;
    }

    @Test
    public void finalizesNonStakingRecordForIneligibleDispatchStack() {
        given(dispatch.stack()).willReturn(stack);

        subject.finalizeRecord(dispatch);

        verify(finalizeRecordHandler, never()).finalizeStakingRecord(any(), any(), any(), any(), any());
        verify(finalizeRecordHandler)
                .finalizeNonStakingRecord(finalizeContext, HederaFunctionality.CRYPTO_TRANSFER, TX_BODY);
    }

    @Test
    public void testExtraRewardReceiversCryptoTransfer() {
        recordBuilder.status(SUCCESS);
        Set<AccountID> extraRewardReceivers =
                subject.extraRewardReceivers(TX_BODY, HederaFunctionality.CRYPTO_TRANSFER, recordBuilder);

        assertEquals(1, extraRewardReceivers.size());
        assertTrue(extraRewardReceivers.contains(
                AccountID.newBuilder().accountNum(1).build()));
    }

    @Test
    public void testExtraRewardReceiversUnsuccessfulCryptoTransfer() {
        recordBuilder.status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);
        Set<AccountID> extraRewardReceivers =
                subject.extraRewardReceivers(TX_BODY, HederaFunctionality.CRYPTO_TRANSFER, recordBuilder);
        assertTrue(extraRewardReceivers.isEmpty());
    }

    @Test
    public void testExtraRewardReceiversOtherFunctionality() {
        recordBuilder.status(SUCCESS);

        Set<AccountID> extraRewardReceivers =
                subject.extraRewardReceivers(TX_BODY, HederaFunctionality.CONTRACT_CALL, recordBuilder);

        assertTrue(extraRewardReceivers.isEmpty());
    }

    @Test
    public void testExtraRewardReceiversNullBody() {
        recordBuilder.status(INVALID_TRANSACTION);

        Set<AccountID> extraRewardReceivers =
                subject.extraRewardReceivers(null, HederaFunctionality.CONTRACT_CALL, recordBuilder);

        assertTrue(extraRewardReceivers.isEmpty());
    }

    @Test
    public void testExtraRewardReceiversIrrelevantFunctionality() {
        recordBuilder.status(SUCCESS);

        Set<AccountID> extraRewardReceivers =
                subject.extraRewardReceivers(TX_BODY, CONSENSUS_SUBMIT_MESSAGE, recordBuilder);

        assertTrue(extraRewardReceivers.isEmpty());
    }

    @Test
    public void testZeroAdjustIdsFrom() {
        List<AccountAmount> accountAmounts = List.of(
                AccountAmount.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(1).build())
                        .amount(0)
                        .build(),
                AccountAmount.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(2).build())
                        .amount(10)
                        .build());

        Set<AccountID> zeroAdjustIds = subject.zeroAdjustIdsFrom(accountAmounts);

        assertEquals(1, zeroAdjustIds.size());
        assertTrue(zeroAdjustIds.contains(AccountID.newBuilder().accountNum(1).build()));
    }
}
