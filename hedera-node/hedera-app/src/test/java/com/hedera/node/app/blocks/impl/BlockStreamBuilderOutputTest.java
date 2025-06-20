// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.CreateAccountOutput;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.node.app.blocks.BlockItemsTranslator;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamBuilderOutputTest {
    private static final Timestamp CONSENSUS_TIME = new Timestamp(1_234_567, 890);
    private static final TransactionID TXN_ID = TransactionID.newBuilder()
            .accountID(AccountID.newBuilder().accountNum(2L).build())
            .build();
    private static final AccountID ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1L).build();
    private static final ContractFunctionResult FUNCTION_RESULT =
            ContractFunctionResult.newBuilder().amount(666L).build();
    private static final BlockItem EVENT_TRANSACTION = BlockItem.newBuilder()
            .eventTransaction(EventTransaction.newBuilder()
                    .applicationTransaction(Bytes.wrap("MOCK"))
                    .build())
            .build();
    private static final BlockItem TRANSACTION_RESULT = BlockItem.newBuilder()
            .transactionResult(
                    TransactionResult.newBuilder().transactionFeeCharged(123L).build())
            .build();
    private static final BlockItem FIRST_OUTPUT = BlockItem.newBuilder()
            .transactionOutput(TransactionOutput.newBuilder()
                    .accountCreate(new CreateAccountOutput(ACCOUNT_ID))
                    .build())
            .build();
    private static final BlockItem SECOND_OUTPUT = BlockItem.newBuilder()
            .transactionOutput(TransactionOutput.newBuilder()
                    .contractCall(new CallContractOutput(FUNCTION_RESULT))
                    .build())
            .build();
    private static final BlockItem STATE_CHANGES = BlockItem.newBuilder()
            .stateChanges(new StateChanges(CONSENSUS_TIME, List.of()))
            .build();
    private static final List<BlockItem> ITEMS_NO_OUTPUTS =
            List.of(EVENT_TRANSACTION, TRANSACTION_RESULT, STATE_CHANGES);
    private static final List<BlockItem> ITEMS_WITH_OUTPUTS =
            List.of(EVENT_TRANSACTION, TRANSACTION_RESULT, FIRST_OUTPUT, SECOND_OUTPUT, STATE_CHANGES);

    @Mock
    private Consumer<BlockItem> action;

    @Mock
    private TranslationContext translationContext;

    @Mock
    private BlockItemsTranslator translator;

    @Test
    void traversesItemsAsExpected() {
        final var subject = new BlockStreamBuilder.Output(ITEMS_WITH_OUTPUTS, translationContext);

        subject.forEachItem(action);

        ITEMS_WITH_OUTPUTS.forEach(item -> verify(action).accept(item));
    }

    @Test
    void translatesNoOutputsToRecordAsExpected() {
        given(translator.translateRecord(translationContext, TRANSACTION_RESULT.transactionResultOrThrow(), null))
                .willReturn(TransactionRecord.DEFAULT);

        final var subject = new BlockStreamBuilder.Output(ITEMS_NO_OUTPUTS, translationContext);

        assertSame(TransactionRecord.DEFAULT, subject.toRecord(translator));
    }

    @Test
    void translatesOutputsToRecordAsExpected() {
        given(translator.translateRecord(
                        translationContext,
                        TRANSACTION_RESULT.transactionResultOrThrow(),
                        null,
                        FIRST_OUTPUT.transactionOutputOrThrow(),
                        SECOND_OUTPUT.transactionOutputOrThrow()))
                .willReturn(TransactionRecord.DEFAULT);

        final var subject = new BlockStreamBuilder.Output(ITEMS_WITH_OUTPUTS, translationContext);

        assertSame(TransactionRecord.DEFAULT, subject.toRecord(translator));
    }

    @Test
    void translatesNoOutputsToReceiptAsExpected() {
        given(translationContext.txnId()).willReturn(TXN_ID);
        given(translator.translateReceipt(translationContext, TRANSACTION_RESULT.transactionResultOrThrow()))
                .willReturn(TransactionReceipt.DEFAULT);

        final var subject = new BlockStreamBuilder.Output(ITEMS_NO_OUTPUTS, translationContext);

        assertEquals(
                new RecordSource.IdentifiedReceipt(TXN_ID, TransactionReceipt.DEFAULT),
                subject.toIdentifiedReceipt(translator));
    }

    @Test
    void translatesOutputsToReceiptAsExpected() {
        given(translationContext.txnId()).willReturn(TXN_ID);
        given(translator.translateReceipt(
                        translationContext,
                        TRANSACTION_RESULT.transactionResultOrThrow(),
                        FIRST_OUTPUT.transactionOutputOrThrow(),
                        SECOND_OUTPUT.transactionOutputOrThrow()))
                .willReturn(TransactionReceipt.DEFAULT);

        final var subject = new BlockStreamBuilder.Output(ITEMS_WITH_OUTPUTS, translationContext);

        assertEquals(
                new RecordSource.IdentifiedReceipt(TXN_ID, TransactionReceipt.DEFAULT),
                subject.toIdentifiedReceipt(translator));
    }
}
