// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_RECEIVER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ALIASED_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ACCOUNT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.PARANOID_SOMEBODY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.convertAccountToLog;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.tokenTransfersLists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.TransferEventLoggingUtils;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.token.ReadableAccountStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TransferEventLoggingUtilsTest {

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private MessageFrame frame;

    @Test
    void emitErcLogEventsForFt() {
        // given
        final var expectedTransfers = List.of(new TestHelpers.TestTokenTransfer(
                FUNGIBLE_TOKEN_ID, false, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 123));
        given(recordBuilder.tokenTransferLists()).willReturn(tokenTransfersLists(expectedTransfers));
        given(readableAccountStore.getAliasedAccountById(OWNER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(ALIASED_RECEIVER);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());
        // when
        TransferEventLoggingUtils.emitErcLogEventsFor(recordBuilder, readableAccountStore, frame);
        // then
        validateFtLogEvent(logs, expectedTransfers);
    }

    public static void validateFtLogEvent(final List<Log> logs, List<TestHelpers.TestTokenTransfer> expectedTransfers) {
        assertEquals(expectedTransfers.size(), logs.size());
        int index = 0;
        for (Log log : logs) {
            TestHelpers.TestTokenTransfer expectedTransfer = expectedTransfers.get(index);
            assertEquals(3, log.getTopics().size());
            assertEquals(
                    convertAccountToLog(expectedTransfer.senderAccount()),
                    log.getTopics().get(1));
            assertEquals(
                    convertAccountToLog(expectedTransfer.receiverAccount()),
                    log.getTopics().get(2));
            assertEquals(
                    expectedTransfer.amount(), UInt256.fromBytes(log.getData()).toLong());
            index++;
        }
    }

    @Test
    void emitErcLogEventsForNft() {
        // given
        final var expectedTransfers = List.of(new TestHelpers.TestTokenTransfer(
                FUNGIBLE_TOKEN_ID, true, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 123));
        given(recordBuilder.tokenTransferLists()).willReturn(tokenTransfersLists(expectedTransfers));
        given(readableAccountStore.getAliasedAccountById(OWNER_ID)).willReturn(OWNER_ACCOUNT);
        given(readableAccountStore.getAliasedAccountById(RECEIVER_ID)).willReturn(ALIASED_RECEIVER);
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());
        // when
        TransferEventLoggingUtils.emitErcLogEventsFor(recordBuilder, readableAccountStore, frame);
        // then
        validateNftLogEvent(logs, expectedTransfers);
    }

    public static void validateNftLogEvent(
            final List<Log> logs, List<TestHelpers.TestTokenTransfer> expectedTransfers) {
        assertEquals(expectedTransfers.size(), logs.size());
        int index = 0;
        for (Log log : logs) {
            TestHelpers.TestTokenTransfer expectedTransfer = expectedTransfers.get(index);
            assertEquals(4, log.getTopics().size());
            assertEquals(
                    convertAccountToLog(expectedTransfer.senderAccount()),
                    log.getTopics().get(1));
            assertEquals(
                    convertAccountToLog(expectedTransfer.receiverAccount()),
                    log.getTopics().get(2));
            assertEquals(
                    expectedTransfer.amount(),
                    UInt256.fromBytes(Bytes.wrap(
                                    logs.getFirst().getTopics().get(3).toArray()))
                            .toLong());
            index++;
        }
    }

    private void emitErcLogEventsForFtAirdropCustomFee(
            List<AccountAmount> transfers, List<TestHelpers.TestTokenTransfer> expectedTransfers) {
        // given
        given(recordBuilder.tokenTransferLists())
                .willReturn(List.of(TokenTransferList.newBuilder()
                        .token(FUNGIBLE_TOKEN_ID)
                        .transfers(transfers)
                        .build()));
        for (final TestHelpers.TestTokenTransfer transfer : expectedTransfers) {
            given(readableAccountStore.getAliasedAccountById(transfer.sender())).willReturn(transfer.senderAccount());
            given(readableAccountStore.getAliasedAccountById(transfer.receiver()))
                    .willReturn(transfer.receiverAccount());
        }
        final List<Log> logs = new ArrayList<>();
        Mockito.doAnswer(e -> logs.add(e.getArgument(0))).when(frame).addLog(any());
        // when
        TransferEventLoggingUtils.emitErcLogEventsFor(recordBuilder, readableAccountStore, frame);
        // then
        validateFtLogEvent(logs, expectedTransfers);
    }

    // Multiple credit accounts. Should be possible with 'Fractional fee'
    @Test
    void emitErcLogEventsForFtAirdropCustomFeeMultipleCredit() {
        emitErcLogEventsForFtAirdropCustomFee(
                List.of(
                        AccountAmount.newBuilder()
                                .accountID(OWNER_ID)
                                .amount(-11)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(RECEIVER_ID)
                                .amount(10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(A_NEW_ACCOUNT_ID)
                                .amount(1)
                                .build()),
                List.of(
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID, false, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 10),
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                OWNER_ID,
                                OWNER_ACCOUNT,
                                A_NEW_ACCOUNT_ID,
                                ALIASED_SOMEBODY,
                                1)));
    }

    // Multiple debit accounts. Reserved for possible future use cases
    @Test
    void emitErcLogEventsForFtAirdropCustomFeeMultipleDebit() {
        emitErcLogEventsForFtAirdropCustomFee(
                List.of(
                        AccountAmount.newBuilder()
                                .accountID(OWNER_ID)
                                .amount(-10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(A_NEW_ACCOUNT_ID)
                                .amount(-1)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(RECEIVER_ID)
                                .amount(11)
                                .build()),
                List.of(
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID, false, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 10),
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                A_NEW_ACCOUNT_ID,
                                ALIASED_SOMEBODY,
                                RECEIVER_ID,
                                ALIASED_RECEIVER,
                                1)));
    }

    // Multiple debit and credit accounts. Reserved for possible future use cases
    @Test
    void emitErcLogEventsForFtAirdropCustomFeeMultipleDebitAndCredit() {
        emitErcLogEventsForFtAirdropCustomFee(
                List.of(
                        AccountAmount.newBuilder()
                                .accountID(OWNER_ID)
                                .amount(-10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(A_NEW_ACCOUNT_ID)
                                .amount(-2)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(RECEIVER_ID)
                                .amount(11)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(B_NEW_ACCOUNT_ID)
                                .amount(1)
                                .build()),
                List.of(
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID, false, OWNER_ID, OWNER_ACCOUNT, RECEIVER_ID, ALIASED_RECEIVER, 10),
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                A_NEW_ACCOUNT_ID,
                                ALIASED_SOMEBODY,
                                RECEIVER_ID,
                                ALIASED_RECEIVER,
                                1),
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                A_NEW_ACCOUNT_ID,
                                ALIASED_SOMEBODY,
                                B_NEW_ACCOUNT_ID,
                                PARANOID_SOMEBODY,
                                1)));
    }

    // Multiple debit and credit accounts with same amount to check accountId ordering.
    @Test
    void emitErcLogEventsForMultipleDebitAndCreditWithSameAmount() {
        final AccountID sender1 = AccountID.newBuilder().accountNum(1_000_009).build();
        final AccountID sender2 = AccountID.newBuilder().accountNum(1_000_008).build();
        final AccountID sender3 = AccountID.newBuilder()
                .alias(com.hedera.pbj.runtime.io.buffer.Bytes.fromHex("ee1ef340808e37344e8150037c0deee33060e123"))
                .build();
        final AccountID receiver1 = AccountID.newBuilder().accountNum(1_000_001).build();
        final AccountID receiver2 = AccountID.newBuilder().accountNum(1_000_002).build();
        final AccountID receiver3 = AccountID.newBuilder()
                .alias(com.hedera.pbj.runtime.io.buffer.Bytes.fromHex("ff1ef340808e37344e8150037c0deee33060e123"))
                .build();

        emitErcLogEventsForFtAirdropCustomFee(
                List.of(
                        AccountAmount.newBuilder()
                                .accountID(sender1)
                                .amount(-10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(sender2)
                                .amount(-10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(sender3)
                                .amount(-10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(receiver1)
                                .amount(10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(receiver2)
                                .amount(10)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(receiver3)
                                .amount(10)
                                .build()),
                List.of(
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                sender3,
                                Account.newBuilder()
                                        .alias(Objects.requireNonNull(sender3.alias()))
                                        .build(),
                                receiver3,
                                Account.newBuilder()
                                        .alias(Objects.requireNonNull(receiver3.alias()))
                                        .build(),
                                10),
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                sender2,
                                Account.newBuilder().accountId(sender1).build(),
                                receiver1,
                                Account.newBuilder().accountId(receiver1).build(),
                                10),
                        new TestHelpers.TestTokenTransfer(
                                FUNGIBLE_TOKEN_ID,
                                false,
                                sender1,
                                Account.newBuilder().accountId(sender2).build(),
                                receiver2,
                                Account.newBuilder().accountId(receiver2).build(),
                                10)));
    }
}
