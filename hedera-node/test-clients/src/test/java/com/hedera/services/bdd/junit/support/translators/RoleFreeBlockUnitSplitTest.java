// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_SIGN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.CreateScheduleOutput;
import com.hedera.hapi.block.stream.output.SignScheduleOutput;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionOutput;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionParts;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleFreeBlockUnitSplitTest {
    private static final TransactionID OUTER_ID = TransactionID.newBuilder()
            .accountID(AccountID.newBuilder().accountNum(1001))
            .transactionValidStart(Timestamp.newBuilder().seconds(1))
            .build();

    @ParameterizedTest
    @MethodSource("nestedExecutions")
    void enrichesNestedScheduledExecutionsButNotTheirSyntheticCreations(
            final HederaFunctionality triggeringFunction,
            final HederaFunctionality scheduledFunction,
            final int nonce) {
        final var parts = nestedParts(triggeringFunction, scheduledFunction, nonce, true);

        assertTrue(parts.get(0).hasEnrichedLegacyRecord());
        assertFalse(parts.get(1).hasEnrichedLegacyRecord());
        assertTrue(parts.get(2).hasEnrichedLegacyRecord());
        assertFalse(parts.get(2).isTopLevel());
        assertFalse(parts.get(3).hasEnrichedLegacyRecord());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void aScheduledFlagAloneDoesNotEnrichSyntheticRecords(final int nonce) {
        final var parts = nestedParts(SCHEDULE_SIGN, CONTRACT_CREATE, nonce, false);

        assertFalse(parts.get(2).hasEnrichedLegacyRecord());
        assertFalse(parts.get(3).hasEnrichedLegacyRecord());
    }

    private static Stream<Arguments> nestedExecutions() {
        return Stream.of(CONTRACT_CALL, CONTRACT_CREATE)
                .flatMap(function -> Stream.of(
                        Arguments.of(SCHEDULE_SIGN, function, 0),
                        Arguments.of(SCHEDULE_SIGN, function, 8),
                        Arguments.of(SCHEDULE_CREATE, function, 8)));
    }

    private static List<BlockTransactionParts> nestedParts(
            final HederaFunctionality triggeringFunction,
            final HederaFunctionality scheduledFunction,
            final int scheduledNonce,
            final boolean includeScheduledId) {
        final var scheduledId =
                OUTER_ID.copyBuilder().scheduled(true).nonce(scheduledNonce).build();
        final var items = new ArrayList<BlockItem>();
        addTransaction(items, body(CONTRACT_CALL, OUTER_ID), 0);
        items.add(BlockItem.newBuilder().stateChanges(StateChanges.DEFAULT).build());
        final var triggerId = OUTER_ID.copyBuilder()
                .nonce(triggeringFunction == SCHEDULE_CREATE ? scheduledNonce : 1)
                .build();
        addTransaction(items, body(triggeringFunction, triggerId), 1);
        final var output = TransactionOutput.newBuilder();
        if (triggeringFunction == SCHEDULE_CREATE) {
            output.createSchedule(
                    CreateScheduleOutput.newBuilder().scheduledTransactionId(includeScheduledId ? scheduledId : null));
        } else {
            output.signSchedule(
                    SignScheduleOutput.newBuilder().scheduledTransactionId(includeScheduledId ? scheduledId : null));
        }
        items.add(BlockItem.newBuilder().transactionOutput(output).build());
        addTransaction(
                items,
                body(scheduledFunction, scheduledId),
                2,
                includeScheduledId ? ScheduleID.newBuilder().scheduleNum(1038).build() : null);
        addTransaction(
                items,
                body(
                        CONTRACT_CREATE,
                        scheduledId.copyBuilder().nonce(scheduledNonce + 1).build()),
                3);

        final var units = new RoleFreeBlockUnitSplit().split(items);
        assertEquals(1, units.size());
        assertEquals(4, units.getFirst().blockTransactionParts().size());
        return units.getFirst().blockTransactionParts();
    }

    private static TransactionBody body(final HederaFunctionality function, final TransactionID txnId) {
        final var builder = TransactionBody.newBuilder().transactionID(txnId);
        switch (function) {
            case CONTRACT_CALL -> builder.contractCall(ContractCallTransactionBody.DEFAULT);
            case CONTRACT_CREATE -> builder.contractCreateInstance(ContractCreateTransactionBody.DEFAULT);
            case SCHEDULE_CREATE -> builder.scheduleCreate(ScheduleCreateTransactionBody.DEFAULT);
            case SCHEDULE_SIGN -> builder.scheduleSign(ScheduleSignTransactionBody.DEFAULT);
            default -> throw new IllegalArgumentException("Unsupported fixture function " + function);
        }
        return builder.build();
    }

    private static void addTransaction(final List<BlockItem> items, final TransactionBody body, final int nanos) {
        addTransaction(items, body, nanos, null);
    }

    private static void addTransaction(
            final List<BlockItem> items, final TransactionBody body, final int nanos, final ScheduleID scheduleRef) {
        items.add(BlockItem.newBuilder()
                .signedTransaction(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                        .build()))
                .build());
        items.add(BlockItem.newBuilder()
                .transactionResult(TransactionResult.newBuilder()
                        .status(SUCCESS)
                        .scheduleRef(scheduleRef)
                        .consensusTimestamp(Timestamp.newBuilder().seconds(2).nanos(nanos)))
                .build());
    }
}
