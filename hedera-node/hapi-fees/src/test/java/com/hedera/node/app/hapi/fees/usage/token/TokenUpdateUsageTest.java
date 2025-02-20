// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.token;

import static com.hedera.node.app.hapi.fees.test.KeyUtils.A_KEY_LIST;
import static com.hedera.node.app.hapi.fees.test.KeyUtils.C_COMPLEX_KEY;
import static com.hedera.node.app.hapi.fees.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.anyLong;
import static org.mockito.BDDMockito.atMostOnce;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.test.KeyUtils;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenUpdateUsageTest {
    private final long maxLifetime = 100 * 365 * 24 * 60 * 60L;

    private final Key kycKey = KeyUtils.A_COMPLEX_KEY;
    private final Key adminKey = KeyUtils.A_THRESHOLD_KEY;
    private final Key freezeKey = KeyUtils.A_KEY_LIST;
    private final Key supplyKey = KeyUtils.B_COMPLEX_KEY;
    private final Key wipeKey = C_COMPLEX_KEY;
    private final long oldExpiry = 2_345_670L;
    private final long expiry = 2_345_678L;
    private final long absurdExpiry = oldExpiry + 2 * maxLifetime;
    private final long oldAutoRenewPeriod = 1_234_567L;
    private final long now = oldExpiry - oldAutoRenewPeriod;
    private final String oldSymbol = "ABC";
    private final String symbol = "ABCDEFGH";
    private final String oldName = "WhyWhy";
    private final String name = "WhyWhyWhy";
    private final String oldMemo = "Calm reigns";
    private final String memo = "Calamity strikes";
    private final int numSigs = 3;
    private final int sigSize = 100;
    private final int numPayerKeys = 1;
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
    private final AccountID treasury = IdUtils.asAccount("1.2.3");
    private final AccountID autoRenewAccount = IdUtils.asAccount("3.2.1");
    private final TokenID id = IdUtils.asToken("0.0.75231");

    private TokenUpdateTransactionBody op;
    private TransactionBody txn;

    private EstimatorFactory factory;
    private TxnUsageEstimator base;
    private TokenUpdateUsage subject;

    @BeforeEach
    void setUp() throws Exception {
        base = mock(TxnUsageEstimator.class);
        given(base.get()).willReturn(A_USAGES_MATRIX);

        factory = mock(EstimatorFactory.class);
        given(factory.get(any(), any(), any())).willReturn(base);
    }

    @Test
    void createsExpectedCappedLifetimeDeltaForNewLargerKeys() {
        // setup:
        final var curRb = curSize(A_KEY_LIST);
        final var newRb = newRb();
        final var expectedBytes = newRb + 3 * BASIC_ENTITY_ID_SIZE + 8;

        givenOp(absurdExpiry);
        // and:
        givenImpliedSubjectWithSmallerKeys();

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs((newRb - curRb) * maxLifetime);
        verify(base)
                .addRbs(TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 2, 0)
                        * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForNewLargerKeys() {
        // setup:
        final var curRb = curSize(A_KEY_LIST);
        final var newRb = newRb();
        final var expectedBytes = newRb + 3 * BASIC_ENTITY_ID_SIZE + 8;

        givenOp();
        // and:
        givenImpliedSubjectWithSmallerKeys();

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs((newRb - curRb) * (expiry - now));
        verify(base)
                .addRbs(TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 2, 0)
                        * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void createsExpectedDeltaForNewSmallerKeys() {
        // setup:
        final var newRb = newRb();
        final var expectedBytes = newRb + 3 * BASIC_ENTITY_ID_SIZE + 8;

        givenOp();
        // and:
        givenImpliedSubjectWithLargerKeys();

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base, atMostOnce()).addRbs(anyLong());
    }

    @Test
    void ignoresNewAutoRenewBytesIfAlreadyUsingAutoRenew() {
        // setup:
        final var curRb = curSize(A_KEY_LIST) + BASIC_ENTITY_ID_SIZE;
        final var newRb = newRb();
        final var expectedBytes = newRb + 3 * BASIC_ENTITY_ID_SIZE + 8;

        givenOp();
        // and:
        givenImpliedSubjectWithSmallerKeys();
        subject.givenCurrentlyUsingAutoRenewAccount();

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs((newRb - curRb) * (expiry - now));
        verify(base)
                .addRbs(TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 2, 0)
                        * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    @Test
    void understandsRemovingAutoRenew() {
        // setup:
        final var curRb = curSize(A_KEY_LIST) + BASIC_ENTITY_ID_SIZE;
        final var newRb = newRb() - BASIC_ENTITY_ID_SIZE;
        final var expectedBytes = newRb + 3 * BASIC_ENTITY_ID_SIZE + 8;

        givenOp();
        op = op.toBuilder().setAutoRenewAccount(AccountID.getDefaultInstance()).build();
        setTxn();
        // and:
        givenImpliedSubjectWithSmallerKeys();
        subject.givenCurrentlyUsingAutoRenewAccount();

        // when:
        final var actual = subject.get();

        // then:
        assertEquals(A_USAGES_MATRIX, actual);
        // and:
        verify(base).addBpt(expectedBytes);
        verify(base).addRbs((newRb - curRb) * (expiry - now));
        verify(base)
                .addRbs(TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 2, 0)
                        * USAGE_PROPERTIES.legacyReceiptStorageSecs());
    }

    private void givenImpliedSubjectWithLargerKeys() {
        givenImpliedSubjectWithKey(C_COMPLEX_KEY);
    }

    private void givenImpliedSubjectWithSmallerKeys() {
        givenImpliedSubjectWithKey(KeyUtils.A_KEY_LIST);
    }

    private void givenImpliedSubjectWithKey(final Key oldKey) {
        givenImpliedSubjectWithExpiryAndKey(oldExpiry, oldKey);
    }

    private void givenImpliedSubjectWithExpiryAndKey(final long extantExpiry, final Key oldKey) {
        subject = TokenUpdateUsage.newEstimate(txn, base)
                .givenCurrentExpiry(extantExpiry)
                .givenCurrentMemo(oldMemo)
                .givenCurrentName(oldName)
                .givenCurrentSymbol(oldSymbol)
                .givenCurrentAdminKey(Optional.of(oldKey))
                .givenCurrentKycKey(Optional.of(oldKey))
                .givenCurrentSupplyKey(Optional.of(oldKey))
                .givenCurrentWipeKey(Optional.of(oldKey))
                .givenCurrentFreezeKey(Optional.of(oldKey))
                .givenCurrentPauseKey(Optional.of(oldKey))
                .givenCurrentFeeScheduleKey(Optional.of(oldKey));
    }

    private long curSize(final Key oldKey) {
        return oldSymbol.length()
                + oldName.length()
                + oldMemo.length()
                + 7 * FeeBuilder.getAccountKeyStorageSize(oldKey);
    }

    private long newRb() {
        return symbol.length()
                + name.length()
                + memo.length()
                + FeeBuilder.getAccountKeyStorageSize(adminKey)
                + FeeBuilder.getAccountKeyStorageSize(kycKey)
                + FeeBuilder.getAccountKeyStorageSize(wipeKey)
                + FeeBuilder.getAccountKeyStorageSize(supplyKey)
                + FeeBuilder.getAccountKeyStorageSize(freezeKey)
                + BASIC_ENTITY_ID_SIZE;
    }

    private void givenOp() {
        givenOp(expiry);
    }

    private void givenOp(final long newExpiry) {
        op = TokenUpdateTransactionBody.newBuilder()
                .setToken(id)
                .setMemo(StringValue.newBuilder().setValue(memo).build())
                .setExpiry(Timestamp.newBuilder().setSeconds(newExpiry))
                .setTreasury(treasury)
                .setAutoRenewAccount(autoRenewAccount)
                .setSymbol(symbol)
                .setName(name)
                .setKycKey(kycKey)
                .setAdminKey(adminKey)
                .setFreezeKey(freezeKey)
                .setSupplyKey(supplyKey)
                .setWipeKey(wipeKey)
                .build();
        setTxn();
    }

    private void setTxn() {
        txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setTokenUpdate(op)
                .build();
    }
}
