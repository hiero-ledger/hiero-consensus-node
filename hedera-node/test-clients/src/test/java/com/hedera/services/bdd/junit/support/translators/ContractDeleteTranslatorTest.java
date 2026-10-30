// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.translators.impl.ContractDeleteTranslator;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractDeleteTranslatorTest {
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().shardNum(1).realmNum(2).contractNum(1234).build();
    private static final ContractID OTHER_CONTRACT_ID =
            CONTRACT_ID.copyBuilder().contractNum(1235).build();

    private final ContractDeleteTranslator subject = new ContractDeleteTranslator();
    private final BaseTranslator baseTranslator = new BaseTranslator(1, 2);

    @Test
    void constructorSelfDestructRetainsIdAfterCreationConsumedStateChange() {
        final var remaining = new ArrayList<StateChange>();

        final var record = translate(CONTRACT_ID, SUCCESS, remaining);

        assertEquals(CONTRACT_ID, record.transactionRecord().receiptOrThrow().contractID());
        assertEquals(SUCCESS, record.transactionRecord().receiptOrThrow().status());
        assertEquals(0, record.transactionRecord().transactionFee());
        assertTrue(remaining.isEmpty());
    }

    @Test
    void multipleDeletionsConsumeOnlyTheContractNamedInTheBody() {
        final var unrelatedDeletion = accountChange(OTHER_CONTRACT_ID, true);
        final var sameNumberInAnotherRealm =
                accountChange(CONTRACT_ID.copyBuilder().realmNum(3).build(), true);
        final var remaining =
                new ArrayList<>(List.of(unrelatedDeletion, sameNumberInAnotherRealm, accountChange(CONTRACT_ID, true)));

        final var record = translate(CONTRACT_ID, SUCCESS, remaining);

        assertEquals(CONTRACT_ID, record.transactionRecord().receiptOrThrow().contractID());
        assertEquals(List.of(unrelatedDeletion, sameNumberInAnotherRealm), remaining);
    }

    @Test
    void alreadyConsumedDeletionDoesNotConsumeAnotherContractOrUndeletedUpdate() {
        final var unrelatedDeletion = accountChange(OTHER_CONTRACT_ID, true);
        final var undeletedUpdate = accountChange(CONTRACT_ID, false);
        final var remaining = new ArrayList<>(List.of(unrelatedDeletion, undeletedUpdate));

        final var record = translate(CONTRACT_ID, SUCCESS, remaining);

        assertEquals(CONTRACT_ID, record.transactionRecord().receiptOrThrow().contractID());
        assertEquals(List.of(unrelatedDeletion, undeletedUpdate), remaining);
    }

    @Test
    void failureDoesNotAcquireContractIdOrConsumeDeletedState() {
        final var deletion = accountChange(CONTRACT_ID, true);
        final var remaining = new ArrayList<>(List.of(deletion));

        final var record = translate(CONTRACT_ID, CONTRACT_REVERT_EXECUTED, remaining);

        assertNull(record.transactionRecord().receiptOrThrow().contractID());
        assertEquals(List.of(deletion), remaining);
    }

    @Test
    void aliasedHapiDeletionStillResolvesNumericReceiptFromState() {
        final var aliasId = ContractID.newBuilder()
                .shardNum(1)
                .realmNum(2)
                .evmAddress(Bytes.fromHex("1234123412341234123412341234123412341234"))
                .build();
        final var remaining = new ArrayList<>(List.of(accountChange(CONTRACT_ID, true)));

        final var record = translate(aliasId, SUCCESS, remaining);

        assertEquals(CONTRACT_ID, record.transactionRecord().receiptOrThrow().contractID());
        assertTrue(remaining.isEmpty());
    }

    private SingleTransactionRecord translate(
            final ContractID contractId, final ResponseCodeEnum status, final List<StateChange> remaining) {
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(3))
                        .transactionValidStart(Timestamp.newBuilder().seconds(1))
                        .nonce(2))
                .contractDeleteInstance(
                        ContractDeleteTransactionBody.newBuilder().contractID(contractId))
                .build();
        final var parts = new BlockTransactionParts(
                new TransactionParts(Transaction.DEFAULT, body, CONTRACT_DELETE),
                TransactionResult.newBuilder()
                        .status(status)
                        .consensusTimestamp(Timestamp.newBuilder().seconds(2).nanos(2))
                        .parentConsensusTimestamp(Timestamp.newBuilder().seconds(2))
                        .build(),
                List.of(),
                List.of(),
                false,
                false,
                false);
        return subject.translate(parts, baseTranslator, remaining, List.of(), List.of(), null, null);
    }

    private static StateChange accountChange(final ContractID contractId, final boolean deleted) {
        final var accountId = AccountID.newBuilder()
                .shardNum(contractId.shardNum())
                .realmNum(contractId.realmNum())
                .accountNum(contractId.contractNumOrThrow())
                .build();
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().accountIdKey(accountId))
                        .value(MapChangeValue.newBuilder()
                                .accountValue(Account.newBuilder()
                                        .accountId(accountId)
                                        .smartContract(true)
                                        .deleted(deleted))))
                .build();
    }
}
