// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BYTECODE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BaseTranslator#addCreatedIdsOfCallTo}, which attributes contract creations to the call that made
 * them when a CLPR transaction dispatches several calls in one unit.
 */
class BaseTranslatorCreatedIdsOfCallTest {
    private static final ContractID VERIFIER = contract(1001L);
    private static final ContractID APPLICATION = contract(1002L);
    private static final ContractID CREATED_BY_APPLICATION = contract(1003L);
    private static final ContractID CREATED_BY_VERIFIER = contract(1004L);

    private final BaseTranslator translator = new BaseTranslator(0L, 0L);

    @Test
    @DisplayName("When only the application callback creates a contract, only the callback claims it")
    void onlyApplicationCallbackCreates() {
        final var stateChanges = List.of(bytecodeUpdate(CREATED_BY_APPLICATION));
        final var verifierCall = callWithActions(call(VERIFIER));
        final var applicationCall = callWithActions(call(APPLICATION), create(CREATED_BY_APPLICATION));

        assertEquals(List.of(), createdIdsOf(verifierCall, stateChanges));
        assertEquals(List.of(CREATED_BY_APPLICATION), createdIdsOf(applicationCall, stateChanges));
    }

    @Test
    @DisplayName("When two dispatched calls each create a contract, each claims only its own")
    void eachCallClaimsItsOwnCreation() {
        final var stateChanges = List.of(bytecodeUpdate(CREATED_BY_APPLICATION), bytecodeUpdate(CREATED_BY_VERIFIER));
        final var verifierCall = callWithActions(call(VERIFIER), create(CREATED_BY_VERIFIER));
        final var applicationCall = callWithActions(call(APPLICATION), create(CREATED_BY_APPLICATION));

        assertEquals(List.of(CREATED_BY_VERIFIER), createdIdsOf(verifierCall, stateChanges));
        assertEquals(List.of(CREATED_BY_APPLICATION), createdIdsOf(applicationCall, stateChanges));
    }

    @Test
    @DisplayName("A creation that was not committed (no bytecode update) is not claimed")
    void uncommittedCreationIsNotClaimed() {
        final var applicationCall = callWithActions(call(APPLICATION), create(CREATED_BY_APPLICATION));

        assertEquals(List.of(), createdIdsOf(applicationCall, List.of()));
    }

    @Test
    @DisplayName("Without traces, falls back to every contract created in the unit")
    void withoutTracesFallsBackToUnitCreations() {
        final var stateChanges = List.of(bytecodeUpdate(CREATED_BY_APPLICATION));
        final var callWithoutTraces = new BlockTransactionParts(null, null, null, null, false, false, false);

        assertEquals(List.of(CREATED_BY_APPLICATION), createdIdsOf(callWithoutTraces, stateChanges));
    }

    private List<ContractID> createdIdsOf(final BlockTransactionParts parts, final List<StateChange> stateChanges) {
        final var builder = ContractFunctionResult.newBuilder();
        translator.addCreatedIdsOfCallTo(builder, parts, stateChanges);
        return builder.build().createdContractIDs();
    }

    private static BlockTransactionParts callWithActions(final ContractAction... actions) {
        final var trace = TraceData.newBuilder()
                .evmTraceData(EvmTraceData.newBuilder()
                        .contractActions(Arrays.asList(actions))
                        .build())
                .build();
        return new BlockTransactionParts(null, null, List.of(trace), null, false, false, false);
    }

    private static ContractAction call(final ContractID target) {
        return ContractAction.newBuilder()
                .callType(ContractActionType.CALL)
                .recipientContract(target)
                .build();
    }

    private static ContractAction create(final ContractID created) {
        return ContractAction.newBuilder()
                .callType(ContractActionType.CREATE)
                .recipientContract(created)
                .build();
    }

    private static StateChange bytecodeUpdate(final ContractID contractId) {
        return StateChange.newBuilder()
                .stateId(STATE_ID_BYTECODE.protoOrdinal())
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().contractIdKey(contractId).build())
                        .build())
                .build();
    }

    private static ContractID contract(final long num) {
        return ContractID.newBuilder().contractNum(num).build();
    }
}
