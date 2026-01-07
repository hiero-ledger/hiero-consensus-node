// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CryptoTransferValidatorTest {

    private static final long FEE_COLLECTION_ACCOUNT_NUM = 802L;
    private static final AccountID FEE_COLLECTION_ACCOUNT =
            AccountID.newBuilder().accountNum(FEE_COLLECTION_ACCOUNT_NUM).build();
    private static final AccountID ACCOUNT_3333 =
            AccountID.newBuilder().accountNum(3333).build();
    private static final AccountID ACCOUNT_4444 =
            AccountID.newBuilder().accountNum(4444).build();
    private static final TokenID TOKEN_1234 =
            TokenID.newBuilder().tokenNum(1234).build();

    private CryptoTransferValidator subject;
    private Configuration config = HederaTestConfigBuilder.create()
            .withValue("accounts.feeCollectionAccount", FEE_COLLECTION_ACCOUNT_NUM)
            .getOrCreateConfig();

    @BeforeEach
    void setUp() {
        final var entityIdFactory = new FakeEntityIdFactoryImpl(0, 0);
        subject = new CryptoTransferValidator(entityIdFactory);
    }

    @Nested
    @DisplayName("Fee Collection Account Validation Tests")
    class FeeCollectionAccountValidationTests {
        @Test
        @DisplayName("Should reject fungible token credit to fee collection account")
        void rejectsFungibleTokenCreditToFeeCollectionAccount() {
            final var tokenTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_3333)
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(FEE_COLLECTION_ACCOUNT)
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer)
                    .build();

            assertThatThrownBy(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED));
        }

        @Test
        @DisplayName("Should reject NFT transfer to fee collection account")
        void rejectsNftTransferToFeeCollectionAccount() {
            final var tokenTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .nftTransfers(NftTransfer.newBuilder()
                            .senderAccountID(ACCOUNT_3333)
                            .receiverAccountID(FEE_COLLECTION_ACCOUNT)
                            .serialNumber(1L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer)
                    .build();

            assertThatThrownBy(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED));
        }

        @Test
        @DisplayName("Should allow fungible token debit from fee collection account")
        void allowsFungibleTokenDebitFromFeeCollectionAccount() {
            final var tokenTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(FEE_COLLECTION_ACCOUNT)
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_3333)
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer)
                    .build();

            assertThatCode(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should allow NFT transfer from fee collection account")
        void allowsNftTransferFromFeeCollectionAccount() {
            final var tokenTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .nftTransfers(NftTransfer.newBuilder()
                            .senderAccountID(FEE_COLLECTION_ACCOUNT)
                            .receiverAccountID(ACCOUNT_3333)
                            .serialNumber(1L)
                            .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer)
                    .build();

            assertThatCode(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should allow transfers between non-fee-collection accounts")
        void allowsTransfersBetweenNonFeeCollectionAccounts() {
            final var tokenTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_3333)
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_4444)
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer)
                    .build();

            assertThatCode(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should allow zero amount to fee collection account")
        void allowsZeroAmountToFeeCollectionAccount() {
            final var tokenTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_3333)
                                    .amount(0L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(FEE_COLLECTION_ACCOUNT)
                                    .amount(0L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(tokenTransfer)
                    .build();

            assertThatCode(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should allow empty token transfers")
        void allowsEmptyTokenTransfers() {
            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(TransferList.newBuilder()
                            .accountAmounts(
                                    AccountAmount.newBuilder()
                                            .accountID(ACCOUNT_3333)
                                            .amount(-100L)
                                            .build(),
                                    AccountAmount.newBuilder()
                                            .accountID(ACCOUNT_4444)
                                            .amount(100L)
                                            .build())
                            .build())
                    .build();

            assertThatCode(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should reject multiple token transfers where one credits fee collection account")
        void rejectsMultipleTokenTransfersWithFeeCollectionCredit() {
            final var validTransfer = TokenTransferList.newBuilder()
                    .token(TOKEN_1234)
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_3333)
                                    .amount(-50L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_4444)
                                    .amount(50L)
                                    .build())
                    .build();

            final var invalidTransfer = TokenTransferList.newBuilder()
                    .token(TokenID.newBuilder().tokenNum(5678).build())
                    .transfers(
                            AccountAmount.newBuilder()
                                    .accountID(ACCOUNT_3333)
                                    .amount(-100L)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(FEE_COLLECTION_ACCOUNT)
                                    .amount(100L)
                                    .build())
                    .build();

            final var op = CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(validTransfer, invalidTransfer)
                    .build();

            assertThatThrownBy(() ->
                            subject.validateSemantics(op, config, HandleContext.TransactionCategory.USER, ACCOUNT_3333))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TRANSFER_TO_FEE_COLLECTION_ACCOUNT_NOT_ALLOWED));
        }
    }
}
