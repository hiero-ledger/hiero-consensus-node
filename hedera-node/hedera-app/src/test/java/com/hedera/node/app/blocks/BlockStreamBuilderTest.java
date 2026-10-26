// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_EVM_HOOK_STORAGE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_STORAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.UTIL_PRNG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockItemsTranslator.BLOCK_ITEMS_TRANSLATOR;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_CONTRACT_NUM;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.ExecutedInitcode;
import com.hedera.hapi.block.stream.trace.SlotRead;
import com.hedera.hapi.block.stream.trace.WrittenSlotKeys;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.CreatedContractIds;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookSlotKey;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockStreamBuilderTest {
    public static final Instant CONSENSUS_TIME = Instant.now();
    public static final Instant PARENT_CONSENSUS_TIME = CONSENSUS_TIME.plusNanos(1L);
    public static final long TRANSACTION_FEE = 6846513L;
    public static final int ENTROPY_NUMBER = 87372879;
    public static final String MEMO = "Yo Memo";
    private final SignedTransaction signedTx = SignedTransaction.newBuilder()
            .bodyBytes(TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
                    .cryptoTransfer(CryptoTransferTransactionBody.newBuilder().build())
                    .memo("ABCDEFG")
                    .transactionFee(123)
                    .build()))
            .build();
    private final Bytes signedTxBytes = SignedTransaction.PROTOBUF.toBytes(signedTx);
    private @Mock TransactionID transactionID;
    private @Mock TransferList transferList;
    private @Mock TokenTransferList tokenTransfer;
    private @Mock ScheduleID scheduleRef;
    private @Mock AssessedCustomFee assessedCustomFee;
    private @Mock TokenAssociation tokenAssociation;
    private @Mock Bytes prngBytes;
    private @Mock AccountAmount accountAmount;
    private @Mock ResponseCodeEnum status;
    private @Mock ExchangeRateSet exchangeRate;
    private @Mock AccountID accountID;

    @Test
    void testBlockItemsWithCryptoTransferOutput() {
        final var itemsBuilder = createBaseBuilder()
                .assessedCustomFees(List.of(assessedCustomFee))
                .functionality(HederaFunctionality.CRYPTO_TRANSFER);

        List<BlockItem> blockItems = itemsBuilder.build(false, null).blockItems();
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
            List<BlockItem> blockItems = itemsBuilder.build(false, null).blockItems();
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
            List<BlockItem> blockItems = itemsBuilder.build(false, null).blockItems();
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
        final var usages = List.of(new ContractSlotUsage(
                ContractID.DEFAULT, new WrittenSlotKeys(List.of(Bytes.EMPTY)), List.of(SlotRead.DEFAULT)));
        final var evmTxResult = EvmTransactionResult.DEFAULT;
        final var itemsBuilder = createBaseBuilder()
                .functionality(CONTRACT_CALL)
                .evmCallTransactionResult(evmTxResult)
                .addContractSlotUsages(usages);

        List<BlockItem> blockItems = itemsBuilder.build(false, null).blockItems();
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
    void successfulExplicitSlotWritesRemainIndexed() {
        final var usage = explicitSlotUsage();
        final var builder = createBaseBuilder()
                .functionality(CONTRACT_CALL)
                .status(SUCCESS)
                .evmCallTransactionResult(EvmTransactionResult.DEFAULT)
                .addContractSlotUsages(List.of(usage));

        final var trace = builder.build(false, null).blockItems().stream()
                .filter(BlockItem::hasTraceData)
                .findFirst()
                .orElseThrow()
                .traceDataOrThrow()
                .evmTraceDataOrThrow();

        assertThat(trace.contractSlotUsages()).containsExactly(usage);
        assertThat(builder.hasExplicitContractSlotWrites()).isTrue();
    }

    @ParameterizedTest(name = "hookStorage={0}, stateChanges={1}")
    @MethodSource("implicitSlotReadScenarios")
    void successfulImplicitReadsUseMinimalKeysAfterWriteLookup(
            final boolean hookStorage, final ImplicitWriteContext writeContext) throws Exception {
        final var contractId = ContractID.newBuilder()
                .contractNum(hookStorage ? HTS_HOOKS_CONTRACT_NUM : 1037)
                .build();
        final var zeroWord = Bytes.fromHex("00".repeat(32));
        final var paddedOne = Bytes.fromHex("00".repeat(31) + "01");
        final var paddedTwo = Bytes.fromHex("00".repeat(31) + "02");
        final var highBitWord = Bytes.fromHex("80" + "00".repeat(31));
        final var reads = List.of(
                SlotRead.newBuilder()
                        .key(zeroWord)
                        .readValue(Bytes.fromHex("11"))
                        .build(),
                SlotRead.newBuilder()
                        .key(paddedOne)
                        .readValue(Bytes.fromHex("22"))
                        .build(),
                SlotRead.newBuilder()
                        .key(highBitWord)
                        .readValue(Bytes.fromHex("33"))
                        .build(),
                SlotRead.newBuilder()
                        .key(paddedTwo)
                        .readValue(Bytes.fromHex("44"))
                        .build(),
                SlotRead.newBuilder()
                        .key(Bytes.EMPTY)
                        .readValue(Bytes.fromHex("55"))
                        .build(),
                SlotRead.newBuilder()
                        .key(Bytes.fromHex("03"))
                        .readValue(Bytes.EMPTY)
                        .build());
        final var builder = createBaseBuilder()
                .functionality(CONTRACT_CALL)
                .status(SUCCESS)
                .evmCallTransactionResult(EvmTransactionResult.DEFAULT)
                .addContractSlotUsages(List.of(new ContractSlotUsage(contractId, null, reads)));
        builder.testForIdenticalKeys(_ -> false);
        if (writeContext != ImplicitWriteContext.NO_CHANGES) {
            final var key = hookStorage
                    ? MapChangeKey.newBuilder()
                            .evmHookSlotKey(new EvmHookSlotKey(HookId.DEFAULT, Bytes.fromHex("02")))
                            .build()
                    : MapChangeKey.newBuilder()
                            .slotKeyKey(new SlotKey(contractId, paddedTwo))
                            .build();
            builder.stateChanges(List.of(StateChange.newBuilder()
                    .stateId((hookStorage ? STATE_ID_EVM_HOOK_STORAGE : STATE_ID_STORAGE).protoOrdinal())
                    .mapUpdate(MapUpdateChange.newBuilder()
                            .key(key)
                            .value(MapChangeValue.newBuilder().slotValueValue(SlotValue.DEFAULT))
                            .identical(writeContext == ImplicitWriteContext.IDENTICAL_UPDATE_ONLY))
                    .build()));
        }

        final var trace = builder.build(false, null).blockItems().stream()
                .filter(BlockItem::hasTraceData)
                .findFirst()
                .orElseThrow()
                .traceDataOrThrow()
                .evmTraceDataOrThrow();
        // The empty bytes used for slot zero must retain the KEY oneof even after serialization.
        final var usage = EvmTraceData.PROTOBUF
                .parseStrict(EvmTraceData.PROTOBUF.toBytes(trace).toReadableSequentialData())
                .contractSlotUsages()
                .getFirst();
        final var matchingRead = writeContext == ImplicitWriteContext.NON_IDENTICAL_WRITE
                ? reads.get(3).copyBuilder().index(0).build()
                : reads.get(3).copyBuilder().key(Bytes.fromHex("02")).build();
        assertThat(usage.contractId()).isEqualTo(contractId);
        assertThat(usage.hasWrittenSlotKeys()).isFalse();
        assertThat(usage.slotReads())
                .containsExactly(
                        reads.get(0).copyBuilder().key(Bytes.EMPTY).build(),
                        reads.get(1).copyBuilder().key(Bytes.fromHex("01")).build(),
                        reads.get(2),
                        matchingRead,
                        reads.get(4),
                        reads.get(5));
        assertThat(usage.slotReads().getFirst().hasKey()).isTrue();
        assertThat(usage.slotReads().getFirst().hasIndex()).isFalse();
    }

    private static Stream<Arguments> implicitSlotReadScenarios() {
        return Stream.of(false, true).flatMap(hookStorage -> Stream.of(ImplicitWriteContext.values())
                .map(writeContext -> Arguments.of(hookStorage, writeContext)));
    }

    private enum ImplicitWriteContext {
        NON_IDENTICAL_WRITE,
        IDENTICAL_UPDATE_ONLY,
        NO_CHANGES
    }

    @Test
    void revertedExplicitSlotWritesBecomeMinimalReadKeys() {
        final var builder = createBaseBuilder()
                .functionality(CONTRACT_CALL)
                .status(REVERTED_SUCCESS)
                .evmCallTransactionResult(EvmTransactionResult.DEFAULT)
                .addContractSlotUsages(List.of(explicitSlotUsage()));

        final var trace = builder.build(false, null).blockItems().stream()
                .filter(BlockItem::hasTraceData)
                .findFirst()
                .orElseThrow()
                .traceDataOrThrow()
                .evmTraceDataOrThrow();
        final var usage = trace.contractSlotUsages().getFirst();

        assertThat(usage.hasWrittenSlotKeys()).isFalse();
        assertThat(usage.slotReads())
                .containsExactly(
                        SlotRead.newBuilder()
                                .key(Bytes.EMPTY)
                                .readValue(Bytes.fromHex("0a"))
                                .build(),
                        SlotRead.newBuilder()
                                .key(Bytes.fromHex("01"))
                                .readValue(Bytes.fromHex("0b"))
                                .build(),
                        SlotRead.newBuilder()
                                .key(Bytes.fromHex("02"))
                                .readValue(Bytes.fromHex("0c"))
                                .build());
        assertThat(builder.hasExplicitContractSlotWrites()).isFalse();
    }

    @Test
    void detectsExplicitWritesWithoutMistakingSyntheticCreationOrReadOnlyTraceForWrites() {
        final var builder = createBaseBuilder().functionality(CONTRACT_CREATE);
        builder.evmCreateTransactionResult(EvmTransactionResult.DEFAULT);
        assertThat(builder.hasContractResult()).isTrue();
        assertThat(builder.hasExplicitContractSlotWrites()).isFalse();

        builder.addContractSlotUsages(List.of(new ContractSlotUsage(
                ContractID.DEFAULT,
                WrittenSlotKeys.DEFAULT,
                List.of(SlotRead.newBuilder().key(Bytes.EMPTY).build()))));
        assertThat(builder.hasExplicitContractSlotWrites()).isFalse();

        builder.addContractSlotUsages(List.of(explicitSlotUsage()));
        assertThat(builder.hasExplicitContractSlotWrites()).isTrue();

        builder.addContractSlotUsages(List.of(new ContractSlotUsage(
                ContractID.DEFAULT,
                null,
                List.of(SlotRead.newBuilder()
                        .key(Bytes.fromHex("00".repeat(32)))
                        .build()))));
        builder.testForIdenticalKeys(_ -> false);
        assertThat(builder.hasExplicitContractSlotWrites()).isFalse();
    }

    private static ContractSlotUsage explicitSlotUsage() {
        return new ContractSlotUsage(
                ContractID.DEFAULT,
                new WrittenSlotKeys(List.of(Bytes.fromHex("00".repeat(32)), Bytes.fromHex("00".repeat(31) + "01"))),
                List.of(
                        SlotRead.newBuilder()
                                .index(0)
                                .readValue(Bytes.fromHex("0a"))
                                .build(),
                        SlotRead.newBuilder()
                                .index(1)
                                .readValue(Bytes.fromHex("0b"))
                                .build(),
                        SlotRead.newBuilder()
                                .key(Bytes.fromHex("02"))
                                .readValue(Bytes.fromHex("0c"))
                                .build()));
    }

    @Test
    void rollbackClearsCapturedCreationMetadataEvenBeforeStatusChanges() {
        final var result = EvmTransactionResult.newBuilder()
                .createdContractIds(new CreatedContractIds(
                        List.of(ContractID.newBuilder().contractNum(1039).build())))
                .build();
        final var builder = evmOutputBuilder(false, false, result)
                .tokenTransferLists(new java.util.ArrayList<>())
                .paidStakingRewards(new java.util.ArrayList<>());
        builder.retainEvmScopeDetails();
        builder.nullOutSideEffectFields();

        assertThat(emittedEvmResult(builder).hasCreatedContractIds()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = ResponseCodeEnum.class,
            names = {"REVERTED_SUCCESS", "CONTRACT_REVERT_EXECUTED", "INSUFFICIENT_GAS"})
    void failedExecutionCannotEmitStaleCreatedContractIds(final ResponseCodeEnum failure) {
        final var result = EvmTransactionResult.newBuilder()
                .createdContractIds(new CreatedContractIds(
                        List.of(ContractID.newBuilder().contractNum(1039).build())))
                .build();
        final var builder = evmOutputBuilder(false, false, result).status(failure);
        builder.retainEvmScopeDetails();

        assertThat(emittedEvmResult(builder).hasCreatedContractIds()).isFalse();
    }

    @ParameterizedTest(name = "ethereum={0}, create={1}, empty={2}")
    @MethodSource("scopedCreatedIdCases")
    void retainsScopedCreatedIdsOnlyForOverlappingExecutions(
            final boolean ethereum, final boolean create, final boolean empty) throws Exception {
        final var ids = empty
                ? List.<ContractID>of()
                : List.of(ContractID.newBuilder().contractNum(1039).build());
        final var result = EvmTransactionResult.newBuilder()
                .gasUsed(123)
                .resultData(Bytes.fromHex("0123"))
                .createdContractIds(new CreatedContractIds(ids))
                .build();
        final var ordinary = evmOutputBuilder(ethereum, create, result);
        assertThat(emittedEvmResult(ordinary))
                .isEqualTo(result.copyBuilder()
                        .createdContractIds((CreatedContractIds) null)
                        .build());

        final var overlapping = evmOutputBuilder(ethereum, create, result);
        overlapping.retainEvmScopeDetails();
        final var emitted = emittedEvmResult(overlapping);
        assertThat(emitted.createdContractIdsOrThrow().contractIds()).isEqualTo(ids);
        final var roundTrip = EvmTransactionResult.PROTOBUF.parse(EvmTransactionResult.PROTOBUF.toBytes(emitted));
        assertThat(roundTrip).isEqualTo(emitted);
        assertThat(roundTrip.hasCreatedContractIds()).isTrue();
    }

    private static Stream<Arguments> scopedCreatedIdCases() {
        return Stream.of(false, true).flatMap(ethereum -> Stream.of(false, true)
                .flatMap(create -> Stream.of(false, true).map(empty -> Arguments.of(ethereum, create, empty))));
    }

    private BlockStreamBuilder evmOutputBuilder(
            final boolean ethereum, final boolean create, final EvmTransactionResult result) {
        final var builder = createBaseBuilder()
                .status(SUCCESS)
                .functionality(
                        ethereum ? HederaFunctionality.ETHEREUM_TRANSACTION : create ? CONTRACT_CREATE : CONTRACT_CALL);
        if (ethereum) {
            builder.ethereumHash(Bytes.fromHex("01"));
        }
        if (create) {
            builder.evmCreateTransactionResult(result);
        } else {
            builder.evmCallTransactionResult(result);
        }
        return builder;
    }

    private static EvmTransactionResult emittedEvmResult(final BlockStreamBuilder builder) {
        final var output = builder.build(false, null).blockItems().stream()
                .filter(BlockItem::hasTransactionOutput)
                .findFirst()
                .orElseThrow()
                .transactionOutputOrThrow();
        if (output.hasContractCall()) {
            return output.contractCallOrThrow().evmTransactionResultOrThrow();
        } else if (output.hasContractCreate()) {
            return output.contractCreateOrThrow().evmTransactionResultOrThrow();
        }
        final var ethereum = output.ethereumCallOrThrow();
        return ethereum.hasEvmCallTransactionResult()
                ? ethereum.evmCallTransactionResultOrThrow()
                : ethereum.evmCreateTransactionResultOrThrow();
    }

    @Test
    void preservesCapturedWritesWithoutCommittedKeysAndKeepsReadOnlySlotsReadOnly() throws Exception {
        final var builder = createBaseBuilder().status(SUCCESS).functionality(CONTRACT_CALL);
        builder.testForIdenticalKeys(_ -> true);
        builder.retainEvmScopeDetails();
        builder.addContractSlotUsages(List.of(new ContractSlotUsage(
                ContractID.DEFAULT,
                null,
                List.of(
                        SlotRead.newBuilder()
                                .key(Bytes.fromHex("00".repeat(32)))
                                .readValue(Bytes.fromHex("07"))
                                .writtenValue(Bytes.EMPTY)
                                .build(),
                        SlotRead.newBuilder()
                                .key(Bytes.fromHex("00".repeat(31) + "01"))
                                .writtenValue(Bytes.fromHex("02"))
                                .build(),
                        SlotRead.newBuilder().key(Bytes.fromHex("02")).build()))));

        final var usage = storageTrace(builder).contractSlotUsages().getFirst();
        assertThat(usage.writtenSlotKeysOrThrow().keys()).containsExactly(Bytes.EMPTY, Bytes.fromHex("01"));
        assertThat(usage.slotReads().getFirst().indexOrThrow()).isZero();
        assertThat(usage.slotReads().getFirst().writtenValueOrThrow()).isEqualTo(Bytes.EMPTY);
        assertThat(usage.slotReads().get(1).writtenValueOrThrow()).isEqualTo(Bytes.fromHex("02"));
        assertThat(usage.slotReads().getLast().hasKey()).isTrue();
        assertThat(usage.slotReads().getLast().hasWrittenValue()).isFalse();
        final var roundTrip = ContractSlotUsage.PROTOBUF.parse(ContractSlotUsage.PROTOBUF.toBytes(usage));
        assertThat(roundTrip).isEqualTo(usage);
        assertThat(roundTrip.slotReads().getFirst().hasWrittenValue()).isTrue();
    }

    @Test
    void candidateWrittenValuesDoNotChangeOrdinaryWireOutputOrPrematurelyExhaustTraceLimit() {
        final var compactUsage = new ContractSlotUsage(
                ContractID.DEFAULT,
                new WrittenSlotKeys(List.of(Bytes.EMPTY)),
                List.of(SlotRead.newBuilder()
                        .index(0)
                        .readValue(Bytes.fromHex("01"))
                        .build()));
        final var compactSize = EvmTraceData.PROTOBUF.measureRecord(EvmTraceData.newBuilder()
                .contractSlotUsages(List.of(compactUsage))
                .build());
        final var candidateUsage = compactUsage
                .copyBuilder()
                .slotReads(compactUsage
                        .slotReads()
                        .getFirst()
                        .copyBuilder()
                        .writtenValue(Bytes.fromHex("ff".repeat(32)))
                        .build())
                .build();
        final var ordinary = createBaseBuilder(compactSize).status(SUCCESS).functionality(CONTRACT_CALL);
        ordinary.addContractSlotUsages(List.of(candidateUsage));
        assertThat(ordinary.hasTraceDataSizeLimitExceeded()).isFalse();
        assertThat(storageTrace(ordinary).contractSlotUsages()).containsExactly(compactUsage);
        assertThat(ordinary.hasTraceDataSizeLimitExceeded()).isFalse();

        final var nested = createBaseBuilder(compactSize).status(SUCCESS).functionality(CONTRACT_CALL);
        nested.retainEvmScopeDetails();
        nested.addContractSlotUsages(List.of(candidateUsage));
        assertThat(nested.hasTraceDataSizeLimitExceeded()).isTrue();
        assertThat(nested.build(false, null).blockItems()).noneMatch(BlockItem::hasTraceData);
    }

    @Test
    void revertedCapturedWritesLoseTheirValuesAndIndexes() {
        final var builder = createBaseBuilder().status(REVERTED_SUCCESS).functionality(CONTRACT_CALL);
        builder.retainEvmScopeDetails();
        builder.addContractSlotUsages(List.of(new ContractSlotUsage(
                ContractID.DEFAULT,
                new WrittenSlotKeys(List.of(Bytes.EMPTY)),
                List.of(SlotRead.newBuilder()
                        .index(0)
                        .writtenValue(Bytes.fromHex("02"))
                        .build()))));

        final var usage = storageTrace(builder).contractSlotUsages().getFirst();
        assertThat(usage.hasWrittenSlotKeys()).isFalse();
        assertThat(usage.slotReads())
                .containsExactly(SlotRead.newBuilder().key(Bytes.EMPTY).build());
    }

    private static EvmTraceData storageTrace(final BlockStreamBuilder builder) {
        return builder.build(false, null).blockItems().stream()
                .filter(BlockItem::hasTraceData)
                .findFirst()
                .orElseThrow()
                .traceDataOrThrow()
                .evmTraceDataOrThrow();
    }

    @Test
    void exceededContractTraceDataLimitClearsAllEvmTraceData() {
        final var actions = List.of(
                ContractAction.newBuilder().callType(ContractActionType.CALL).build());
        final var usages = List.of(new ContractSlotUsage(
                ContractID.DEFAULT, new WrittenSlotKeys(List.of(Bytes.EMPTY)), List.of(SlotRead.DEFAULT)));
        final var maxTraceDataBytes = EvmTraceData.PROTOBUF.measureRecord(
                        EvmTraceData.newBuilder().contractSlotUsages(usages).build())
                + EvmTraceData.PROTOBUF.measureRecord(
                        EvmTraceData.newBuilder().contractActions(actions).build())
                - 1;
        final var logs =
                List.of(new EvmTransactionLog(ContractID.DEFAULT, Bytes.wrap("log"), List.of(Bytes.wrap("topic"))));
        final var initcode = ExecutedInitcode.newBuilder()
                .contractId(ContractID.DEFAULT)
                .explicitInitcode(Bytes.wrap("too-large"))
                .build();
        final var itemsBuilder = createBaseBuilder(maxTraceDataBytes)
                .functionality(CONTRACT_CALL)
                .evmCallTransactionResult(EvmTransactionResult.DEFAULT);
        itemsBuilder.addContractSlotUsages(usages);
        itemsBuilder.addLogs(logs);
        itemsBuilder.addActions(actions);
        itemsBuilder.addInitcode(initcode);
        itemsBuilder.addActions(actions);

        final var traceItems = itemsBuilder.build(false, null).blockItems().stream()
                .filter(BlockItem::hasTraceData)
                .toList();
        assertTrue(itemsBuilder.hasTraceDataSizeLimitExceeded());
        assertTrue(traceItems.isEmpty());
    }

    @Test
    void testBlockItemsWithAdditionalSubmitMsgTraceData() {
        final var itemsBuilder = createEmptyBuilder().functionality(CONSENSUS_SUBMIT_MESSAGE);
        // set additional trace data
        itemsBuilder.topicSequenceNumber(66);
        final var blockItems = itemsBuilder.build(false, List.of()).blockItems();

        final var traceItem = blockItems.get(2);
        assertThat(traceItem.hasTraceData()).isTrue();
        final var trace = traceItem.traceDataOrThrow();

        assertThat(trace.hasSubmitMessageTraceData()).isTrue();
        final var submitMessageTraceData = trace.submitMessageTraceData();
        assertThat(submitMessageTraceData).isNotNull();
        assertThat(submitMessageTraceData.sequenceNumber()).isEqualTo(66);
    }

    @Test
    void testBlockItemsWithCreateAccountOutput() {
        final var itemsBuilder =
                createBaseBuilder().functionality(CRYPTO_CREATE).accountID(accountID);

        List<BlockItem> blockItems = itemsBuilder.build(false, null).blockItems();
        validateTransactionBlockItems(blockItems);
        validateTransactionResult(blockItems);

        final var outputBlockItem = blockItems.get(2);
        assertTrue(outputBlockItem.hasTransactionOutput());
        final var output = outputBlockItem.transactionOutput();
        assertTrue(output.hasAccountCreate());
    }

    @Test
    void testTransactionBodyReturnsDeserializedBody() {
        final var builder = createBaseBuilder().functionality(HederaFunctionality.CRYPTO_TRANSFER);

        final var body = builder.transactionBody();

        assertEquals("ABCDEFG", body.memo());
        assertEquals(123, body.transactionFee());
        assertTrue(body.hasCryptoTransfer());
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
        assertEquals(4L, result.highVolumePricingMultiplier());
    }

    private void validateTransactionBlockItems(final List<BlockItem> blockItems) {
        final var txnBlockItem = blockItems.getFirst();
        assertTrue(txnBlockItem.hasSignedTransaction());
        assertEquals(SignedTransaction.PROTOBUF.toBytes(signedTx), txnBlockItem.signedTransactionOrThrow());
    }

    @Test
    void nullOutSideEffectFieldsClearsEvmLogsAndCreatedContractIds() {
        final var contractId = ContractID.newBuilder().contractNum(1L).build();
        final var log = EvmTransactionLog.DEFAULT;
        final BlockStreamBuilder builder = createEmptyBuilder()
                .functionality(CONTRACT_CALL)
                .evmCallTransactionResult(EvmTransactionResult.DEFAULT)
                .status(ResponseCodeEnum.SUCCESS)
                .consensusTimestamp(CONSENSUS_TIME)
                .transactionID(TransactionID.DEFAULT);
        builder.addLogs(List.of(log));
        builder.createdContractIds(List.of(contractId));

        builder.nullOutSideEffectFields();

        final var record = builder.build(false, null).toRecord(BLOCK_ITEMS_TRANSLATOR);

        assertThat(record.contractCallResult().logInfo()).isEmpty();
        assertThat(record.contractCallResult().bloom()).isEqualTo(Bytes.EMPTY);
        assertThat(record.contractCallResult().createdContractIDs()).isEmpty();
    }

    private BlockStreamBuilder createBaseBuilder() {
        return createBaseBuilder(Integer.MAX_VALUE);
    }

    private BlockStreamBuilder createBaseBuilder(final int maxSerializedTraceDataBytes) {
        final List<TokenTransferList> tokenTransferLists = List.of(tokenTransfer);
        final List<AccountAmount> paidStakingRewards = List.of(accountAmount);
        return new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes)
                .status(status)
                .consensusTimestamp(CONSENSUS_TIME)
                .parentConsensus(PARENT_CONSENSUS_TIME)
                .exchangeRate(exchangeRate)
                .scheduleRef(scheduleRef)
                .transactionFee(TRANSACTION_FEE)
                .signedTx(signedTx)
                .transactionID(transactionID)
                .memo(MEMO)
                .transactionFee(TRANSACTION_FEE)
                .transferList(transferList)
                .tokenTransferLists(tokenTransferLists)
                .addAutomaticTokenAssociation(tokenAssociation)
                .paidStakingRewards(paidStakingRewards)
                .congestionMultiplier(10L)
                .highVolumePricingMultiplier(4L);
    }

    private BlockStreamBuilder createEmptyBuilder() {
        return new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER)
                .signedTx(signedTx)
                .serializedSignedTx(signedTxBytes);
    }

    @Test
    void tracksAndEnumeratesDeletedAccountBeneficiaries() {
        final var deletedA = AccountID.newBuilder().accountNum(1001).build();
        final var beneficiaryA = AccountID.newBuilder().accountNum(1002).build();
        final var deletedB = AccountID.newBuilder().accountNum(1003).build();
        final var beneficiaryB = AccountID.newBuilder().accountNum(1004).build();

        final var builder = new BlockStreamBuilder(REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER);
        builder.addBeneficiaryForDeletedAccount(deletedA, beneficiaryA);
        builder.addBeneficiaryForDeletedAccount(deletedB, beneficiaryB);

        assertThat(builder.getNumberOfDeletedAccounts()).isEqualTo(2);
        assertThat(builder.getDeletedAccountBeneficiaryFor(deletedB)).isEqualTo(beneficiaryB);

        final Map<AccountID, AccountID> visited = new HashMap<>();
        builder.forEachDeletedAccountBeneficiary(visited::put);
        assertThat(visited).hasSize(2).containsEntry(deletedA, beneficiaryA).containsEntry(deletedB, beneficiaryB);
    }
}
