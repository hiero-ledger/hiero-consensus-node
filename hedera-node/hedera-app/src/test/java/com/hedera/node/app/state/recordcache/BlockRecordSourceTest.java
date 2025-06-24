// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.recordcache;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.CreateAccountOutput;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.blocks.BlockItemsTranslator;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.TranslationContext;
import com.hedera.node.app.spi.records.RecordSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockRecordSourceTest {
    private static final TransactionRecord FIRST_RECORD = TransactionRecord.newBuilder()
            .receipt(TransactionReceipt.newBuilder()
                    .status(SCHEDULE_ALREADY_DELETED)
                    .build())
            .transactionID(TransactionID.newBuilder().nonce(1).build())
            .memo("FIRST")
            .build();
    private static final TransactionRecord SECOND_RECORD = TransactionRecord.newBuilder()
            .receipt(TransactionReceipt.newBuilder().status(SUCCESS).build())
            .transactionID(TransactionID.newBuilder().nonce(2).build())
            .memo("SECOND")
            .build();
    private static final AccountID ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1L).build();
    private static final BlockItem FIRST_OUTPUT = BlockItem.newBuilder()
            .transactionOutput(TransactionOutput.newBuilder()
                    .accountCreate(new CreateAccountOutput(ACCOUNT_ID))
                    .build())
            .build();
    private static final BlockItem TRANSACTION_RESULT = BlockItem.newBuilder()
            .transactionResult(
                    TransactionResult.newBuilder().transactionFeeCharged(123L).build())
            .build();

    @Mock
    private BlockItemsTranslator recordTranslator;

    @Mock
    private Consumer<BlockItem> itemAction;

    @Mock
    private Consumer<TransactionRecord> recordAction;

    @Mock
    private TranslationContext translationContext;

    private BlockRecordSource subject;

    @Test
    void actionSeesAllItems() {
        subjectWith(List.of(
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT, FIRST_OUTPUT), translationContext),
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT), translationContext)));

        subject.forEachItem(itemAction);

        verify(itemAction, times(3)).accept(any(BlockItem.class));
    }

    @Test
    void hasDefaultBlockItemTranslator() {
        assertDoesNotThrow(() -> new BlockRecordSource(List.of()));
    }

    @Test
    void forEachTxnRecordIncludesRecordsFromAllOutputs() {
        given(recordTranslator.translateRecord(
                        translationContext,
                        TRANSACTION_RESULT.transactionResultOrThrow(),
                        null,
                        FIRST_OUTPUT.transactionOutputOrThrow()))
                .willReturn(FIRST_RECORD);
        given(recordTranslator.translateRecord(translationContext, TRANSACTION_RESULT.transactionResultOrThrow(), null))
                .willReturn(SECOND_RECORD);
        subjectWith(List.of(
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT, FIRST_OUTPUT), translationContext),
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT), translationContext)));

        subject.forEachTxnRecord(recordAction);

        verify(recordAction).accept(FIRST_RECORD);
        verify(recordAction).accept(SECOND_RECORD);

        assertDoesNotThrow(() -> subject.forEachTxnRecord(recordAction));
    }

    @Test
    void identifiedReceiptsIncludeReceiptsFromAllOutputs() {
        given(translationContext.txnId())
                .willReturn(FIRST_RECORD.transactionIDOrThrow())
                .willReturn(SECOND_RECORD.transactionIDOrThrow());
        given(recordTranslator.translateReceipt(
                        translationContext,
                        TRANSACTION_RESULT.transactionResultOrThrow(),
                        FIRST_OUTPUT.transactionOutputOrThrow()))
                .willReturn(FIRST_RECORD.receiptOrThrow());
        given(recordTranslator.translateReceipt(translationContext, TRANSACTION_RESULT.transactionResultOrThrow()))
                .willReturn(SECOND_RECORD.receiptOrThrow());
        subjectWith(List.of(
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT, FIRST_OUTPUT), translationContext),
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT), translationContext)));

        assertThat(subject.identifiedReceipts())
                .containsExactly(
                        new RecordSource.IdentifiedReceipt(
                                FIRST_RECORD.transactionIDOrThrow(), FIRST_RECORD.receiptOrThrow()),
                        new RecordSource.IdentifiedReceipt(
                                SECOND_RECORD.transactionIDOrThrow(), SECOND_RECORD.receiptOrThrow()));

        assertDoesNotThrow(() -> subject.identifiedReceipts());
    }

    @Test
    void findsReceiptForPresentTxnIdAndThrowsOtherwise() {
        given(translationContext.txnId())
                .willReturn(FIRST_RECORD.transactionIDOrThrow())
                .willReturn(SECOND_RECORD.transactionIDOrThrow());
        given(recordTranslator.translateReceipt(
                        translationContext,
                        TRANSACTION_RESULT.transactionResultOrThrow(),
                        FIRST_OUTPUT.transactionOutputOrThrow()))
                .willReturn(FIRST_RECORD.receiptOrThrow());
        given(recordTranslator.translateReceipt(translationContext, TRANSACTION_RESULT.transactionResultOrThrow()))
                .willReturn(SECOND_RECORD.receiptOrThrow());
        subjectWith(List.of(
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT, FIRST_OUTPUT), translationContext),
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT), translationContext)));

        assertThat(subject.receiptOf(FIRST_RECORD.transactionIDOrThrow())).isEqualTo(FIRST_RECORD.receiptOrThrow());
        assertThat(subject.receiptOf(SECOND_RECORD.transactionIDOrThrow())).isEqualTo(SECOND_RECORD.receiptOrThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.receiptOf(TransactionID.newBuilder().nonce(3).build()));
    }

    @Test
    void findsChildReceiptsForTxnIdAndEmptyListOtherwise() {
        given(translationContext.txnId())
                .willReturn(FIRST_RECORD.transactionIDOrThrow())
                .willReturn(SECOND_RECORD.transactionIDOrThrow());
        given(recordTranslator.translateReceipt(
                        translationContext,
                        TRANSACTION_RESULT.transactionResultOrThrow(),
                        FIRST_OUTPUT.transactionOutputOrThrow()))
                .willReturn(FIRST_RECORD.receiptOrThrow());
        given(recordTranslator.translateReceipt(translationContext, TRANSACTION_RESULT.transactionResultOrThrow()))
                .willReturn(SECOND_RECORD.receiptOrThrow());
        subjectWith(List.of(
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT, FIRST_OUTPUT), translationContext),
                new BlockStreamBuilder.Output(List.of(TRANSACTION_RESULT), translationContext)));

        assertThat(subject.childReceiptsOf(TransactionID.DEFAULT))
                .containsExactly(FIRST_RECORD.receiptOrThrow(), SECOND_RECORD.receiptOrThrow());
        assertThat(subject.childReceiptsOf(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(2L).build())
                        .build()))
                .isEmpty();
    }

    private void subjectWith(@NonNull final List<BlockStreamBuilder.Output> outputs) {
        subject = new BlockRecordSource(recordTranslator, outputs);
    }
}
