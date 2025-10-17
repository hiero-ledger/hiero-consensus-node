// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.IndirectKey;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoUpdateHandler;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoUpdateIndirectKeyFeeTest {
    private static final AccountID ACCOUNT_1 = AccountID.newBuilder().accountNum(1L).build();

    private CryptoUpdateHandler subject;

    @Mock private FeeContext feeContext;
    @Mock private FeeCalculatorFactory feeCalculatorFactory;
    @Mock private FeeCalculator feeCalculator;
    @Mock private ReadableAccountStore accountStore;

    private Configuration config;

    @BeforeEach
    void setUp() {
        subject = new CryptoUpdateHandler(mock(com.hedera.node.app.service.token.CryptoSignatureWaivers.class));
    }

    @Test
    void addsFeeWhenIndirectKeysIncrease() {
        // Given configuration with per-occurrence price in tinycents
        config = HederaTestConfigBuilder.create()
                .withValue("fees.indirectKeyExtraTinycents", 10)
                .getOrCreateConfig();

        // Existing account has no indirect keys
        final var existing = Account.newBuilder().accountId(ACCOUNT_1).key(Key.DEFAULT).build();
        given(accountStore.getAccountById(ACCOUNT_1)).willReturn(existing);

        // New key has two indirect keys
        final var newKey = keyList(indirectTo(ACCOUNT_1), indirectTo(ACCOUNT_1));

        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(ACCOUNT_1)
                        .transactionValidStart(com.hedera.hapi.node.base.Timestamp.newBuilder().seconds(1)))
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                        .accountIDToUpdate(ACCOUNT_1)
                        .key(newKey))
                .build();

        given(feeContext.body()).willReturn(txn);
        given(feeContext.configuration()).willReturn(config);
        given(feeContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.addBytesPerTransaction(anyLong())).willReturn(feeCalculator);
        given(feeCalculator.addRamByteSeconds(anyLong())).willReturn(feeCalculator);
        // Base fees are FREE
        given(feeCalculator.calculate()).willReturn(Fees.FREE);
        // Two occurrences * 10 tinycents = 20 tinycents -> convert via calculator helper
        given(feeCalculator.tinybarsFromTinycents(20L)).willReturn(7L);

        // When
        final var fees = subject.calculateFees(feeContext);

        // Then: only service fee increased by 7 tinybars
        assertThat(fees).isEqualTo(new Fees(0L, 0L, 7L));
    }

    @Test
    void noExtraFeeWhenIndirectKeysDecreaseOrSame() {
        // Given configuration with per-occurrence price in tinycents
        config = HederaTestConfigBuilder.create()
                .withValue("fees.indirectKeyExtraTinycents", 10)
                .getOrCreateConfig();

        // Existing account has two indirect keys
        final var existing = Account.newBuilder().accountId(ACCOUNT_1).key(keyList(indirectTo(ACCOUNT_1), indirectTo(ACCOUNT_1))).build();
        given(accountStore.getAccountById(ACCOUNT_1)).willReturn(existing);

        // New key removes both indirect keys (zero occurrences)
        final var newKey = Key.DEFAULT;

        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(ACCOUNT_1)
                        .transactionValidStart(com.hedera.hapi.node.base.Timestamp.newBuilder().seconds(1)))
                .cryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                        .accountIDToUpdate(ACCOUNT_1)
                        .key(newKey))
                .build();

        given(feeContext.body()).willReturn(txn);
        given(feeContext.configuration()).willReturn(config);
        given(feeContext.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.addBytesPerTransaction(anyLong())).willReturn(feeCalculator);
        given(feeCalculator.addRamByteSeconds(anyLong())).willReturn(feeCalculator);
        // Base fees are FREE
        given(feeCalculator.calculate()).willReturn(Fees.FREE);

        // When
        final var fees = subject.calculateFees(feeContext);

        // Then: no extra fee applied when delta <= 0
        assertThat(fees).isEqualTo(Fees.FREE);
    }

    private static Key keyList(final Key... keys) {
        return Key.newBuilder().keyList(KeyList.newBuilder().keys(keys)).build();
    }

    private static Key indirectTo(final AccountID id) {
        return Key.newBuilder().indirectKey(IndirectKey.newBuilder().accountId(id).build()).build();
    }
}

