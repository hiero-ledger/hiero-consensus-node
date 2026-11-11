// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BYTECODE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.CallContractOutput;
import com.hedera.hapi.block.stream.output.CreateContractOutput;
import com.hedera.hapi.block.stream.output.EthereumOutput;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.contract.CreatedContractIds;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BaseTranslatorCreatedContractsTest {
    private static final ContractID A = contract(1041);
    private static final ContractID B = contract(1042);
    private static final ContractID C = contract(1043);
    private static final ContractID D = contract(1044);
    private static final List<StateChange> UNIT_CHANGES = List.of(bytecode(A), bytecode(B), bytecode(C), bytecode(D));

    enum OutputKind {
        CALL,
        CREATE,
        ETHEREUM_CALL,
        ETHEREUM_CREATE
    }

    @ParameterizedTest
    @EnumSource(OutputKind.class)
    void preservesOuterAggregateAndEachNestedScopesOwnCreations(final OutputKind kind) {
        // X creates A, calls Y1 that creates B, resumes to create C, then calls Y2 that creates D.
        // X's completed savepoint includes all four; neither Y inherits X's or its sibling's creations.
        assertEquals(List.of(A, B, C, D), translatedIds(parts(kind, result(A, B, C, D))));
        assertEquals(List.of(B), translatedIds(parts(kind, result(B))));
        assertEquals(List.of(D), translatedIds(parts(kind, result(D))));
    }

    @ParameterizedTest
    @EnumSource(OutputKind.class)
    void presentEmptyScopeDoesNotInheritAnotherScopesCreations(final OutputKind kind) {
        final var parts = parts(kind, result());
        final var builder = ContractFunctionResult.newBuilder().createdContractIDs(A);

        assertTrue(new BaseTranslator(0, 0).addCapturedCreatedIdsTo(builder, parts));
        assertEquals(List.of(), builder.build().createdContractIDs());
        assertEquals(List.of(), translatedIds(parts));
    }

    @Test
    void nonceChangesToAnExistingContractDoNotMakeItACreationOfTheNestedScope() {
        // A was created earlier by X. Y calls A to create B, so Y changes A's nonce but only creates B.
        final var result = result(B)
                .copyBuilder()
                .contractNonces(
                        ContractNonceInfo.newBuilder().contractId(A).nonce(2).build(),
                        ContractNonceInfo.newBuilder().contractId(B).nonce(1).build())
                .build();

        assertEquals(List.of(B), translatedIds(parts(OutputKind.CALL, result)));
    }

    @Test
    void capturedIdsTakePrecedenceOverOptionalActionsForBatchScopes() {
        final var ordinaryParts = parts(OutputKind.CALL, result(B));
        final var withActions = new BlockTransactionParts(
                ordinaryParts.transactionParts(),
                ordinaryParts.transactionResult(),
                List.of(TraceData.newBuilder()
                        .evmTraceData(EvmTraceData.newBuilder()
                                .contractActions(ContractAction.newBuilder()
                                        .callType(ContractActionType.CREATE)
                                        .recipientContract(A)
                                        .build()))
                        .build()),
                ordinaryParts.outputs(),
                false,
                true,
                true);

        assertEquals(List.of(B), translatedIds(withActions));
    }

    @ParameterizedTest
    @EnumSource(OutputKind.class)
    void absentScopeMetadataPreservesLegacyStateChangeInference(final OutputKind kind) {
        final var parts = parts(kind, EvmTransactionResult.DEFAULT);

        assertFalse(new BaseTranslator(0, 0).addCapturedCreatedIdsTo(ContractFunctionResult.newBuilder(), parts));
        assertEquals(List.of(A, B, C, D), translatedIds(parts));
    }

    @Test
    void missingOutputsAlsoPreserveLegacyInference() {
        final var parts = new BlockTransactionParts(null, TransactionResult.DEFAULT, null, null, true, true, false);

        assertEquals(List.of(A, B, C, D), translatedIds(parts));
    }

    @Test
    void creationListPresenceSurvivesPbjAndProtocRoundTrips() throws Exception {
        for (final var result : List.of(EvmTransactionResult.DEFAULT, result(), result(A, B))) {
            final var serialized = EvmTransactionResult.PROTOBUF.toBytes(result);
            final var protoc =
                    com.hederahashgraph.api.proto.java.EvmTransactionResult.parseFrom(serialized.toByteArray());

            assertEquals(result.hasCreatedContractIds(), protoc.hasCreatedContractIds());
            assertEquals(result, EvmTransactionResult.PROTOBUF.parse(serialized));
            assertEquals(result, EvmTransactionResult.PROTOBUF.parse(Bytes.wrap(protoc.toByteArray())));
            if (result.hasCreatedContractIds()) {
                assertEquals(
                        result.createdContractIdsOrThrow().contractIds().stream()
                                .map(ContractID::contractNumOrThrow)
                                .toList(),
                        protoc.getCreatedContractIds().getContractIdsList().stream()
                                .map(com.hederahashgraph.api.proto.java.ContractID::getContractNum)
                                .toList());
            }
        }
    }

    private static List<ContractID> translatedIds(final BlockTransactionParts parts) {
        final var result = ContractFunctionResult.newBuilder();
        new BaseTranslator(0, 0).addCreatedIdsTo(result, parts, UNIT_CHANGES);
        return result.build().createdContractIDs();
    }

    private static BlockTransactionParts parts(final OutputKind kind, final EvmTransactionResult result) {
        final var output = TransactionOutput.newBuilder();
        switch (kind) {
            case CALL -> output.contractCall(CallContractOutput.newBuilder().evmTransactionResult(result));
            case CREATE ->
                output.contractCreate(CreateContractOutput.newBuilder().evmTransactionResult(result));
            case ETHEREUM_CALL ->
                output.ethereumCall(EthereumOutput.newBuilder().evmCallTransactionResult(result));
            case ETHEREUM_CREATE ->
                output.ethereumCall(EthereumOutput.newBuilder().evmCreateTransactionResult(result));
        }
        return new BlockTransactionParts(
                new TransactionParts(Transaction.DEFAULT, TransactionBody.DEFAULT, CONTRACT_CALL),
                TransactionResult.DEFAULT,
                null,
                List.of(output.build()),
                false,
                true,
                false);
    }

    private static EvmTransactionResult result(final ContractID... created) {
        return EvmTransactionResult.newBuilder()
                .createdContractIds(new CreatedContractIds(List.of(created)))
                .build();
    }

    private static StateChange bytecode(final ContractID contract) {
        return StateChange.newBuilder()
                .stateId(STATE_ID_BYTECODE.protoOrdinal())
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().contractIdKey(contract))
                        .value(MapChangeValue.newBuilder().bytecodeValue(Bytecode.DEFAULT)))
                .build();
    }

    private static ContractID contract(final long number) {
        return ContractID.newBuilder().contractNum(number).build();
    }
}
