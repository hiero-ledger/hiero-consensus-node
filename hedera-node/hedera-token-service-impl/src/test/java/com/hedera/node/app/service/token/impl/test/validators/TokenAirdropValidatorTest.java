// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.validators;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.validators.TokenAirdropValidator;
import org.junit.jupiter.api.Test;

class TokenAirdropValidatorTest {
    private static final AccountID ACCOUNT_1 = accountId(1);
    private static final AccountID ACCOUNT_2 = accountId(2);
    private static final AccountID ACCOUNT_3 = accountId(3);
    private static final AccountID ACCOUNT_4 = accountId(4);
    private static final AccountID ACCOUNT_5 = accountId(5);
    private static final TokenID TOKEN_1 = TokenID.newBuilder().tokenNum(1).build();

    private final TokenAirdropValidator subject = new TokenAirdropValidator();

    @Test
    void acceptsZeroSumFungibleChangesWithOneTokenDebit() {
        final var op = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjustment(ACCOUNT_1, -1), adjustment(ACCOUNT_2, 1))
                        .build())
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(TOKEN_1)
                        .transfers(adjustment(ACCOUNT_1, -2), adjustment(ACCOUNT_2, 1), adjustment(ACCOUNT_3, 1))
                        .build())
                .build();

        assertThatCode(() -> subject.validatePostCustomFeeAssessment(op)).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonZeroSumHbarChanges() {
        final var op = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(adjustment(ACCOUNT_1, -1), adjustment(ACCOUNT_2, 2))
                        .build())
                .build();

        assertThatThrownBy(() -> subject.validatePostCustomFeeAssessment(op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Custom fee assessment returned non-zero-sum HBAR adjustments with net 1");
    }

    @Test
    void rejectsNonZeroSumTokenChanges() {
        final var op = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(TOKEN_1)
                        .transfers(adjustment(ACCOUNT_1, -1), adjustment(ACCOUNT_2, 2))
                        .build())
                .build();

        assertThatThrownBy(() -> subject.validatePostCustomFeeAssessment(op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom fee assessment returned non-zero-sum adjustments for token")
                .hasMessageContaining("with net 1");
    }

    @Test
    void rejectsMultipleTokenDebitsEvenWhenChangesAreZeroSum() {
        final var op = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(TOKEN_1)
                        .transfers(adjustment(ACCOUNT_1, -1), adjustment(ACCOUNT_2, -1), adjustment(ACCOUNT_3, 2))
                        .build())
                .build();

        assertThatThrownBy(() -> subject.validatePostCustomFeeAssessment(op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom fee assessment returned 2 debits for token")
                .hasMessageContaining("expected exactly one");
    }

    @Test
    void rejectsChangesWhoseLongSumWrapsToZero() {
        final var op = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(TOKEN_1)
                        .transfers(
                                adjustment(ACCOUNT_1, Long.MIN_VALUE),
                                adjustment(ACCOUNT_2, Long.MAX_VALUE),
                                adjustment(ACCOUNT_3, Long.MAX_VALUE),
                                adjustment(ACCOUNT_4, Long.MAX_VALUE),
                                adjustment(ACCOUNT_5, 3))
                        .build())
                .build();

        assertThatThrownBy(() -> subject.validatePostCustomFeeAssessment(op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom fee assessment returned non-zero-sum adjustments for token")
                .hasMessageContaining("with net 18446744073709551616");
    }

    @Test
    void rejectsLongMinValueDebitThatCannotBeSafelyReconstructed() {
        final var op = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(TOKEN_1)
                        .transfers(
                                adjustment(ACCOUNT_1, Long.MIN_VALUE),
                                adjustment(ACCOUNT_2, Long.MAX_VALUE),
                                adjustment(ACCOUNT_3, 1))
                        .build())
                .build();

        assertThatThrownBy(() -> subject.validatePostCustomFeeAssessment(op))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom fee assessment returned a Long.MIN_VALUE debit for token")
                .hasMessageContaining("cannot be safely reconstructed from its credits");
    }

    private static AccountID accountId(final long accountNum) {
        return AccountID.newBuilder().accountNum(accountNum).build();
    }

    private static AccountAmount adjustment(final AccountID accountId, final long amount) {
        return AccountAmount.newBuilder().accountID(accountId).amount(amount).build();
    }
}
