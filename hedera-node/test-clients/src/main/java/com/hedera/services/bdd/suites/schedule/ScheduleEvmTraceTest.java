// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_STORAGE;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.protoToPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassWithReplayFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ScheduleEvmTraceTest {
    private static final String CONTRACT = "Traceability";
    private static final String SECOND_CONTRACT = CONTRACT + "B";
    private static final String THIRD_CONTRACT = CONTRACT + "C";
    private static final String SCHEDULE_PAYER = "schedulePayer";
    private static final String SCHEDULE = "scheduledCall";
    private static final String CREATE_TXN = "createSchedule";
    private static final String SIGN_TXN = "signSchedule";

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> immediatelyCreatedScheduleTracesStorageWrites() {
        return scheduledCallTracesStorageWrites(false);
    }

    @LeakyHapiTest(overrides = {"scheduling.whitelist", "contracts.sidecars"})
    final Stream<DynamicTest> immediatelySignedScheduleTracesStorageWrites() {
        return scheduledCallTracesStorageWrites(true);
    }

    private Stream<DynamicTest> scheduledCallTracesStorageWrites(final boolean executeOnSign) {
        final var triggeringTxn = executeOnSign ? SIGN_TXN : CREATE_TXN;
        return hapiTest(
                overriding("scheduling.whitelist", "ContractCall"),
                overriding("contracts.sidecars", "CONTRACT_STATE_CHANGE,CONTRACT_BYTECODE"),
                cryptoCreate(SCHEDULE_PAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT, BigInteger.valueOf(55), BigInteger.TWO, BigInteger.TWO)
                        .gas(2_500_000),
                contractCustomCreate(CONTRACT, "B", BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(12))
                        .gas(2_500_000),
                contractCustomCreate(CONTRACT, "C", BigInteger.valueOf(99), BigInteger.valueOf(88), BigInteger.ZERO)
                        .gas(2_500_000),
                withOpContext((spec, log) -> {
                    // Read slot 0 and update slot 1 in A, update slot 2 in B, and delete slots 0 and 1 in C.
                    final var creation = scheduleCreate(
                                    SCHEDULE,
                                    contractCall(
                                                    CONTRACT,
                                                    "eetScenario1",
                                                    asHeadlongAddress(asAddress(
                                                            spec.registry().getContractId(SECOND_CONTRACT))),
                                                    asHeadlongAddress(asAddress(
                                                            spec.registry().getContractId(THIRD_CONTRACT))))
                                            .gas(1_000_000))
                            .designatingPayer(SCHEDULE_PAYER)
                            .via(CREATE_TXN)
                            .signedBy(
                                    executeOnSign
                                            ? new String[] {DEFAULT_PAYER}
                                            : new String[] {DEFAULT_PAYER, SCHEDULE_PAYER});
                    allRunFor(spec, creation);
                    if (executeOnSign) {
                        allRunFor(
                                spec,
                                getScheduleInfo(SCHEDULE).isNotExecuted(),
                                scheduleSign(SCHEDULE)
                                        .alsoSigningWith(SCHEDULE_PAYER)
                                        .via(SIGN_TXN));
                    }
                }),
                getScheduleInfo(SCHEDULE).isExecuted(),
                getTxnRecord(CREATE_TXN).scheduled().hasPriority(recordWith().status(SUCCESS)),
                streamMustIncludePassWithReplayFrom(
                        spec -> scheduledStorageTrace(spec, triggeringTxn), Duration.ofSeconds(30)));
    }

    private static BlockStreamAssertion scheduledStorageTrace(final HapiSpec spec, final String triggeringTxn) {
        final var triggerId = protoToPbj(spec.registry().getTxnId(triggeringTxn), TransactionID.class);
        final var scheduledId = protoToPbj(
                spec.registry().getTxnId(CREATE_TXN).toBuilder()
                        .setScheduled(true)
                        .build(),
                TransactionID.class);
        final var expected = Map.of(
                contractId(spec, CONTRACT), List.of(read(0, 55), write(1, 2, 55)),
                contractId(spec, SECOND_CONTRACT), List.of(write(2, 12, 143)),
                contractId(spec, THIRD_CONTRACT), List.of(write(0, 99, 0), write(1, 88, 0)));
        final var splitter = new RoleFreeBlockUnitSplit();
        final var translator = new BlockTransactionalUnitTranslator(spec.shard(), spec.realm());
        return block -> {
            translator.scanBlockForGenesis(block);
            for (final var rawUnit : splitter.split(block)) {
                final var unit = rawUnit.withBatchTransactionParts();
                final var scheduled = unit.blockTransactionParts().stream()
                        .filter(parts -> parts.transactionIdOrThrow().equals(scheduledId))
                        .findFirst();
                if (scheduled.isEmpty()) {
                    continue;
                }
                final var parent = unit.blockTransactionParts().stream()
                        .filter(parts -> parts.transactionIdOrThrow().equals(triggerId))
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        parent.consensusTimestamp(),
                        scheduled.get().transactionResult().parentConsensusTimestampOrThrow());
                final var traces = scheduled.get().tracesOrThrow().stream()
                        .filter(TraceData::hasEvmTraceData)
                        .map(TraceData::evmTraceDataOrThrow)
                        .toList();
                assertEquals(1, traces.size());
                final var usages = traces.getFirst().contractSlotUsages();
                assertEquals(expected.size(), usages.size());
                final var writtenKeys = unit.stateChanges().stream()
                        .filter(change -> change.stateId() == STATE_ID_STORAGE.protoOrdinal())
                        .filter(change -> change.hasMapDelete()
                                || (change.hasMapUpdate()
                                        && !change.mapUpdateOrThrow().identical()))
                        .map(ScheduleEvmTraceTest::writtenSlotKey)
                        .toList();
                assertEquals(4, writtenKeys.size(), "Two updates and two deletions must be committed");
                for (final var usage : usages) {
                    assertFalse(usage.hasWrittenSlotKeys(), "A single EVM execution uses implicit written keys");
                    final var writes = writtenKeys.stream()
                            .filter(key -> key.contractIDOrThrow().equals(usage.contractIdOrThrow()))
                            .map(key -> minimalRepresentationOf(key.key()))
                            .toList();
                    final var expectedReads = expected.get(usage.contractIdOrThrow());
                    assertEquals(expectedReads.size(), usage.slotReads().size());
                    for (int i = 0; i < expectedReads.size(); i++) {
                        final var expectedRead = expectedReads.get(i);
                        final var actualRead = usage.slotReads().get(i);
                        assertEquals(expectedRead.valueRead(), actualRead.readValue());
                        assertFalse(
                                actualRead.hasWrittenValue(), "A single EVM execution keeps compact value encoding");
                        if (expectedRead.hasValueWritten()) {
                            assertTrue(actualRead.hasIndex(), "Written slots must reference the unit's committed keys");
                            assertEquals(expectedRead.slot(), writes.get(actualRead.indexOrThrow()));
                        } else {
                            assertTrue(actualRead.hasKey(), "Read-only slots must retain their explicit key");
                            assertEquals(expectedRead.slot(), actualRead.keyOrThrow());
                        }
                    }
                }
                // Verify the same block also reconstructs the complete scheduled transaction's storage sidecar.
                final var records = translator.translate(unit);
                final var record = records.stream()
                        .filter(r ->
                                r.transactionRecord().transactionIDOrThrow().equals(scheduledId))
                        .findFirst()
                        .orElseThrow();
                final var sidecars = record.transactionSidecarRecords().stream()
                        .filter(TransactionSidecarRecord::hasStateChanges)
                        .toList();
                assertEquals(1, sidecars.size());
                final var changes = sidecars.getFirst().stateChangesOrThrow().contractStateChanges();
                assertEquals(expected.size(), changes.size());
                for (final ContractStateChange change : changes) {
                    assertEquals(expected.get(change.contractIdOrThrow()), change.storageChanges());
                }
                return true;
            }
            return false;
        };
    }

    private static ContractID contractId(final HapiSpec spec, final String name) {
        return protoToPbj(spec.registry().getContractId(name), ContractID.class);
    }

    private static SlotKey writtenSlotKey(final StateChange change) {
        return change.hasMapDelete()
                ? change.mapDeleteOrThrow().keyOrThrow().slotKeyKeyOrThrow()
                : change.mapUpdateOrThrow().keyOrThrow().slotKeyKeyOrThrow();
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
