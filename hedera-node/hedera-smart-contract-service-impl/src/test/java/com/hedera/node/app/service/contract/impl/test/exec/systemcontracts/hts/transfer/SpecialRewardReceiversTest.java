// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SpecialRewardReceivers.SPECIAL_REWARD_RECEIVERS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HAPI_RECORD_BUILDER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpecialRewardReceiversTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private MessageFrame initialFrame;

    @Mock
    private ContractOperationStreamBuilder recordBuilder;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        stack.push(initialFrame);
        stack.addFirst(frame);
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(initialFrame.getContextVariable(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE))
                .willReturn(recordBuilder);
    }

    @Test
    void addsFungibleTokenTransfers() {
        final var body = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .transfers(List.of(
                                AccountAmount.newBuilder()
                                        .accountID(A_NEW_ACCOUNT_ID)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(B_NEW_ACCOUNT_ID)
                                        .build()))
                        .build())
                .build();
        SPECIAL_REWARD_RECEIVERS.addInFrame(frame, body, List.of());

        verify(recordBuilder).trackExplicitRewardSituation(A_NEW_ACCOUNT_ID);
        verify(recordBuilder).trackExplicitRewardSituation(B_NEW_ACCOUNT_ID);
    }

    @Test
    void addsNftOwnershipChanges() {
        final var body = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .nftTransfers(NftTransfer.newBuilder()
                                .senderAccountID(A_NEW_ACCOUNT_ID)
                                .receiverAccountID(B_NEW_ACCOUNT_ID)
                                .serialNumber(123L)
                                .isApproval(true)
                                .build())
                        .build())
                .build();
        SPECIAL_REWARD_RECEIVERS.addInFrame(frame, body, List.of());

        verify(recordBuilder).trackExplicitRewardSituation(A_NEW_ACCOUNT_ID);
        verify(recordBuilder).trackExplicitRewardSituation(B_NEW_ACCOUNT_ID);
    }

    @Test
    void addsHbarTransfers() {
        final var body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(List.of(
                                AccountAmount.newBuilder()
                                        .accountID(A_NEW_ACCOUNT_ID)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(B_NEW_ACCOUNT_ID)
                                        .build()))
                        .build())
                .build();
        SPECIAL_REWARD_RECEIVERS.addInFrame(frame, body, List.of());

        verify(recordBuilder).trackExplicitRewardSituation(A_NEW_ACCOUNT_ID);
        verify(recordBuilder).trackExplicitRewardSituation(B_NEW_ACCOUNT_ID);
    }

    @Test
    void tracksFeeCollectionAccounts() {
        SPECIAL_REWARD_RECEIVERS.addInFrame(
                frame,
                CryptoTransferTransactionBody.DEFAULT,
                List.of(AssessedCustomFee.newBuilder()
                        .feeCollectorAccountId(A_NEW_ACCOUNT_ID)
                        .build()));
        verify(recordBuilder).trackExplicitRewardSituation(A_NEW_ACCOUNT_ID);
    }
}
