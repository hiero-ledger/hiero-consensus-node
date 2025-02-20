// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.CallStatusStandardizer.CALL_STATUS_STANDARDIZER;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer.ApprovalSwitchHelperTest.adjust;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer.ApprovalSwitchHelperTest.nftTransfer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import java.util.ArrayDeque;
import java.util.Deque;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CallStatusStandardizerTest {
    private static final AccountID MISC_ID =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID STAKING_FUNDING_ID =
            AccountID.newBuilder().accountNum(800L).build();
    private static final AccountID NODE_REWARD_ID =
            AccountID.newBuilder().accountNum(801L).build();

    @Mock
    private MessageFrame frame;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    @Test
    void noopForNonInvalidAccountId() {
        final var standardCode =
                CALL_STATUS_STANDARDIZER.codeForFailure(INVALID_NFT_ID, frame, CryptoTransferTransactionBody.DEFAULT);

        assertEquals(INVALID_NFT_ID, standardCode);
    }

    @Test
    void invalidAccountIdNoopIfNotImmutableAccountDebit() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        given(frame.getMessageFrameStack()).willReturn(stack);

        final var standardCode = CALL_STATUS_STANDARDIZER.codeForFailure(
                INVALID_ACCOUNT_ID, frame, CryptoTransferTransactionBody.DEFAULT);

        assertEquals(INVALID_ACCOUNT_ID, standardCode);
    }

    @Test
    void translatesInvalidAccountIdForImmutableAccountHbarDebit() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        given(frame.getMessageFrameStack()).willReturn(stack);

        final var hbarDebit = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjust(STAKING_FUNDING_ID, -1L), adjust(MISC_ID, +1))
                        .build())
                .build();

        final var standardCode = CALL_STATUS_STANDARDIZER.codeForFailure(INVALID_ACCOUNT_ID, frame, hbarDebit);

        assertEquals(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE, standardCode);
    }

    @Test
    void translatesInvalidAccountIdForImmutableAccountFungibleDebit() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        given(frame.getMessageFrameStack()).willReturn(stack);

        final var fungibleDebit = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(FUNGIBLE_TOKEN_ID)
                        .transfers(adjust(NODE_REWARD_ID, -1L), adjust(MISC_ID, 1L))
                        .build())
                .build();

        final var standardCode = CALL_STATUS_STANDARDIZER.codeForFailure(INVALID_ACCOUNT_ID, frame, fungibleDebit);

        assertEquals(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE, standardCode);
    }

    @Test
    void translatesInvalidAccountIdForImmutableAccountNftTransfer() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(DEFAULT_CONFIG);
        given(frame.getMessageFrameStack()).willReturn(stack);

        final var nonFungibleDebit = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(NON_FUNGIBLE_TOKEN_ID)
                        .nftTransfers(nftTransfer(STAKING_FUNDING_ID, MISC_ID, 69L))
                        .build())
                .build();

        final var standardCode = CALL_STATUS_STANDARDIZER.codeForFailure(INVALID_ACCOUNT_ID, frame, nonFungibleDebit);

        assertEquals(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE, standardCode);
    }
}
