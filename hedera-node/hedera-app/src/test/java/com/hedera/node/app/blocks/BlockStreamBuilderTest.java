// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.SlotRead;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockStreamBuilderTest {
    public static final Instant CONSENSUS_TIME = Instant.now();
    public static final Instant PARENT_CONSENSUS_TIME = CONSENSUS_TIME.plusNanos(1L);
    public static final long TRANSACTION_FEE = 6846513L;
    public static final int ENTROPY_NUMBER = 87372879;
    public static final String MEMO = "Yo Memo";
    private Transaction transaction = Transaction.newBuilder()
            .body(TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().build())
                    .build())
            .build();
    private @Mock TransactionID transactionID;
    private final Bytes transactionBytes = Bytes.wrap("Hello Tester");
    private @Mock TransferList transferList;
    private @Mock TokenTransferList tokenTransfer;
    private @Mock ScheduleID scheduleRef;
    private @Mock AssessedCustomFee assessedCustomFee;
    private @Mock TokenAssociation tokenAssociation;
    private @Mock Bytes prngBytes;
    private @Mock AccountAmount accountAmount;
    private @Mock ResponseCodeEnum status;
    private @Mock ExchangeRateSet exchangeRate;
    private @Mock ContractStateChanges contractStateChanges;
    private @Mock AccountID accountID;

    @Test
    void testBlockItemsWithCryptoTransferOutput() {
        final var itemsBuilder = createBaseBuilder()
                .assessedCustomFees(List.of(assessedCustomFee))
                .functionality(HederaFunctionality.CRYPTO_TRANSFER);

        List<BlockItem> blockItems = itemsBuilder.build(false).blockItems();
        validateTransactionBlockItems(blockItems);
        validateTransactionResult(blockItems);

        final var result = blockItems.get(1).transactionResult();
        assertEquals(List.of(assessedCustomFee), result.assessedCustomFees());
    }

    @ParameterizedTest
    @EnumSource(TransactionRecord.EntropyOneOfType.class)
    void testBlockItemsWithUtilPrngOutput(TransactionRecord.EntropyOneOfType entropyOneOfType) {
        if (entropyOneOfType == TransactionRecord.EntropyOneOfType.UNSET) {
            return;
        }
        if (entropyOneOfType == TransactionRecord.EntropyOneOfType.PRNG_BYTES) {
            final var itemsBuilder =
                    createBaseBuilder().functionality(UTIL_PRNG).entropyBytes(prngBytes);
            List<BlockItem> blockItems = itemsBuilder.build(false).blockItems();
            validateTransactionBlockItems(blockItems);
            validateTransactionResult(blockItems);

            final var outputBlockItem = blockItems.get(2);
            assertTrue(outputBlockItem.hasTransactionOutput());
            final var output = outputBlockItem.transactionOutput();
            assertTrue(output.hasUtilPrng());
            assertEquals(prngBytes, output.utilPrng().prngBytes());
        } else {
            final var itemsBuilder =
                    createBaseBuilder().functionality(UTIL_PRNG).entropyNumber(ENTROPY_NUMBER);
            List<BlockItem> blockItems = itemsBuilder.build(false).blockItems();
            validateTransactionBlockItems(blockItems);
            validateTransactionResult(blockItems);

            final var outputBlockItem = blockItems.get(2);
            assertTrue(outputBlockItem.hasTransactionOutput());
            final var output = outputBlockItem.transactionOutput();
            assertTrue(output.hasUtilPrng());
            assertEquals(ENTROPY_NUMBER, output.utilPrng().prngNumber());
        }
    }

    @Test
    void testBlockItemsWithTraceAndOutput() {
        final var usages =
                List.of(new ContractSlotUsage(ContractID.DEFAULT, List.of(Bytes.EMPTY), List.of(SlotRead.DEFAULT)));
        final var evmTxResult = EvmTransactionResult.DEFAULT;
        final var itemsBuilder = createBaseBuilder()
                .functionality(CONTRACT_CALL)
                .evmCallTransactionResult(evmTxResult)
                .addContractSlotUsages(usages);

        List<BlockItem> blockItems = itemsBuilder.build(false).blockItems();
        validateTransactionBlockItems(blockItems);
        validateTransactionResult(blockItems);

        final var outputBlockItem = blockItems.get(2);
        assertTrue(outputBlockItem.hasTransactionOutput());
        final var output = outputBlockItem.transactionOutputOrThrow();
        assertTrue(output.hasContractCall());

        final var traceItem = blockItems.get(3);
        assertTrue(traceItem.hasTraceData());
        final var trace = traceItem.traceDataOrThrow();
        assertTrue(trace.hasEvmTraceData());
        final var evmTrace = trace.evmTraceDataOrThrow();
        assertEquals(usages, evmTrace.contractSlotUsages());
    }

    @Test
    void testBlockItemsWithCreateAccountOutput() {
        final var itemsBuilder =
                createBaseBuilder().functionality(CRYPTO_CREATE).accountID(accountID);

        List<BlockItem> blockItems = itemsBuilder.build(false).blockItems();
        validateTransactionBlockItems(blockItems);
        validateTransactionResult(blockItems);

        final var outputBlockItem = blockItems.get(2);
        assertTrue(outputBlockItem.hasTransactionOutput());
        final var output = outputBlockItem.transactionOutput();
        assertTrue(output.hasAccountCreate());
    }

    private void validateTransactionResult(final List<BlockItem> blockItems) {
        final var resultBlockItem = blockItems.get(1);
        assertTrue(resultBlockItem.hasTransactionResult());
        final var result = resultBlockItem.transactionResult();

        assertEquals(status, result.status());
        assertEquals(asTimestamp(CONSENSUS_TIME), result.consensusTimestamp());
        assertEquals(asTimestamp(PARENT_CONSENSUS_TIME), result.parentConsensusTimestamp());
        assertEquals(scheduleRef, result.scheduleRef());
        assertEquals(TRANSACTION_FEE, result.transactionFeeCharged());
        assertEquals(transferList, result.transferList());
        assertEquals(List.of(tokenTransfer), result.tokenTransferLists());
        assertEquals(List.of(tokenAssociation), result.automaticTokenAssociations());
        assertEquals(List.of(accountAmount), result.paidStakingRewards());
        assertEquals(10L, result.congestionPricingMultiplier());
    }

    private void validateTransactionBlockItems(final List<BlockItem> blockItems) {
        final var txnBlockItem = blockItems.get(0);
        assertTrue(txnBlockItem.hasEventTransaction());
        assertEquals(
                Transaction.PROTOBUF.toBytes(transaction),
                txnBlockItem.eventTransaction().applicationTransactionOrThrow());
    }

    private BlockStreamBuilder createBaseBuilder() {
        final List<TokenTransferList> tokenTransferLists = List.of(tokenTransfer);
        final List<AccountAmount> paidStakingRewards = List.of(accountAmount);
        return new BlockStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER)
                .status(status)
                .consensusTimestamp(CONSENSUS_TIME)
                .parentConsensus(PARENT_CONSENSUS_TIME)
                .exchangeRate(exchangeRate)
                .scheduleRef(scheduleRef)
                .transactionFee(TRANSACTION_FEE)
                .transaction(transaction)
                .transactionBytes(transactionBytes)
                .transactionID(transactionID)
                .memo(MEMO)
                .transactionFee(TRANSACTION_FEE)
                .transferList(transferList)
                .tokenTransferLists(tokenTransferLists)
                .addAutomaticTokenAssociation(tokenAssociation)
                .paidStakingRewards(paidStakingRewards)
                .congestionMultiplier(10L);
    }
}
