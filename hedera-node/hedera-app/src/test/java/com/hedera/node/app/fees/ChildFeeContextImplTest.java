// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChildFeeContextImplTest {
    private static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567, 890);
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(666L).build();
    private static final TransactionBody SAMPLE_BODY = TransactionBody.newBuilder()
            .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                    .tokenTransfers(TokenTransferList.newBuilder()
                            .token(TokenID.newBuilder().tokenNum(666L).build())
                            .transfers(
                                    AccountAmount.newBuilder()
                                            .accountID(AccountID.newBuilder().accountNum(1234))
                                            .amount(-1000)
                                            .build(),
                                    AccountAmount.newBuilder()
                                            .accountID(AccountID.newBuilder().accountNum(5678))
                                            .amount(+1000)
                                            .build())
                            .build()))
            .build();

    @Mock
    private Authorizer authorizer;

    @Mock
    private FeeManager feeManager;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private FeeContext context;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private ReadableStoreFactory storeFactory;

    @Mock
    private AppKeyVerifier verifier;

    private ChildFeeContextImpl subject;

    @BeforeEach
    void setUp() {
        subject = new ChildFeeContextImpl(
                feeManager, context, SAMPLE_BODY, PAYER_ID, true, authorizer, storeFactory, NOW, verifier, 0);
    }

    @Test
    void returnsChildBody() {
        assertSame(SAMPLE_BODY, subject.body());
    }

    @Test
    void delegatesFeeCalculatorCreation() {
        given(feeManager.createFeeCalculator(
                        eq(SAMPLE_BODY),
                        eq(Key.DEFAULT),
                        eq(HederaFunctionality.CRYPTO_TRANSFER),
                        eq(0),
                        eq(0),
                        eq(NOW),
                        eq(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES),
                        eq(true),
                        any(ReadableStoreFactory.class)))
                .willReturn(feeCalculator);
        assertSame(
                feeCalculator,
                subject.feeCalculatorFactory().feeCalculator(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES));
    }

    @Test
    void propagatesInvalidBodyAsIllegalStateException() {
        subject = new ChildFeeContextImpl(
                feeManager,
                context,
                TransactionBody.DEFAULT,
                PAYER_ID,
                true,
                authorizer,
                storeFactory,
                NOW,
                verifier,
                0);
        assertThrows(IllegalStateException.class, () -> subject.feeCalculatorFactory()
                .feeCalculator(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES));
    }

    @Test
    void delegatesReadableStoreCreation() {
        given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        assertSame(readableAccountStore, subject.readableStore(ReadableAccountStore.class));
    }

    @Test
    void delegatesConfiguration() {
        given(context.configuration()).willReturn(DEFAULT_CONFIG);

        assertSame(DEFAULT_CONFIG, subject.configuration());
    }

    @Test
    void delegatesAuthorizer() {
        assertSame(authorizer, subject.authorizer());
    }
}
