// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_REFERENCE_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_REFERENCE_REPEATED;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.TokenReference;
import com.hedera.hapi.node.token.TokenRejectTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.FeeContextImpl;
import com.hedera.node.app.service.token.impl.handlers.TokenRejectHandler;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TokenRejectHandlerTest extends CryptoTransferHandlerTestBase {
    public static final String LEDGER_TOKEN_REJECTS_MAX_LEN = "ledger.tokenRejects.maxLen";
    public static final String TOKEN_REJECT_ENABLED_PROPERTY = "tokens.reject.enabled";

    private final TokenReference tokenRefFungible =
            TokenReference.newBuilder().fungibleToken(fungibleTokenId).build();
    private final TokenReference tokenRefNFT =
            TokenReference.newBuilder().nft(nftIdSl1).build();

    private Configuration config;

    private TokenRejectHandler subject;

    @Mock
    private PureChecksContext pureChecksContext;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        subject = new TokenRejectHandler();
    }

    @Test
    void handleNullArgs() {
        Assertions.assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void happyPathWorks() {
        // Given:
        refreshWritableStores();
        final var txn = newTokenReject(ownerId, tokenRefFungible, tokenRefNFT);
        given(handleContext.body()).willReturn(txn);
        givenStoresAndConfig(handleContext);
        given(handleContext.payer()).willReturn(ownerId);
        given(handleContext.body()).willReturn(txn);
        when(handleContext.configuration()).thenReturn(defaultConfig().getOrCreateConfig());
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(configProvider.getConfiguration()).willReturn(versionedConfig);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        final var initialSenderBalance = writableAccountStore.get(ownerId).tinybarBalance();
        final var initialSenderTokenBalance =
                writableTokenRelStore.get(ownerId, fungibleTokenId).balance();
        final var initialTreasuryTokenBalance =
                writableTokenRelStore.get(treasuryId, fungibleTokenId).balance();
        final var initialFeeCollectorBalance =
                writableAccountStore.get(feeCollectorId).tinybarBalance();

        // When:
        subject.handle(handleContext);

        // Then:
        assertThat(writableAccountStore.modifiedAccountsInState()).hasSize(2);
        assertThat(writableAccountStore.modifiedAccountsInState()).contains(ownerId, treasuryId);

        // Verify balance removal
        final var endSenderTokenBalance =
                writableTokenRelStore.get(ownerId, fungibleTokenId).balance();
        assertThat(endSenderTokenBalance).isZero();
        final var endTreasuryTokenBalance =
                writableTokenRelStore.get(treasuryId, fungibleTokenId).balance();
        assertThat(endTreasuryTokenBalance).isEqualTo(initialSenderTokenBalance + initialTreasuryTokenBalance);

        // No fees collected for token transfers
        final var endSenderBalance = writableAccountStore.get(ownerId).tinybarBalance();
        assertThat(endSenderBalance).isEqualTo(initialSenderBalance);
        final var feeCollectorBalance = writableAccountStore.get(feeCollectorId).tinybarBalance();
        assertThat(feeCollectorBalance).isEqualTo(initialFeeCollectorBalance);
    }

    @Test
    void calculateFeesFungibleReject() {
        final var txn = newTokenReject(ACCOUNT_ID_3333, tokenRefFungible);

        FeeContext feeContext = mock(FeeContextImpl.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        setupFeeTest(txn, feeContext, feeCalculator, feeCalculatorFactory);

        subject.calculateFees(feeContext);

        // Not interested in return value from calculate, just that it was called and bpt and rbs were set appropriately
        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory, times(1)).feeCalculator(SubType.TOKEN_FUNGIBLE_COMMON);
        inOrder.verify(feeCalculator, times(1)).addBytesPerTransaction(176L);
        inOrder.verify(feeCalculator, times(1)).addRamByteSeconds(176L * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        inOrder.verify(feeCalculator, times(1)).calculate();
    }

    @Test
    void calculateFeesNonFungibleReject() {
        final var txn = newTokenReject(ACCOUNT_ID_3333, tokenRefNFT);
        FeeContext feeContext = mock(FeeContextImpl.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        setupFeeTest(txn, feeContext, feeCalculator, feeCalculatorFactory);

        subject.calculateFees(feeContext);

        // Not interested in return value from calculate, just that it was called and bpt and rbs were set appropriately
        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory, times(1)).feeCalculator(SubType.TOKEN_NON_FUNGIBLE_UNIQUE);
        inOrder.verify(feeCalculator, times(1)).addBytesPerTransaction(104L);
        inOrder.verify(feeCalculator, times(1)).addRamByteSeconds(104L * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        inOrder.verify(feeCalculator, times(1)).calculate();
    }

    @Test
    void calculateFeesFungibleAndNonFungibleRejects() {
        final var txn = newTokenReject(ACCOUNT_ID_3333, tokenRefNFT, tokenRefFungible);
        FeeContext feeContext = mock(FeeContextImpl.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        setupFeeTest(txn, feeContext, feeCalculator, feeCalculatorFactory);

        subject.calculateFees(feeContext);

        // Not interested in return value from calculate, just that it was called and bpt and rbs were set appropriately
        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory, times(1)).feeCalculator(SubType.TOKEN_NON_FUNGIBLE_UNIQUE);
        inOrder.verify(feeCalculator, times(1)).addBytesPerTransaction(280L);
        inOrder.verify(feeCalculator, times(1)).addRamByteSeconds(280L * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        inOrder.verify(feeCalculator, times(1)).calculate();
    }

    @Test
    void handleExceedsMaxTokenReferences() {
        config = defaultConfig().withValue(LEDGER_TOKEN_REJECTS_MAX_LEN, 1).getOrCreateConfig();
        final var txn = newTokenReject(ACCOUNT_ID_3333, tokenRefFungible, tokenRefNFT);
        final var context = mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_REFERENCE_LIST_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void handleRepeatedTokenReferences() {
        final var txn = newTokenReject(ACCOUNT_ID_3333, tokenRefFungible, tokenRefFungible);
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TOKEN_REFERENCE_REPEATED));
    }

    @Test
    void handleRejectsTransactionWithInvalidNftId() {
        final var invalidNftRef = TokenReference.newBuilder()
                .nft(NftID.newBuilder()
                        .tokenId(nonFungibleTokenId)
                        .serialNumber(0)
                        .build())
                .build();
        final var txn = newTokenReject(ACCOUNT_ID_3333, invalidNftRef);
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void handleRejectsTransactionWithEmptyRejections() {
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                .tokenReject(TokenRejectTransactionBody.newBuilder()
                        .owner(ACCOUNT_ID_3333)
                        .build())
                .build();
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_TOKEN_REFERENCE_LIST));
    }

    private void setupFeeTest(
            final TransactionBody txn,
            final FeeContext feeContext,
            final FeeCalculator feeCalculator,
            final FeeCalculatorFactory feeCalculatorFactory) {
        config = defaultConfig()
                .withValue("fees.tokenTransferUsageMultiplier", 2)
                .getOrCreateConfig();
        Fees fees = mock(Fees.class);
        when(feeContext.body()).thenReturn(txn);
        when(feeContext.configuration()).thenReturn(config);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(any())).thenReturn(feeCalculator);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(fees);
    }

    private HandleContext mockContext(final TransactionBody txn) {
        final var context = mock(HandleContext.class);
        given(context.configuration()).willReturn(config);
        given(context.body()).willReturn(txn);
        return context;
    }

    private static TestConfigBuilder defaultConfig() {
        return HederaTestConfigBuilder.create()
                .withValue(TOKEN_REJECT_ENABLED_PROPERTY, "true")
                .withValue(LEDGER_TOKEN_REJECTS_MAX_LEN, 10);
    }

    private TransactionBody newTokenReject(final AccountID payerId, final TokenReference... tokenReferences) {
        return newTokenReject(Arrays.stream(tokenReferences).toList(), payerId);
    }

    private TransactionBody newTokenReject(final List<TokenReference> tokenReferences, final AccountID payerId) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).transactionValidStart(consensusTimestamp))
                .tokenReject(TokenRejectTransactionBody.newBuilder().rejections(tokenReferences))
                .build();
    }
}
