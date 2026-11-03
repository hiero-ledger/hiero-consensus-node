// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_BYTECODE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.impl.ContractCreateTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractCreateTranslatorTest {
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().shardNum(1).realmNum(2).contractNum(1602).build();
    private static final ContractID CHILD_ID =
            CONTRACT_ID.copyBuilder().contractNum(1603).build();
    private static final Bytes TOPIC =
            Bytes.fromHex("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    private static final EvmTransactionLog LOG = EvmTransactionLog.newBuilder()
            .contractId(CONTRACT_ID)
            .data(Bytes.fromHex("0123456789abcdef"))
            .topics(TOPIC)
            .build();

    private final ContractCreateTranslator subject = new ContractCreateTranslator();

    @Test
    void createTriggeredByScheduleOpGetsSameEnrichedResultAsTopLevelCreate() {
        final var topLevel = translate(true, true);
        final var scheduled = translate(false, true);

        assertEquals(List.of(CONTRACT_ID, CHILD_ID), scheduled.createdContractIDs());
        assertEquals(1, scheduled.logInfo().size());
        final var logInfo = scheduled.logInfo().getFirst();
        assertEquals(CONTRACT_ID, logInfo.contractID());
        assertEquals(LOG.data(), logInfo.data());
        assertEquals(List.of(TOPIC), logInfo.topic());
        assertEquals(256, scheduled.bloom().length());
        assertEquals(logInfo.bloom(), scheduled.bloom());
        assertEquals(topLevel, scheduled);
    }

    @Test
    void createWithoutEnrichedLegacyRecordKeepsMinimalResult() {
        final var result = translate(false, false);

        assertEquals(CONTRACT_ID, result.contractID());
        assertTrue(result.logInfo().isEmpty());
        assertTrue(result.createdContractIDs().isEmpty());
        assertEquals(Bytes.EMPTY, result.bloom());
    }

    private ContractFunctionResult translate(final boolean isTopLevel, final boolean hasEnrichedLegacyRecord) {
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(
                                AccountID.newBuilder().shardNum(1).realmNum(2).accountNum(2))
                        .transactionValidStart(Timestamp.newBuilder().seconds(1))
                        .scheduled(!isTopLevel))
                .contractCreateInstance(
                        ContractCreateTransactionBody.newBuilder().initcode(Bytes.fromHex("6080")))
                .build();
        final var evmResult = EvmTransactionResult.newBuilder()
                .senderId(AccountID.newBuilder().shardNum(1).realmNum(2).accountNum(3))
                .contractId(CONTRACT_ID)
                .gasUsed(107_973L)
                .contractNonces(ContractNonceInfo.newBuilder()
                        .contractId(CHILD_ID)
                        .nonce(1)
                        .build())
                .build();
        final var parts = new BlockTransactionParts(
                new TransactionParts(Transaction.DEFAULT, body, CONTRACT_CREATE),
                TransactionResult.newBuilder()
                        .status(SUCCESS)
                        .consensusTimestamp(Timestamp.newBuilder().seconds(2).nanos(2))
                        .build(),
                List.of(TraceData.newBuilder()
                        .evmTraceData(EvmTraceData.newBuilder().logs(LOG))
                        .build()),
                List.of(TransactionOutput.newBuilder()
                        .contractCreate(CreateContractOutput.newBuilder().evmTransactionResult(evmResult))
                        .build()),
                isTopLevel,
                hasEnrichedLegacyRecord,
                false);
        final var remaining = new ArrayList<>(
                List.of(bytecodeChange(CONTRACT_ID), bytecodeChange(CHILD_ID), accountChange(CONTRACT_ID)));
        final var translator = new BaseTranslator(1, 2);
        translator.prepareForUnit(new BlockTransactionalUnit(List.of(parts), List.copyOf(remaining)));
        return subject.translate(parts, translator, remaining, List.of(), List.of(), null, null)
                .transactionRecord()
                .contractCreateResultOrThrow();
    }

    private static StateChange bytecodeChange(final ContractID contractId) {
        return StateChange.newBuilder()
                .stateId(STATE_ID_BYTECODE.protoOrdinal())
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().contractIdKey(contractId))
                        .value(MapChangeValue.newBuilder()
                                .bytecodeValue(Bytecode.newBuilder().code(Bytes.fromHex("6080")))))
                .build();
    }

    private static StateChange accountChange(final ContractID contractId) {
        final var accountId = AccountID.newBuilder()
                .shardNum(contractId.shardNum())
                .realmNum(contractId.realmNum())
                .accountNum(contractId.contractNumOrThrow())
                .build();
        return StateChange.newBuilder()
                .stateId(STATE_ID_ACCOUNTS.protoOrdinal())
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().accountIdKey(accountId))
                        .value(MapChangeValue.newBuilder()
                                .accountValue(Account.newBuilder()
                                        .accountId(accountId)
                                        .smartContract(true))))
                .build();
    }
}
