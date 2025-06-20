// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.SystemAccountCreditScreen.SYSTEM_ACCOUNT_CREDIT_SCREEN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer.ApprovalSwitchHelperTest.adjust;
import static com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer.ApprovalSwitchHelperTest.nftTransfer;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule;
import org.junit.jupiter.api.Test;

class SystemAccountCreditScreenTest {
    private static final AccountID SYSTEM_ACCOUNT_ID = AccountID.newBuilder()
            .accountNum(ProcessorModule.NUM_SYSTEM_ACCOUNTS)
            .build();

    @Test
    void detectsHbarCredits() {
        final var hbarCredit = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                // Shouldn't switch since already authorized
                                adjust(OWNER_ID, -1L),
                                // Should switch since not yet authorized
                                adjust(SYSTEM_ACCOUNT_ID, +1))
                        .build())
                .build();
        assertTrue(SYSTEM_ACCOUNT_CREDIT_SCREEN.creditsToSystemAccount(hbarCredit));
    }

    @Test
    void detectsFungibleTokenCredits() {
        final var fungibleCredit = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(FUNGIBLE_TOKEN_ID)
                        .transfers(adjust(OWNER_ID, -1L), adjust(SYSTEM_ACCOUNT_ID, 1L))
                        .build())
                .build();
        assertTrue(SYSTEM_ACCOUNT_CREDIT_SCREEN.creditsToSystemAccount(fungibleCredit));
    }

    @Test
    void detectsNonFungibleTokenCredits() {
        final var nonFungibleCredit = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(NON_FUNGIBLE_TOKEN_ID)
                        .nftTransfers(nftTransfer(OWNER_ID, SYSTEM_ACCOUNT_ID, 69L))
                        .build())
                .build();
        assertTrue(SYSTEM_ACCOUNT_CREDIT_SCREEN.creditsToSystemAccount(nonFungibleCredit));
    }

    @Test
    void detectsNoCredits() {
        final var noSystemCredits = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjust(OWNER_ID, -1L), adjust(RECEIVER_ID, +1L))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(FUNGIBLE_TOKEN_ID)
                                .transfers(adjust(OWNER_ID, -1L), adjust(RECEIVER_ID, +1L))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(NON_FUNGIBLE_TOKEN_ID)
                                .nftTransfers(nftTransfer(OWNER_ID, RECEIVER_ID, 42L))
                                .build())
                .build();
        assertFalse(SYSTEM_ACCOUNT_CREDIT_SCREEN.creditsToSystemAccount(noSystemCredits));
    }
}
