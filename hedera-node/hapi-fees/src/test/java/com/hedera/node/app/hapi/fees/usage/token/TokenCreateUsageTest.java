// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenCreateUsageTest {
    private final long maxLifetime = 100 * 365 * 24 * 60 * 60L;

    private final Key kycKey = KeyUtils.A_COMPLEX_KEY;
    private final Key adminKey = KeyUtils.A_THRESHOLD_KEY;
    private final Key freezeKey = KeyUtils.A_KEY_LIST;
    private final Key supplyKey = KeyUtils.B_COMPLEX_KEY;
    private final Key wipeKey = KeyUtils.C_COMPLEX_KEY;
    private final Key customFeeKey = KeyUtils.A_THRESHOLD_KEY;
    private final long expiry = 2_345_678L;
    private final long autoRenewPeriod = 1_234_567L;
    private final long now = expiry - autoRenewPeriod;
    private final String symbol = "ABCDEFGH";
    private final String name = "WhyWhyWHy";
    private final String memo = "Cellar door";
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
    private final AccountID autoRenewAccount = IdUtils.asAccount("0.0.75231");

    private final TokenOpsUsage tokenOpsUsage = new TokenOpsUsage();
    private TokenCreateTransactionBody op;
    private TransactionBody txn;

    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private TokenCreateUsage subject;

    @BeforeEach
    void setUp() throws Exception {
        base = mock(TxnUsageEstimator.class);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
    }

    @Test
    void createsExpectedDeltaForExpiryBasedFungibleCommon() {
        // setup:
        final var expectedBytes = baseSize();

        givenExpiryBasedOp(TokenType.FUNGIBLE_COMMON);
        given(base.get(SubType.TOKEN_FUNGIBLE_COMMON)).willReturn(A_USAGES_MATRIX);
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs(expectedBytes * autoRenewPeriod);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForExpiryBasedFungibleCommonWithCustomFee() {
        // setup:
        final var expectedBytes = baseSize();

        givenExpiryBasedOp(expiry, TokenType.FUNGIBLE_COMMON, false, true);
        given(base.get(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES)).willReturn(A_USAGES_MATRIX);
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs((expectedBytes + tokenOpsUsage.bytesNeededToRepr(op.customFees())) * autoRenewPeriod);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForExpiryBasedNonfungibleUnique() {
        // setup:
        final var expectedBytes = baseSize();

        givenExpiryBasedOp(TokenType.NON_FUNGIBLE_UNIQUE);
        given(base.get(SubType.TOKEN_NON_FUNGIBLE_UNIQUE)).willReturn(A_USAGES_MATRIX);
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs(expectedBytes * autoRenewPeriod);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForExpiryBasedNonfungibleUniqueWithScheduleKey() {
        // setup:
        final var expectedBytes = baseSizeWith(true);

        givenExpiryBasedOp(TokenType.NON_FUNGIBLE_UNIQUE, true);
        given(base.get(SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)).willReturn(A_USAGES_MATRIX);
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs(expectedBytes * autoRenewPeriod);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForExpiryBasedNonfungibleUniqueWithScheduleOnlyNoKey() {
        // setup:
        final var expectedBytes = baseSizeWith(false);

        givenExpiryBasedOp(expiry, TokenType.NON_FUNGIBLE_UNIQUE, false, true);
        given(base.get(SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES)).willReturn(A_USAGES_MATRIX);
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs((expectedBytes + tokenOpsUsage.bytesNeededToRepr(op.customFees())) * autoRenewPeriod);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedCappedDeltaForExpiryBasedFungibleCommon() {
        // setup:
        final var expectedBytes = baseSize();

        given(base.get(SubType.TOKEN_FUNGIBLE_COMMON)).willReturn(A_USAGES_MATRIX);
        givenExpiryBasedOp(expiry + 2 * maxLifetime, TokenType.FUNGIBLE_COMMON, false, false);
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs(expectedBytes * maxLifetime);
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForAutoRenewBased() {
        // setup:
        final var expectedBytes = baseSize() + BASIC_ENTITY_ID_SIZE;

        given(base.get(SubType.TOKEN_FUNGIBLE_COMMON)).willReturn(A_USAGES_MATRIX);
        givenAutoRenewBasedOp();
        // and:
        subject = TokenCreateUsage.newEstimate(txn, base);

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs(expectedBytes * autoRenewPeriod);
        verify(base)
                .addRbs(TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 1, 0)
                        * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        verify(base).addNetworkRbs(BASIC_ENTITY_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    private long baseSize() {
        return baseSizeWith(false);
    }

    private long baseSizeWith(final boolean customFeesKey) {
        return TOKEN_ENTITY_SIZES.totalBytesInTokenReprGiven(symbol, name)
                + FeeBuilder.getAccountKeyStorageSize(kycKey)
                + FeeBuilder.getAccountKeyStorageSize(adminKey)
                + FeeBuilder.getAccountKeyStorageSize(wipeKey)
                + FeeBuilder.getAccountKeyStorageSize(freezeKey)
                + FeeBuilder.getAccountKeyStorageSize(supplyKey)
                + memo.length()
                + (customFeesKey ? FeeBuilder.getAccountKeyStorageSize(customFeeKey) : 0);
    }

    private void givenExpiryBasedOp(final TokenType type) {
        givenExpiryBasedOp(expiry, type, false, false);
    }

    private void givenExpiryBasedOp(final TokenType type, final boolean withCustomFees) {
        givenExpiryBasedOp(expiry, type, withCustomFees, false);
    }

    private void givenExpiryBasedOp(
            final long newExpiry, final TokenType type, final boolean withCustomFeesKey, final boolean withCustomFees) {
        final var builder = TokenCreateTransactionBody.newBuilder()
                .tokenType(type)
                .expiry(Timestamp.newBuilder().seconds(newExpiry))
                .symbol(symbol)
                .memo(memo)
                .name(name)
                .kycKey(kycKey)
                .adminKey(adminKey)
                .freezeKey(freezeKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey);
        if (withCustomFeesKey) {
            builder.feeScheduleKey(customFeeKey);
        }
        if (withCustomFees) {
            builder.customFees(CustomFee.newBuilder()
                    .feeCollectorAccountId(IdUtils.asAccount("0.0.1234"))
                    .fixedFee(FixedFee.newBuilder().amount(123))
                    .build());
        }
        op = builder.build();
        setTxn();
    }

    private void givenAutoRenewBasedOp() {
        op = TokenCreateTransactionBody.newBuilder()
                .autoRenewAccount(autoRenewAccount)
                .memo(memo)
                .autoRenewPeriod(Duration.newBuilder().seconds(autoRenewPeriod))
                .symbol(symbol)
                .name(name)
                .kycKey(kycKey)
                .adminKey(adminKey)
                .freezeKey(freezeKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey)
                .initialSupply(1)
                .build();
        setTxn();
    }

    private void setTxn() {
        txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now)))
                .tokenCreation(op)
                .build();
    }
}
