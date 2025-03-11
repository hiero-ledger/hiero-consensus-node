// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.TokenAssociateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class CryptoTransferHandlerPureChecksTest extends CryptoTransferHandlerTestBase {

    @Mock
    private PureChecksContext pureChecksContext;

    @SuppressWarnings("DataFlowIssue")
    @Test
    void pureChecksNullArgThrows() {
        Assertions.assertThatThrownBy(() -> subject.pureChecks(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pureChecksHasNoCryptoTransfer() {
        final var nonTransferTxnBody = TokenAssociateTransactionBody.newBuilder();
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                .tokenAssociate(nonTransferTxnBody)
                .build();
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void pureChecksHbarTransfersHasNullAccountId() {
        final var txn = newCryptoTransfer(
                ACCT_3333_MINUS_10,
                ACCT_4444_PLUS_10.copyBuilder().accountID((AccountID) null).build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    void pureChecksHbarTransfersHasAccountIdWithEmptyAliasAndNumber() {
        final var txn = newCryptoTransfer(
                ACCT_3333_MINUS_10,
                AccountAmount.newBuilder()
                        .accountID(AccountID.newBuilder()
                                .accountNum(5555)
                                .alias(Bytes.wrap(""))
                                .build())
                        .amount(10)
                        .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    void pureChecksHbarTransfersHasAccountIdWithIllegalNumber() {
        final var txn = newCryptoTransfer(
                ACCT_3333_MINUS_10,
                ACCT_4444_PLUS_10
                        .copyBuilder()
                        .accountID(idFactory.newAccountId(0))
                        .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    void pureChecksHbarTransfersHasAccountIdWithIllegalAlias() {
        final var txn = newCryptoTransfer(
                ACCT_4444_MINUS_5,
                AccountAmount.newBuilder()
                        .accountID(AccountID.newBuilder().alias(Bytes.wrap("")).build())
                        .amount(5)
                        .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_ACCOUNT_ID));
    }

    @Test
    void pureChecksHbarTransfersHasNonZeroHbarAdjustments() {
        // A net non-zero transfer balance of (-10 + 11) = 1 should cause the pure checks to fail
        final var txn = newCryptoTransfer(
                ACCT_3333_MINUS_10, ACCT_4444_PLUS_10.copyBuilder().amount(11).build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksHbarTransfersHasRepeatedAccountId() {
        final var txn = newCryptoTransfer(
                ACCT_3333_MINUS_10, ACCT_3333_MINUS_10.copyBuilder().amount(10).build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksHasValidHbarTransfers() {
        // Note: this test only checks for valid hbar transfers (WITHOUT any token transfers)
        final var txn = newCryptoTransfer(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5);
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void pureChecksFungibleTokenTransfersHasMissingTokenId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token((TokenID) null)
                // These are TOKEN fungible amount transfers, not HBAR amount transfers
                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void pureChecksFungibleTokenTransfersHasMissingAccountId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are TOKEN fungible amount transfers, not HBAR amount transfers
                .transfers(
                        ACCT_3333_MINUS_10,
                        ACCT_4444_PLUS_10
                                .copyBuilder()
                                .accountID((AccountID) null)
                                .build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void pureChecksFungibleTokenTransfersHasRepeatedAccountId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are TOKEN amount transfers, not HBAR amount transfers
                .transfers(
                        ACCT_4444_MINUS_5,
                        ACCT_4444_MINUS_5.copyBuilder().amount(5).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksFungibleTokenTransfersHasNonZeroTokenSum() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are TOKEN amount transfers, not HBAR amount transfers
                .transfers(
                        ACCT_3333_MINUS_10,
                        ACCT_4444_PLUS_10.copyBuilder().amount(5).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN));
    }

    @Test
    void pureChecksHasValidFungibleTokenTransfers() {
        // Note: this test only checks for valid fungible token transfers (WITHOUT any hbar or nft transfers)
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasMissingTokenId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token((TokenID) null)
                // These are nft transfers, not hbar or fungible token transfers
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasInvalidNftId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are nft transfers, not hbar or fungible token transfers
                .nftTransfers(
                        SERIAL_1_FROM_3333_TO_4444.copyBuilder().serialNumber(0).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasMissingSenderId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are nft transfers, not hbar or fungible token transfers
                .nftTransfers(SERIAL_2_FROM_4444_TO_3333
                        .copyBuilder()
                        .senderAccountID((AccountID) null)
                        .build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasMissingReceiverId() {
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444
                        .copyBuilder()
                        .receiverAccountID((AccountID) null)
                        .build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasRepeatedNftId() {
        final var txn = newCryptoTransfer(
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                        .build(),
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .nftTransfers(SERIAL_2_FROM_4444_TO_3333)
                        .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST));
    }

    @Test
    void pureChecksHasValidNonFungibleTokenTransfers() {
        // Note: this test only checks for valid non-fungible token transfers (WITHOUT any hbar or fungible token
        // transfers)
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .nftTransfers(
                        SERIAL_1_FROM_3333_TO_4444,
                        SERIAL_2_FROM_4444_TO_3333,
                        SERIAL_1_FROM_3333_TO_4444.copyBuilder().serialNumber(3).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void pureChecksTokenTransfersDoesNotHaveFungibleOrNonFungibleAmount() {
        // This test checks that, if any token transfer is present, it must have at least one fungible or non-fungible
        // balance not equal to zero
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // transfers and nftTransfers are intentionally empty (will result in a count of zero transfers)
                .transfers()
                .nftTransfers()
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksTokenTransferHasBothFungibleAndNonFungibleAmounts() {
        // This test checks that, if a transfer for a token is present, it must have ONLY a fungible transfer OR an NFT
        // transfer, but not both
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444, SERIAL_1_FROM_3333_TO_4444)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksForEmptyHbarTransferAndEmptyTokenTransfers() {
        // It's actually valid to have no hbar transfers and no token transfers
        final var txn = newCryptoTransfer(Collections.emptyList(), Collections.emptyList());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void pureChecksHasValidHbarAndTokenTransfers() {
        // Tests that valid hbar transfers, fungible transfers, and non-fungible transfers are all valid when given
        // together
        final var token9753 = asToken(9753);
        final var token9754 = asToken(9754);
        final var txn = newCryptoTransfer(
                // Valid hbar transfers
                List.of(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10),
                List.of(
                        // Valid fungible token transfers
                        TokenTransferList.newBuilder()
                                .token(TOKEN_2468)
                                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(token9754)
                                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                                .build(),
                        // Valid nft token transfers
                        TokenTransferList.newBuilder()
                                .token(token9753)
                                .nftTransfers(SERIAL_1_FROM_3333_TO_4444, SERIAL_2_FROM_4444_TO_3333)
                                .build()));
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }
}
