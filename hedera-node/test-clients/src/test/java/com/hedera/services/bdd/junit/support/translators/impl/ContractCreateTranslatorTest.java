// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators.impl;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BYTECODE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.bloomForAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.CreateContractOutput;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.block.stream.trace.EvmTraceData;
import com.hedera.hapi.block.stream.trace.EvmTransactionLog;
import com.hedera.hapi.block.stream.trace.TraceData;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.BaseTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContractCreateTranslatorTest {
    private static final ContractID CONTRACT =
            ContractID.newBuilder().shardNum(11).realmNum(12).contractNum(1041).build();
    private static final AccountID ACCOUNT =
            AccountID.newBuilder().shardNum(11).realmNum(12).accountNum(1041).build();
    private static final EvmTransactionLog LOG = EvmTransactionLog.newBuilder()
            .contractId(CONTRACT)
            .data(Bytes.fromHex("aabb"))
            .topics(Bytes.fromHex("01"))
            .build();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enrichesImmediatelyScheduledCreationWithAndWithoutLogs(final boolean hasLogs) {
        final var translated = translate(true, hasLogs);
        final var result = translated.transactionRecord().contractCreateResultOrThrow();

        assertEquals(List.of(CONTRACT), result.createdContractIDs());
        assertEquals(CONTRACT, translated.transactionRecord().receiptOrThrow().contractID());
        assertEquals(256, result.bloom().length());
        if (hasLogs) {
            assertEquals(1, result.logInfo().size());
            assertEquals(LOG.data(), result.logInfo().getFirst().data());
            assertEquals(result.logInfo().getFirst().bloom(), result.bloom());
            assertNotEquals(bloomForAll(List.of()), result.bloom());
        } else {
            assertTrue(result.logInfo().isEmpty());
            assertEquals(bloomForAll(List.of()), result.bloom());
        }
    }

    @Test
    void doesNotEnrichNestedCreationUnderTheScheduledTransaction() {
        final var result = translate(false, false).transactionRecord().contractCreateResultOrThrow();

        assertEquals(Bytes.EMPTY, result.bloom());
        assertTrue(result.logInfo().isEmpty());
        assertTrue(result.createdContractIDs().isEmpty());
    }

    private static SingleTransactionRecord translate(final boolean hasEnrichedLegacyRecord, final boolean hasLogs) {
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(ACCOUNT)
                        .scheduled(true)
                        .nonce(hasEnrichedLegacyRecord ? 0 : 1))
                .contractCreateInstance(ContractCreateTransactionBody.DEFAULT)
                .build();
        final var parts = new BlockTransactionParts(
                new TransactionParts(Transaction.DEFAULT, body, CONTRACT_CREATE),
                TransactionResult.newBuilder()
                        .status(SUCCESS)
                        .consensusTimestamp(
                                Timestamp.newBuilder().seconds(1_234).nanos(1))
                        .parentConsensusTimestamp(Timestamp.newBuilder().seconds(1_234))
                        .build(),
                hasLogs
                        ? List.of(TraceData.newBuilder()
                                .evmTraceData(EvmTraceData.newBuilder().logs(LOG))
                                .build())
                        : List.of(),
                List.of(TransactionOutput.newBuilder()
                        .contractCreate(CreateContractOutput.newBuilder()
                                .evmTransactionResult(EvmTransactionResult.newBuilder()
                                        .contractId(CONTRACT)
                                        .gasUsed(1_234)))
                        .build()),
                false,
                hasEnrichedLegacyRecord,
                false);
        final var stateChanges = new ArrayList<>(List.of(
                StateChange.newBuilder()
                        .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                        .mapUpdate(MapUpdateChange.newBuilder()
                                .key(MapChangeKey.newBuilder().accountIdKey(ACCOUNT))
                                .value(MapChangeValue.newBuilder()
                                        .accountValue(Account.newBuilder()
                                                .accountId(ACCOUNT)
                                                .smartContract(true))))
                        .build(),
                StateChange.newBuilder()
                        .stateId(STATE_ID_BYTECODE.protoOrdinal())
                        .mapUpdate(MapUpdateChange.newBuilder()
                                .key(MapChangeKey.newBuilder().contractIdKey(CONTRACT))
                                .value(MapChangeValue.newBuilder().bytecodeValue(Bytecode.DEFAULT)))
                        .build()));
        final var baseTranslator = new BaseTranslator(11, 12);
        baseTranslator.prepareForUnit(new BlockTransactionalUnit(List.of(parts), stateChanges));
        return new ContractCreateTranslator()
                .translate(parts, baseTranslator, stateChanges, null, List.of(), null, null);
    }
}
