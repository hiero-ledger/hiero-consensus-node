// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BYTECODE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_STORAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.services.bdd.junit.support.translators.impl.ContractCallTranslator.isClprDispatch;
import static com.hedera.services.bdd.junit.support.translators.impl.ContractCallTranslator.reached;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.SlotRead;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.CreatedContractIds;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.junit.support.translators.inputs.HookMetadata;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContractCallTranslatorTest {
    private static final ContractID CONTRACT =
            ContractID.newBuilder().shardNum(11).realmNum(12).contractNum(1037).build();
    private static final ContractID CREATED_CONTRACT =
            CONTRACT.copyBuilder().contractNum(1041).build();
    private static final TransactionID TRANSACTION_ID = TransactionID.newBuilder()
            .accountID(AccountID.newBuilder().accountNum(1031))
            .transactionValidStart(Timestamp.newBuilder().seconds(1_234))
            .build();
    private static final Timestamp PARENT_TIME =
            Timestamp.newBuilder().seconds(2_345).build();
    private static final Bytes ONE = Bytes.fromHex("01");
    private static final Bytes TWO = Bytes.fromHex("02");
    private static final Bytes PADDED_ONE = Bytes.fromHex("00".repeat(31) + "01");
    private static final Bytes PADDED_TWO = Bytes.fromHex("00".repeat(31) + "02");
    private static final EvmTransactionLog LOG = EvmTransactionLog.newBuilder()
            .contractId(CONTRACT)
            .data(Bytes.fromHex("aabb"))
            .topics(ONE)
            .build();

    @ParameterizedTest
    @EnumSource(
            value = HederaFunctionality.class,
            names = {"SCHEDULE_CREATE", "SCHEDULE_SIGN"})
    void reconstructsScheduledCallLogsBloomCreationsAndStorage(final HederaFunctionality triggeringFunction) {
        final var unit = scheduledUnit(triggeringFunction, SUCCESS, List.of(LOG), false);
        final var parts = unit.blockTransactionParts().get(1);
        assertFalse(parts.isTopLevel());
        assertFalse(parts.isInnerBatchTxn());
        assertTrue(parts.hasEnrichedLegacyRecord());

        final var translated = translate(unit, 1);
        final var result = translated.transactionRecord().contractCallResultOrThrow();
        assertEquals(1, result.logInfo().size());
        final var log = result.logInfo().getFirst();
        assertEquals(CONTRACT, log.contractID());
        assertEquals(LOG.data(), log.data());
        assertEquals(List.of(PADDED_ONE), log.topic());
        assertEquals(log.bloom(), result.bloom());
        assertEquals(256, result.bloom().length());
        assertNotEquals(bloomForAll(List.of()), result.bloom());
        assertEquals(List.of(CREATED_CONTRACT), result.createdContractIDs());
        assertEquals(CONTRACT, translated.transactionRecord().receiptOrThrow().contractID());
        assertNull(translated.transactionRecord().parentConsensusTimestamp());

        // The written value is recovered from the scheduling parent's state changes, using the trace's index.
        final var storageChanges = translated
                .transactionSidecarRecords()
                .getFirst()
                .stateChangesOrThrow()
                .contractStateChanges()
                .getFirst()
                .storageChanges();
        assertEquals(2, storageChanges.size());
        assertEquals(ONE, storageChanges.getFirst().slot());
        assertEquals(ONE, storageChanges.getFirst().valueRead());
        assertEquals(TWO, storageChanges.getFirst().valueWritten());
        assertEquals(Bytes.EMPTY, storageChanges.get(1).slot());
        assertFalse(storageChanges.get(1).hasValueWritten());
    }

    @Test
    void reconstructsImpliedEmptyBloomWhenScheduledCallHasStorageTraceButNoLogs() {
        final var unit = scheduledUnit(SCHEDULE_CREATE, SUCCESS, List.of(), false);

        final var result = translate(unit, 1).transactionRecord().contractCallResultOrThrow();

        assertTrue(result.logInfo().isEmpty());
        assertEquals(bloomForAll(List.of()), result.bloom());
        assertEquals(256, result.bloom().length());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void reconstructsReceiptContractIdForNonzeroNonceScheduledCall(final int nonce) {
        final var items = new ArrayList<BlockItem>();
        items.add(signedItem(TransactionBody.newBuilder()
                .transactionID(TRANSACTION_ID)
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(CONTRACT)
                        .gas(100_000))
                .build()));
        items.add(BlockItem.newBuilder()
                .transactionResult(
                        TransactionResult.newBuilder().status(SUCCESS).consensusTimestamp(PARENT_TIME))
                .build());
        items.add(BlockItem.newBuilder().stateChanges(StateChanges.DEFAULT).build());
        addScheduledCall(
                items,
                nonce,
                SUCCESS,
                List.of(),
                ScheduleID.newBuilder()
                        .shardNum(11)
                        .realmNum(12)
                        .scheduleNum(1038)
                        .build());
        final var units = new RoleFreeBlockUnitSplit().split(items);
        assertEquals(1, units.size());
        final var unit = units.getFirst();
        assertTrue(unit.blockTransactionParts().get(1).hasEnrichedLegacyRecord());

        final var translated = translate(unit, 1);

        assertEquals(CONTRACT, translated.transactionRecord().receiptOrThrow().contractID());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedHookCreationsOverrideInferenceWithoutSkippingItsQueueEntry(final boolean empty) {
        final var hookContract = CONTRACT.copyBuilder().contractNum(365).build();
        final var hookId = HookId.DEFAULT;
        final var capturedIds = empty ? List.<ContractID>of() : List.of(CREATED_CONTRACT);
        final var nextCreations =
                List.of(CONTRACT.copyBuilder().contractNum(1042).build());
        final var inferredCreations = new ArrayList<>(List.of(List.of(CONTRACT), nextCreations));
        final var hookMetadata = new HookMetadata(List.of(hookId), Map.of(hookId, inferredCreations));
        final var body = TransactionBody.newBuilder()
                .transactionID(TRANSACTION_ID.copyBuilder().nonce(1))
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(hookContract)
                        .gas(100_000))
                .build();
        final var parts = new BlockTransactionParts(
                TransactionParts.from(signedItem(body).signedTransactionOrThrow()),
                TransactionResult.newBuilder()
                        .status(SUCCESS)
                        .consensusTimestamp(PARENT_TIME.copyBuilder().nanos(1))
                        .parentConsensusTimestamp(PARENT_TIME)
                        .build(),
                null,
                List.of(TransactionOutput.newBuilder()
                        .contractCall(CallContractOutput.newBuilder()
                                .evmTransactionResult(EvmTransactionResult.newBuilder()
                                        .contractId(hookContract)
                                        .gasUsed(1_234)
                                        .createdContractIds(new CreatedContractIds(capturedIds))))
                        .build()),
                false,
                false,
                false);

        final var translated = new ContractCallTranslator()
                .translate(parts, new BaseTranslator(11, 12), List.of(), null, List.of(), hookId, hookMetadata);

        assertEquals(
                capturedIds,
                translated.transactionRecord().contractCallResultOrThrow().createdContractIDs());
        assertEquals(
                List.of(nextCreations), inferredCreations, "Later hook executions must retain their own queue entry");
    }

    @Test
    void doesNotEnrichTheScheduledCallsSyntheticChild() {
        final var unit = scheduledUnit(SCHEDULE_CREATE, SUCCESS, List.of(LOG), true);
        assertFalse(unit.blockTransactionParts().get(2).hasEnrichedLegacyRecord());

        final var record = translate(unit, 2).transactionRecord();
        final var result = record.contractCallResultOrThrow();

        assertNull(
                record.receiptOrThrow().contractID(), "A scheduled flag alone does not identify a scheduled execution");
        assertEquals(Bytes.EMPTY, result.bloom());
        assertTrue(result.logInfo().isEmpty());
        assertTrue(result.createdContractIDs().isEmpty());
    }

    @Test
    void doesNotEnrichAnUnsuccessfulScheduledCall() {
        final var unit = scheduledUnit(SCHEDULE_CREATE, CONTRACT_REVERT_EXECUTED, List.of(), false);

        final var result = translate(unit, 1).transactionRecord().contractCallResultOrThrow();

        assertEquals(Bytes.EMPTY, result.bloom());
        assertTrue(result.logInfo().isEmpty());
        assertTrue(result.createdContractIDs().isEmpty());
    }

    private static SingleTransactionRecord translate(final BlockTransactionalUnit unit, final int index) {
        return new ContractCallTranslator()
                .translate(
                        unit.blockTransactionParts().get(index),
                        new BaseTranslator(11, 12),
                        unit.stateChanges(),
                        null,
                        List.of(),
                        null,
                        null);
    }

    private static BlockTransactionalUnit scheduledUnit(
            final HederaFunctionality triggeringFunction,
            final ResponseCodeEnum scheduledStatus,
            final List<EvmTransactionLog> logs,
            final boolean includeChild) {
        final var parentBody = TransactionBody.newBuilder().transactionID(TRANSACTION_ID);
        if (triggeringFunction == SCHEDULE_CREATE) {
            parentBody.scheduleCreate(ScheduleCreateTransactionBody.DEFAULT);
        } else {
            parentBody.scheduleSign(ScheduleSignTransactionBody.DEFAULT);
        }
        final var items = new ArrayList<BlockItem>();
        items.add(signedItem(parentBody.build()));
        items.add(BlockItem.newBuilder()
                .transactionResult(
                        TransactionResult.newBuilder().status(SUCCESS).consensusTimestamp(PARENT_TIME))
                .build());
        items.add(BlockItem.newBuilder()
                .stateChanges(
                        StateChanges.newBuilder().stateChanges(scheduledStatus == SUCCESS ? stateChanges() : List.of()))
                .build());
        addScheduledCall(items, 0, scheduledStatus, logs);
        if (includeChild) {
            addScheduledCall(items, 1, SUCCESS, List.of());
        }
        final var units = new RoleFreeBlockUnitSplit().split(items);
        assertEquals(1, units.size());
        return units.getFirst();
    }

    private static void addScheduledCall(
            final List<BlockItem> items,
            final int nonce,
            final ResponseCodeEnum status,
            final List<EvmTransactionLog> logs) {
        addScheduledCall(items, nonce, status, logs, null);
    }

    private static void addScheduledCall(
            final List<BlockItem> items,
            final int nonce,
            final ResponseCodeEnum status,
            final List<EvmTransactionLog> logs,
            final ScheduleID scheduleRef) {
        items.add(signedItem(TransactionBody.newBuilder()
                .transactionID(TRANSACTION_ID.copyBuilder().scheduled(true).nonce(nonce))
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .contractID(CONTRACT)
                        .gas(100_000))
                .build()));
        items.add(BlockItem.newBuilder()
                .transactionResult(TransactionResult.newBuilder()
                        .status(status)
                        .consensusTimestamp(PARENT_TIME.copyBuilder().nanos(nonce + 1))
                        .scheduleRef(scheduleRef)
                        .parentConsensusTimestamp(PARENT_TIME))
                .build());
        items.add(BlockItem.newBuilder()
                .transactionOutput(TransactionOutput.newBuilder()
                        .contractCall(CallContractOutput.newBuilder()
                                .evmTransactionResult(EvmTransactionResult.newBuilder()
                                        .contractId(CONTRACT)
                                        .gasUsed(1_234))))
                .build());
        if (nonce == 0) {
            items.add(BlockItem.newBuilder()
                    .traceData(TraceData.newBuilder()
                            .evmTraceData(EvmTraceData.newBuilder()
                                    .logs(logs)
                                    .contractSlotUsages(ContractSlotUsage.newBuilder()
                                            .contractId(CONTRACT)
                                            .slotReads(
                                                    (status == SUCCESS
                                                                    ? SlotRead.newBuilder()
                                                                            .index(0)
                                                                    : SlotRead.newBuilder()
                                                                            .key(ONE))
                                                            .readValue(ONE)
                                                            .build(),
                                                    SlotRead.newBuilder()
                                                            .key(Bytes.EMPTY)
                                                            .readValue(Bytes.fromHex("040b"))
                                                            .build())
                                            .build())))
                    .build());
        }
    }

    private static BlockItem signedItem(final TransactionBody body) {
        return BlockItem.newBuilder()
                .signedTransaction(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                        .build()))
                .build();
    }

    private static List<StateChange> stateChanges() {
        return List.of(
                StateChange.newBuilder()
                        .stateId(STATE_ID_STORAGE.protoOrdinal())
                        .mapUpdate(MapUpdateChange.newBuilder()
                                .key(MapChangeKey.newBuilder().slotKeyKey(new SlotKey(CONTRACT, PADDED_ONE)))
                                .value(MapChangeValue.newBuilder()
                                        .slotValueValue(SlotValue.newBuilder().value(PADDED_TWO))))
                        .build(),
                StateChange.newBuilder()
                        .stateId(STATE_ID_BYTECODE.protoOrdinal())
                        .mapUpdate(MapUpdateChange.newBuilder()
                                .key(MapChangeKey.newBuilder().contractIdKey(CREATED_CONTRACT))
                                .value(MapChangeValue.newBuilder().bytecodeValue(Bytecode.DEFAULT)))
                        .build());
    }

    private static final ContractID APPLICATION =
            ContractID.newBuilder().contractNum(1001L).build();
    private static final ContractID HTS =
            ContractID.newBuilder().contractNum(0x167L).build();
    private static final Bytes APPLICATION_ALIAS = Bytes.fromHex("c0ffee00000000000000000000000000000000c1");
    private static final Map<Bytes, AccountID> ALIASES =
            Map.of(APPLICATION_ALIAS, AccountID.newBuilder().accountNum(1001L).build());

    @Test
    @DisplayName("A call a CLPR transaction dispatched carries no sender, so it is a CLPR dispatch")
    void dispatchedCallIsClprDispatch() {
        final var dispatched =
                EvmTransactionResult.newBuilder().contractId(APPLICATION).build();

        assertTrue(isClprDispatch(true, dispatched));
    }

    @Test
    @DisplayName("An HTS view called by a CLPR callback carries its caller as sender, so it is not a CLPR dispatch")
    void systemContractChildOfCallbackIsNotClprDispatch() {
        // E.g. the application callback calling HTS isToken(): a synthetic child CONTRACT_CALL whose result names
        // the calling contract as sender; its receipt must not get the dispatched call's contract id
        final var htsView = EvmTransactionResult.newBuilder()
                .contractId(HTS)
                .senderId(AccountID.newBuilder().accountNum(1001L).build())
                .build();

        assertFalse(isClprDispatch(true, htsView));
    }

    @Test
    @DisplayName("Outside a unit with a CLPR transaction, no child call is a CLPR dispatch")
    void noClprTransactionNoDispatch() {
        final var call =
                EvmTransactionResult.newBuilder().contractId(APPLICATION).build();

        assertFalse(isClprDispatch(false, call));
    }

    @Test
    @DisplayName("A callback addressed to the numeric id of its application reached it")
    void numericTargetReached() {
        assertTrue(reached(APPLICATION, APPLICATION, ALIASES));
        assertFalse(reached(APPLICATION, HTS, ALIASES));
    }

    @Test
    @DisplayName("A callback addressed to the long-zero address of its application reached it")
    void longZeroTargetReached() {
        final var target = ContractID.newBuilder()
                .evmAddress(Bytes.wrap(asEvmAddress(1001L)))
                .build();

        assertTrue(reached(target, APPLICATION, ALIASES));
        assertFalse(reached(target, HTS, ALIASES));
    }

    @Test
    @DisplayName("A callback addressed to the EVM alias of its application reached it")
    void aliasedTargetReached() {
        // CLPR callbacks address the application by the EVM address in the message, e.g. a CREATE2 address,
        // while the result carries the resolved numeric id
        final var target = ContractID.newBuilder().evmAddress(APPLICATION_ALIAS).build();

        assertTrue(reached(target, APPLICATION, ALIASES));
        assertFalse(reached(target, HTS, ALIASES));
    }

    @Test
    @DisplayName("A callback addressed to an unknown alias did not reach the contract its result reports")
    void unknownAliasNotReached() {
        final var target = ContractID.newBuilder().evmAddress(APPLICATION_ALIAS).build();

        assertFalse(reached(target, APPLICATION, Map.of()));
    }
}
