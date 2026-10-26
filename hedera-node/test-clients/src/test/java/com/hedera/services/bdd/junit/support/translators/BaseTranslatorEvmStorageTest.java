// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_STORAGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.ContractSlotUsage;
import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.SlotRead;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.block.stream.trace.WrittenSlotKeys;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BaseTranslatorEvmStorageTest {
    private static final ContractID CONTRACT =
            ContractID.newBuilder().contractNum(1037).build();
    private static final Bytes KEY = Bytes.fromHex("01");
    private static final Bytes ONE = Bytes.fromHex("01");
    private static final Bytes TWO = Bytes.fromHex("02");
    private static final Bytes THREE = Bytes.fromHex("03");
    private static final Bytes PADDED_KEY = Bytes.fromHex("00".repeat(31) + "01");

    @Test
    void reconstructsOverlappingScopesWhenTheOuterExecutionResumes() {
        // X writes 1, nested Y writes 2, then X resumes and writes 3. X's scope
        // includes the nested execution, while Y's own scope finishes with 2.
        final var outer = explicitUsage(Bytes.EMPTY, THREE);
        final var nested = explicitUsage(ONE, TWO);
        final var changes = List.of(stateChange(THREE));

        final var outerStorage = translate(outer, changes, List.of(scoped(nested)));
        final var nestedStorage = translate(nested, changes, List.of());

        assertEquals(Bytes.EMPTY, outerStorage.valueRead());
        assertEquals(THREE, outerStorage.valueWritten());
        assertEquals(ONE, nestedStorage.valueRead());
        assertEquals(TWO, nestedStorage.valueWritten());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitZeroWriteOverridesBothInferenceSources(final boolean hasFollowingTrace) {
        final var usage = explicitUsage(ONE, Bytes.EMPTY);
        final var following =
                hasFollowingTrace ? List.of(scoped(explicitUsage(TWO, THREE))) : List.<ScopedTraceData>of();

        final var storage = translate(usage, List.of(stateChange(THREE)), following);

        assertTrue(storage.hasValueWritten());
        assertEquals(Bytes.EMPTY, storage.valueWritten());
    }

    @Test
    void anExplicitWriteDoesNotRequireAFinalStateChange() {
        final var storage = translate(explicitUsage(ONE, TWO), List.of(), List.of());

        assertEquals(KEY, storage.slot());
        assertEquals(TWO, storage.valueWritten());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serialWritesWithoutExplicitValuesStillUseFollowingReadsAndFinalState(final boolean explicitKeys) {
        final var first = serialUsage(Bytes.EMPTY, explicitKeys);
        final var second = serialUsage(ONE, explicitKeys);
        final var changes = List.of(stateChange(TWO));

        assertEquals(ONE, translate(first, changes, List.of(scoped(second))).valueWritten());
        assertEquals(TWO, translate(second, changes, List.of()).valueWritten());
    }

    @Test
    void aFollowingReadOnlyTraceDoesNotClaimTheUnitsImplicitWrite() {
        final var readOnly = ContractSlotUsage.newBuilder()
                .contractId(CONTRACT)
                .slotReads(SlotRead.newBuilder().key(KEY).readValue(ONE).build())
                .build();
        final var changes = List.of(stateChange(TWO));

        assertEquals(
                TWO,
                translate(serialUsage(Bytes.EMPTY, false), changes, List.of(scoped(readOnly)))
                        .valueWritten());
        assertFalse(translate(readOnly, changes, List.of()).hasValueWritten());
    }

    @Test
    void skipsReadOnlyTracesWhenFindingTheNextActualWriter() {
        final var readOnly = ContractSlotUsage.newBuilder()
                .contractId(CONTRACT)
                .slotReads(SlotRead.newBuilder().key(KEY).readValue(ONE).build())
                .build();

        final var storage = translate(
                serialUsage(Bytes.EMPTY, false),
                List.of(stateChange(THREE)),
                List.of(scoped(readOnly), scoped(serialUsage(TWO, false))));

        assertEquals(TWO, storage.valueWritten());
    }

    @Test
    void writtenValuePresenceSurvivesPbjAndProtocRoundTrips() throws Exception {
        for (final var read : List.of(
                SlotRead.newBuilder().index(0).readValue(ONE).build(),
                SlotRead.newBuilder()
                        .index(0)
                        .readValue(ONE)
                        .writtenValue(Bytes.EMPTY)
                        .build(),
                SlotRead.newBuilder()
                        .index(0)
                        .readValue(ONE)
                        .writtenValue(THREE)
                        .build())) {
            final var serialized = SlotRead.PROTOBUF.toBytes(read);
            final var protoc = com.hedera.hapi.block.stream.trace.protoc.SlotRead.parseFrom(serialized.toByteArray());

            assertEquals(read.hasWrittenValue(), protoc.hasWrittenValue());
            assertEquals(read, SlotRead.PROTOBUF.parse(serialized));
            assertEquals(read, SlotRead.PROTOBUF.parse(Bytes.wrap(protoc.toByteArray())));
            if (read.hasWrittenValue()) {
                assertEquals(
                        read.writtenValue(),
                        Bytes.wrap(protoc.getWrittenValue().getValue().toByteArray()));
            }
        }
    }

    private static StorageChange translate(
            final ContractSlotUsage usage,
            final List<StateChange> stateChanges,
            final List<ScopedTraceData> followingTraces) {
        final var txnId = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001))
                .transactionValidStart(Timestamp.newBuilder().seconds(1))
                .build();
        final var body = TransactionBody.newBuilder()
                .transactionID(txnId)
                .contractCall(ContractCallTransactionBody.newBuilder().contractID(CONTRACT))
                .build();
        final var parts = new BlockTransactionParts(
                TransactionParts.from(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                        .build()),
                TransactionResult.newBuilder()
                        .status(SUCCESS)
                        .consensusTimestamp(Timestamp.newBuilder().seconds(2))
                        .build(),
                List.of(trace(usage)),
                List.of(),
                true,
                true,
                false);
        final var translated = new BaseTranslator(0, 0)
                .recordFrom(parts, (receipt, record) -> {}, stateChanges, followingTraces, null);
        return translated
                .transactionSidecarRecords()
                .getFirst()
                .stateChangesOrThrow()
                .contractStateChanges()
                .getFirst()
                .storageChanges()
                .getFirst();
    }

    private static ContractSlotUsage explicitUsage(final Bytes read, final Bytes written) {
        return ContractSlotUsage.newBuilder()
                .contractId(CONTRACT)
                .writtenSlotKeys(new WrittenSlotKeys(List.of(KEY)))
                .slotReads(SlotRead.newBuilder()
                        .index(0)
                        .readValue(read)
                        .writtenValue(written)
                        .build())
                .build();
    }

    private static ContractSlotUsage serialUsage(final Bytes read, final boolean explicitKeys) {
        return ContractSlotUsage.newBuilder()
                .contractId(CONTRACT)
                .writtenSlotKeys(explicitKeys ? new WrittenSlotKeys(List.of(KEY)) : null)
                .slotReads(SlotRead.newBuilder().index(0).readValue(read).build())
                .build();
    }

    private static ScopedTraceData scoped(final ContractSlotUsage usage) {
        return new ScopedTraceData(trace(usage), null);
    }

    private static TraceData trace(final ContractSlotUsage usage) {
        return TraceData.newBuilder()
                .evmTraceData(EvmTraceData.newBuilder().contractSlotUsages(usage))
                .build();
    }

    private static StateChange stateChange(final Bytes written) {
        return StateChange.newBuilder()
                .stateId(STATE_ID_STORAGE.protoOrdinal())
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().slotKeyKey(new SlotKey(CONTRACT, PADDED_KEY)))
                        .value(MapChangeValue.newBuilder()
                                .slotValueValue(SlotValue.newBuilder().value(written))))
                .build();
    }
}
