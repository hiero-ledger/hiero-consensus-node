// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ApprovalSwitchHelper.APPROVAL_SWITCH_HELPER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.APPROVED_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_FUNGIBLE_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.UNAUTHORIZED_SPENDER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.node.app.spi.key.RequirementVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApprovalSwitchHelperTest {
    @Mock
    private RequirementVerifier signatureTest;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Test
    void switchesOnlyUnauthorizedDebitsToApprovals() {
        given(nativeOperations.getAccount(OWNER_ID)).willReturn(account(OWNER_ID, AN_ED25519_KEY));
        given(nativeOperations.getAccount(APPROVED_ID)).willReturn(account(APPROVED_ID, B_SECP256K1_KEY));

        given(signatureTest.test(KeyRequirement.forAccount(OWNER_ID, AN_ED25519_KEY)))
                .willReturn(true);
        given(signatureTest.test(KeyRequirement.forAccount(APPROVED_ID, B_SECP256K1_KEY)))
                .willReturn(false);

        final var output =
                APPROVAL_SWITCH_HELPER.switchToApprovalsAsNeededIn(inputTransfer(), signatureTest, nativeOperations);

        assertEquals(revisedTransfer(), output);
    }

    @Test
    void sharedKeyDoesNotAuthorizeOtherAccountDebits() {
        given(nativeOperations.getAccount(OWNER_ID)).willReturn(account(OWNER_ID, B_SECP256K1_KEY));
        given(signatureTest.test(KeyRequirement.forAccount(OWNER_ID, B_SECP256K1_KEY)))
                .willReturn(true);
        given(nativeOperations.getAccount(APPROVED_ID)).willReturn(account(APPROVED_ID, B_SECP256K1_KEY));

        given(signatureTest.test(KeyRequirement.forAccount(APPROVED_ID, B_SECP256K1_KEY)))
                .willReturn(false);

        final var output =
                APPROVAL_SWITCH_HELPER.switchToApprovalsAsNeededIn(inputTransfer(), signatureTest, nativeOperations);

        assertEquals(revisedTransfer(), output);
    }

    @Test
    void okToHaveEmptyTransfers() {
        final var output = APPROVAL_SWITCH_HELPER.switchToApprovalsAsNeededIn(
                CryptoTransferTransactionBody.DEFAULT, signatureTest, nativeOperations);
        assertEquals(CryptoTransferTransactionBody.DEFAULT, output);
    }

    private static Account account(final AccountID id, final Key key) {
        return Account.newBuilder().accountId(id).key(key).build();
    }

    private CryptoTransferTransactionBody inputTransfer() {
        return CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                // Shouldn't switch since already authorized
                                adjust(OWNER_ID, -1L),
                                // Should switch since not yet authorized
                                adjust(APPROVED_ID, -1L),
                                // Just here to cover the case of a missing key
                                adjust(UNAUTHORIZED_SPENDER_ID, -1L),
                                adjust(RECEIVER_ID, +3L))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(FUNGIBLE_TOKEN_ID)
                                .transfers(
                                        // Shouldn't switch since already authorized
                                        adjust(OWNER_ID, -1L),
                                        // Should switch since not yet authorized
                                        adjust(APPROVED_ID, -1L),
                                        adjust(RECEIVER_ID, +2L))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(NON_FUNGIBLE_TOKEN_ID)
                                .nftTransfers(
                                        // Shouldn't switch since already authorized
                                        nftTransfer(OWNER_ID, RECEIVER_ID, 42L),
                                        // Should switch since not yet authorized
                                        nftTransfer(APPROVED_ID, RECEIVER_ID, 69L),
                                        // Just here to cover the case of a missing key
                                        nftTransfer(UNAUTHORIZED_SPENDER_ID, RECEIVER_ID, 101L))
                                .build())
                .build();
    }

    private CryptoTransferTransactionBody revisedTransfer() {
        return CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                adjust(OWNER_ID, -1L),
                                approvedAdjust(APPROVED_ID, -1L),
                                adjust(UNAUTHORIZED_SPENDER_ID, -1L),
                                adjust(RECEIVER_ID, +3L))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(FUNGIBLE_TOKEN_ID)
                                .transfers(
                                        adjust(OWNER_ID, -1L),
                                        approvedAdjust(APPROVED_ID, -1L),
                                        adjust(RECEIVER_ID, +2L))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(NON_FUNGIBLE_TOKEN_ID)
                                .nftTransfers(
                                        nftTransfer(OWNER_ID, RECEIVER_ID, 42L),
                                        approvedNftTransfer(APPROVED_ID, RECEIVER_ID, 69L),
                                        nftTransfer(UNAUTHORIZED_SPENDER_ID, RECEIVER_ID, 101L))
                                .build())
                .build();
    }

    static AccountAmount adjust(@NonNull final AccountID account, final long amount) {
        return AccountAmount.newBuilder().accountID(account).amount(amount).build();
    }

    private AccountAmount approvedAdjust(@NonNull final AccountID account, final long amount) {
        return AccountAmount.newBuilder()
                .accountID(account)
                .amount(amount)
                .isApproval(true)
                .build();
    }

    static NftTransfer nftTransfer(@NonNull final AccountID from, @NonNull final AccountID to, final long serialNo) {
        return NftTransfer.newBuilder()
                .serialNumber(serialNo)
                .senderAccountID(from)
                .receiverAccountID(to)
                .build();
    }

    private NftTransfer approvedNftTransfer(
            @NonNull final AccountID from, @NonNull final AccountID to, final long serialNo) {
        return NftTransfer.newBuilder()
                .serialNumber(serialNo)
                .senderAccountID(from)
                .receiverAccountID(to)
                .isApproval(true)
                .build();
    }
}
