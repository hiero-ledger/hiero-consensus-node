// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassWithReplayFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/** Verifies storage traces for EVM executions that overlap through an immediate HSS schedule dispatch. */
@Tag(SMART_CONTRACT)
public class ScheduledEvmInterleavingTest {
    private static final String CONTRACT = "ScheduledEvmInterleaving";
    private static final String PAYER = "contractControlledPayer";
    private static final String OUTER_TXN = "outerCall";
    private static final int READ_ONLY_VALUE = 0;

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> outerWriteAfterScheduledWritePreservesBothValues() {
        return overlappingWrites(10, 11, List.of(22), 33);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> outerScopeIncludesScheduledWriteWithoutResumingWrites() {
        return overlappingWrites(10, 11, List.of(22), null);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> multipleScheduledWritesPreserveEachIntermediateValue() {
        return overlappingWrites(10, 11, List.of(22, 44), 33);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> scheduledDeletionSurvivesOuterReinsertion() {
        return overlappingWrites(10, 11, List.of(0), 33);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> scheduledDeletionSurvivesNetZeroOuterScope() {
        return overlappingWrites(0, 11, List.of(0), 0);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> nestedCreatedContractIdsStayWithTheirExecutionScope() {
        return overlappingWrites(10, 11, List.of(22), 33, true);
    }

    private Stream<DynamicTest> overlappingWrites(
            final int initialValue,
            final int beforeValue,
            final List<Integer> scheduledValues,
            final Integer afterValue) {
        return overlappingWrites(initialValue, beforeValue, scheduledValues, afterValue, false);
    }

    private Stream<DynamicTest> overlappingWrites(
            final int initialValue,
            final int beforeValue,
            final List<Integer> scheduledValues,
            final Integer afterValue,
            final boolean createContracts) {
        final Map<TransactionID, List<ContractID>> createdIdsByExecution = new LinkedHashMap<>();
        return hapiTest(
                overriding("scheduling.whitelist", "ContractCall"),
                overriding("contracts.sidecars", "CONTRACT_STATE_CHANGE,CONTRACT_BYTECODE"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT, BigInteger.valueOf(initialValue)).gas(2_000_000),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).keyShape(KeyShape.CONTRACT.signedWith(CONTRACT)),
                withOpContext((spec, log) -> {
                    final var schedules = new Address[scheduledValues.size()];
                    for (int i = 0; i < scheduledValues.size(); i++) {
                        allRunFor(
                                spec,
                                scheduleCreate(
                                                scheduleName(i),
                                                contractCall(
                                                                CONTRACT,
                                                                createContracts ? "setWithCreation" : "set",
                                                                BigInteger.valueOf(scheduledValues.get(i)))
                                                        .gas(1_000_000))
                                        .designatingPayer(PAYER)
                                        .signedBy(DEFAULT_PAYER)
                                        .via(creationName(i)),
                                getScheduleInfo(scheduleName(i)).isNotExecuted());
                        schedules[i] = mirrorAddrWith(
                                spec,
                                spec.registry().getScheduleId(scheduleName(i)).getScheduleNum());
                    }
                    allRunFor(
                            spec,
                            contractCall(
                                            CONTRACT,
                                            createContracts ? "runWithCreations" : "run",
                                            schedules,
                                            BigInteger.valueOf(beforeValue),
                                            scheduledValues.stream()
                                                    .map(BigInteger::valueOf)
                                                    .toArray(BigInteger[]::new),
                                            afterValue != null,
                                            BigInteger.valueOf(afterValue == null ? 0 : afterValue))
                                    .gas(3_000_000)
                                    .via(OUTER_TXN)
                                    .hasKnownStatus(SUCCESS));
                    allRunFor(
                            spec,
                            getTxnRecord(OUTER_TXN)
                                    .exposingTo(record -> captureCreatedIds(record, createdIdsByExecution)));
                    for (int i = 0; i < scheduledValues.size(); i++) {
                        allRunFor(
                                spec,
                                getScheduleInfo(scheduleName(i)).isExecuted(),
                                getTxnRecord(creationName(i))
                                        .scheduled()
                                        .hasPriority(recordWith().status(SUCCESS))
                                        .exposingTo(record -> captureCreatedIds(record, createdIdsByExecution)));
                    }
                    if (createContracts) {
                        final var outerId = protoToPbj(spec.registry().getTxnId(OUTER_TXN), TransactionID.class);
                        final var outerCreated = createdIdsByExecution.get(outerId);
                        final var nestedCreated = createdIdsByExecution.entrySet().stream()
                                .filter(entry -> !entry.getKey().equals(outerId))
                                .findFirst()
                                .orElseThrow()
                                .getValue();
                        assertEquals(3, outerCreated.size(), "X's scope includes A, nested B, and resumed C");
                        assertEquals(1, nestedCreated.size(), "Y's scope includes only B");
                        final var byNumber = outerCreated.stream()
                                .sorted(java.util.Comparator.comparingLong(ContractID::contractNumOrThrow))
                                .toList();
                        assertEquals(nestedCreated.getFirst(), byNumber.get(1), "Y creates B between X's A and C");
                    }
                }),
                streamMustIncludePassWithReplayFrom(
                        spec -> overlappingStorageTraces(
                                spec, initialValue, beforeValue, scheduledValues, afterValue, createdIdsByExecution),
                        Duration.ofSeconds(30)));
    }

    private static void captureCreatedIds(
            final com.hederahashgraph.api.proto.java.TransactionRecord record,
            final Map<TransactionID, List<ContractID>> createdIdsByExecution) {
        createdIdsByExecution.put(
                protoToPbj(record.getTransactionID(), TransactionID.class),
                record.getContractCallResult().getCreatedContractIDsList().stream()
                        .map(id -> protoToPbj(id, ContractID.class))
                        .toList());
    }

    private static BlockStreamAssertion overlappingStorageTraces(
            final HapiSpec spec,
            final int initialValue,
            final int beforeValue,
            final List<Integer> scheduledValues,
            final Integer afterValue,
            final Map<TransactionID, List<ContractID>> createdIdsByExecution) {
        final var outerId = protoToPbj(spec.registry().getTxnId(OUTER_TXN), TransactionID.class);
        final var contractId = protoToPbj(spec.registry().getContractId(CONTRACT), ContractID.class);
        final Map<TransactionID, List<StorageChange>> expected = new LinkedHashMap<>();
        // Legacy storage sidecars describe the complete execution scope. The outer scope includes its nested calls.
        final int finalValue = afterValue == null ? scheduledValues.getLast() : afterValue;
        expected.put(outerId, List.of(write(0, initialValue, finalValue), read(1, READ_ONLY_VALUE)));
        int priorValue = beforeValue;
        for (int i = 0; i < scheduledValues.size(); i++) {
            final var scheduledId = protoToPbj(
                    spec.registry().getTxnId(creationName(i)).toBuilder()
                            .setScheduled(true)
                            .build(),
                    TransactionID.class);
            expected.put(scheduledId, List.of(write(0, priorValue, scheduledValues.get(i)), read(1, READ_ONLY_VALUE)));
            priorValue = scheduledValues.get(i);
        }
        final var splitter = new RoleFreeBlockUnitSplit();
        final var translator = new BlockTransactionalUnitTranslator(spec.shard(), spec.realm());
        return block -> {
            translator.scanBlockForGenesis(block);
            for (final var rawUnit : splitter.split(block)) {
                final var unit = rawUnit.withBatchTransactionParts();
                if (unit.blockTransactionParts().stream()
                        .noneMatch(parts -> parts.transactionIdOrThrow().equals(outerId))) {
                    continue;
                }
                final var outerParts = unit.blockTransactionParts().stream()
                        .filter(parts -> parts.transactionIdOrThrow().equals(outerId))
                        .findFirst()
                        .orElseThrow();
                for (final var entry : expected.entrySet()) {
                    final var parts = unit.blockTransactionParts().stream()
                            .filter(candidate ->
                                    candidate.transactionIdOrThrow().equals(entry.getKey()))
                            .findFirst()
                            .orElseThrow();
                    assertStorageUsage(parts, contractId, entry.getValue());
                    final var rawResult = parts.callContractOutputOrThrow().evmTransactionResultOrThrow();
                    assertTrue(
                            rawResult.hasCreatedContractIds(), "Overlapping scopes must identify their own creations");
                    assertEquals(
                            createdIdsByExecution.get(entry.getKey()),
                            rawResult.createdContractIdsOrThrow().contractIds());
                    assertTrue(parts.hasEnrichedLegacyRecord(), "Each scheduled EVM execution has an enriched result");
                    if (!entry.getKey().equals(outerId)) {
                        assertEquals(
                                outerParts.consensusTimestamp(),
                                parts.transactionResult().parentConsensusTimestampOrThrow());
                    }
                }
                final var translated = translator.translate(unit);
                for (final var entry : expected.entrySet()) {
                    final var record = translated.stream()
                            .filter(candidate -> candidate
                                    .transactionRecord()
                                    .transactionIDOrThrow()
                                    .equals(entry.getKey()))
                            .findFirst()
                            .orElseThrow();
                    final var result = record.transactionRecord().contractCallResultOrThrow();
                    assertEquals(
                            createdIdsByExecution.get(entry.getKey()),
                            result.createdContractIDs(),
                            "Translated creations must match the producer's captured execution scope");
                    assertEquals(256, result.bloom().length(), "Even executions without logs retain an empty bloom");
                    assertEquals(bloomForAll(List.of()), result.bloom());
                    final var sidecars = record.transactionSidecarRecords().stream()
                            .filter(TransactionSidecarRecord::hasStateChanges)
                            .toList();
                    assertEquals(1, sidecars.size(), "Each EVM execution must retain its storage sidecar");
                    assertEquals(
                            List.of(new ContractStateChange(contractId, entry.getValue())),
                            sidecars.getFirst().stateChangesOrThrow().contractStateChanges(),
                            "Storage writes must belong to their execution scope, including intermediate zero values");
                }
                return true;
            }
            return false;
        };
    }

    private static void assertStorageUsage(
            final BlockTransactionParts parts, final ContractID contractId, final List<StorageChange> expected) {
        final var traces = parts.tracesOrThrow().stream()
                .filter(TraceData::hasEvmTraceData)
                .map(TraceData::evmTraceDataOrThrow)
                .toList();
        assertEquals(1, traces.size());
        final var usages = traces.getFirst().contractSlotUsages();
        assertEquals(1, usages.size());
        final ContractSlotUsage usage = usages.getFirst();
        assertEquals(contractId, usage.contractIdOrThrow());
        assertEquals(expected.size(), usage.slotReads().size());
        assertTrue(usage.hasWrittenSlotKeys(), "Overlapping EVM scopes need explicit written keys");
        assertEquals(List.of(Bytes.EMPTY), usage.writtenSlotKeysOrThrow().keys());
        for (int i = 0; i < expected.size(); i++) {
            final var actual = usage.slotReads().get(i);
            final var change = expected.get(i);
            assertEquals(change.valueRead(), actual.readValue());
            if (change.hasValueWritten()) {
                assertTrue(actual.hasIndex(), "A written slot must reference its explicit key");
                assertEquals(0, actual.indexOrThrow());
                assertTrue(actual.hasWrittenValue(), "Overlapping scopes must carry their own final written values");
                assertEquals(change.valueWritten(), actual.writtenValue());
                assertEquals(minimalRepresentationOf(actual.writtenValue()), actual.writtenValue());
            } else {
                assertTrue(actual.hasKey(), "A genuine read-only slot must retain its key");
                assertFalse(actual.hasWrittenValue(), "A read-only access must not acquire a written value");
                assertEquals(change.slot(), actual.keyOrThrow());
                assertEquals(minimalRepresentationOf(actual.keyOrThrow()), actual.keyOrThrow());
            }
        }
    }

    private static String scheduleName(final int index) {
        return "nestedSchedule" + index;
    }

    private static String creationName(final int index) {
        return "createNestedSchedule" + index;
    }

    private static StorageChange read(final int slot, final int value) {
        return StorageChange.newBuilder()
                .slot(minimal(slot))
                .valueRead(minimal(value))
                .build();
    }

    private static StorageChange write(final int slot, final int before, final int after) {
        return read(slot, before).copyBuilder().valueWritten(minimal(after)).build();
    }

    private static Bytes minimal(final int value) {
        return value == 0 ? Bytes.EMPTY : Bytes.wrap(new byte[] {(byte) value});
    }
}
