// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWith;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.transfer.AssociateTokenRecipientsStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.EnsureAliasesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.ReplaceAliasesWithIDsInOp;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.WarmupContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.handle.DispatchHandleContext;
import com.hedera.node.app.workflows.handle.cache.CacheWarmer;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoTransferHandlerTest extends CryptoTransferHandlerTestBase {
    @Mock
    private CryptoTransferStreamBuilder transferRecordBuilder;

    private static final TokenID TOKEN_1357 = asToken(1357);
    private static final TokenID TOKEN_9191 = asToken(9191);
    private Configuration config;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        subject = new CryptoTransferHandler(validator);
    }

    @Test
    void warmTestNullContext() {
        Assertions.assertThatThrownBy(() -> subject.warm(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void warmTestAllAccountsTransferList() {
        ReadableStoreFactory storeFactory = mock(ReadableStoreFactory.class);
        ReadableAccountStore readableAccountStore = mock(ReadableAccountStore.class);

        TransactionBody txn = newCryptoTransfer(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10);

        WarmupContext warmupContext = new CacheWarmer.WarmupContextImpl(txn, storeFactory);
        when(storeFactory.getStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);

        subject.warm(warmupContext);

        verify(readableAccountStore, times(1)).warm(ACCOUNT_ID_3333);
        verify(readableAccountStore, times(1)).warm(ACCOUNT_ID_4444);
    }

    @Test
    void warmTokenDataTransferList() {
        ReadableStoreFactory storeFactory = mock(ReadableStoreFactory.class);
        ReadableAccountStore readableAccountStore = mock(ReadableAccountStore.class);
        ReadableTokenStore readableTokenStore = mock(ReadableTokenStore.class);
        ReadableNftStore readableNftStore = mock(ReadableNftStore.class);
        ReadableTokenRelationStore readableTokenRelationStore = mock(ReadableTokenRelationStore.class);
        Account account = mock(Account.class);
        Nft nft = nftSl1;

        TransactionBody txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444
                        .copyBuilder()
                        .receiverAccountID((AccountID) null)
                        .build())
                .build());

        WarmupContext warmupContext = new CacheWarmer.WarmupContextImpl(txn, storeFactory);
        when(storeFactory.getStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(storeFactory.getStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(storeFactory.getStore(ReadableNftStore.class)).thenReturn(readableNftStore);
        when(storeFactory.getStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelationStore);
        when(readableNftStore.get(TOKEN_2468, 1L)).thenReturn(nft);
        when(readableAccountStore.getAliasedAccountById(any(AccountID.class))).thenReturn(account);

        subject.warm(warmupContext);

        verify(readableTokenRelationStore, times(1)).warm(ACCOUNT_ID_3333, TOKEN_2468);
        verify(readableNftStore, times(1)).warm(any());
    }

    @Test
    void calculateFeesHbarTransfer() {
        config = defaultConfig()
                .withValue("fees.tokenTransferUsageMultiplier", 1)
                .getOrCreateConfig();
        List<AccountAmount> acctAmounts = new ArrayList<>();
        List<TokenTransferList> tokenTransferLists = new ArrayList<>();
        acctAmounts.add(aaWith(ACCOUNT_ID_3333, -5));
        acctAmounts.add(aaWith(ACCOUNT_ID_4444, 5));

        CryptoTransferTransactionBody cryptoTransfer = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder().accountAmounts(acctAmounts))
                .tokenTransfers(tokenTransferLists)
                .build();

        FeeContext feeContext = mock(DispatchHandleContext.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        Fees fees = mock(Fees.class);

        when(feeContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                        .cryptoTransfer(cryptoTransfer)
                        .build());
        when(feeContext.configuration()).thenReturn(config);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(any())).thenReturn(feeCalculator);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(fees);

        subject.calculateFees(feeContext);

        // Not interested in return value from calculate, just that it was called and bpt and rbs were set appropriately
        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory, times(1)).feeCalculator(SubType.DEFAULT);
        inOrder.verify(feeCalculator, times(1)).addBytesPerTransaction(64L);
        inOrder.verify(feeCalculator, times(1)).addRamByteSeconds(64L * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        inOrder.verify(feeCalculator, times(1)).calculate();
    }

    @Test
    void calculateFeesFtCustomFeesTransfer() {
        config = defaultConfig()
                .withValue("fees.tokenTransferUsageMultiplier", 2)
                .getOrCreateConfig();
        List<AccountAmount> acctAmounts = new ArrayList<>();
        List<TokenTransferList> tokenTransferLists = new ArrayList<>();
        tokenTransferLists.add(TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(
                        AccountAmount.newBuilder()
                                .accountID(ACCOUNT_ID_3333)
                                .amount(-5)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(ACCOUNT_ID_4444)
                                .amount(5)
                                .build())
                .build());

        CryptoTransferTransactionBody cryptoTransfer = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder().accountAmounts(acctAmounts))
                .tokenTransfers(tokenTransferLists)
                .build();

        FeeContext feeContext = mock(DispatchHandleContext.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        Fees fees = mock(Fees.class);

        when(feeContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                        .cryptoTransfer(cryptoTransfer)
                        .build());
        when(feeContext.configuration()).thenReturn(config);
        when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);
        when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(any())).thenReturn(feeCalculator);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(fees);

        subject.calculateFees(feeContext);

        // Not interested in return value from calculate, just that it was called and bpt and rbs were set appropriately
        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory, times(1)).feeCalculator(SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        inOrder.verify(feeCalculator, times(1)).addBytesPerTransaction(176L);
        inOrder.verify(feeCalculator, times(1)).addRamByteSeconds(320L * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        inOrder.verify(feeCalculator, times(1)).calculate();
    }

    @Test
    void calculateFeesNftCustomFeesTransfer() {
        config = defaultConfig()
                .withValue("fees.tokenTransferUsageMultiplier", 2)
                .getOrCreateConfig();
        List<AccountAmount> acctAmounts = new ArrayList<>();
        List<TokenTransferList> tokenTransferLists = new ArrayList<>();
        tokenTransferLists.add(TokenTransferList.newBuilder()
                .token(nonFungibleTokenId)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                .build());

        CryptoTransferTransactionBody cryptoTransfer = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder().accountAmounts(acctAmounts))
                .tokenTransfers(tokenTransferLists)
                .build();

        FeeContext feeContext = mock(DispatchHandleContext.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        Fees fees = mock(Fees.class);

        when(feeContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                        .cryptoTransfer(cryptoTransfer)
                        .build());
        when(feeContext.configuration()).thenReturn(config);
        when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);
        when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(any())).thenReturn(feeCalculator);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(fees);

        subject.calculateFees(feeContext);

        // Not interested in return value from calculate, just that it was called and bpt and rbs were set appropriately
        InOrder inOrder = inOrder(feeCalculatorFactory, feeCalculator);
        inOrder.verify(feeCalculatorFactory, times(1))
                .feeCalculator(SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
        inOrder.verify(feeCalculator, times(1)).addBytesPerTransaction(104L);
        inOrder.verify(feeCalculator, times(1)).addRamByteSeconds(136L * USAGE_PROPERTIES.legacyReceiptStorageSecs());
        inOrder.verify(feeCalculator, times(1)).calculate();
    }

    @Test
    void handleNullArgs() {
        Assertions.assertThatThrownBy(() -> subject.handle(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void handleExceedsMaxHbarTransfers() {
        config = defaultConfig().withValue("ledger.transfers.maxLen", 1).getOrCreateConfig();
        final var txn = newCryptoTransfer(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10);
        final var context = mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TRANSFER_LIST_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void handleHbarAllowancePresentButNotEnoughForCustomFee() {
        config = defaultConfig().getOrCreateConfig();
        givenStoresAndConfig(handleContext);
        givenTxnWithAllowances();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);

        CryptoCreateStreamBuilder cryptoCreateRecordBuilder = mock(CryptoCreateStreamBuilder.class);
        given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);

        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && spenderId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(tokenReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(0L, 0L, tokenReceiver));
                    writableTokenRelStore.put(fungibleTokenRelation
                            .copyBuilder()
                            .kycGranted(true)
                            .accountId(tokenReceiverId)
                            .build());
                    return cryptoCreateRecordBuilder;
                });

        when(handleContext.dispatchMetadata()).thenReturn(mock(DispatchMetadata.class));

        Assertions.assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
    }

    @Test
    void handleExceedsMaxFungibleTokenTransfersInSingleTokenTransferList() {
        config = defaultConfig().withValue("ledger.tokenTransfers.maxLen", 1).getOrCreateConfig();
        // Here we configure a SINGLE TokenTransferList that has 2 fungible token transfers
        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                .build());
        final var context = mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void handleExceedsMaxFungibleTokenTransfersAcrossMultipleTokenTransferLists() {
        config = defaultConfig().withValue("ledger.tokenTransfers.maxLen", 4).getOrCreateConfig();
        // Here we configure MULTIPLE TokenTransferList objects, each with a fungible token transfer credit and debit
        final var txn = newCryptoTransfer(
                TokenTransferList.newBuilder()
                        .token(TOKEN_1357)
                        .transfers(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10)
                        .build(),
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                        .build(),
                TokenTransferList.newBuilder()
                        .token(TOKEN_9191)
                        .transfers(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10)
                        .build());
        final var context = mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void handleExceedsMaxNftTransfersInSingleTokenTransferList() {
        config = defaultConfig().withValue("ledger.nftTransfers.maxLen", 1).getOrCreateConfig();
        // Here we configure a SINGLE TokenTransferList that has 2 nft transfers

        final var txn = newCryptoTransfer(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444, SERIAL_2_FROM_4444_TO_3333)
                .build());
        final var context = mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void handleExceedsMaxNftTransfersAcrossMultipleTokenTransferLists() {
        config = defaultConfig().withValue("ledger.nftTransfers.maxLen", 1).getOrCreateConfig();
        // Here we configure TWO TokenTransferList objects that each have a single nft transfer
        final var txn = newCryptoTransfer(
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                        .build(),
                TokenTransferList.newBuilder()
                        .token(TOKEN_1357)
                        .nftTransfers(SERIAL_2_FROM_4444_TO_3333)
                        .build());
        final var context = mockContext(txn);

        Assertions.assertThatThrownBy(() -> subject.handle(context))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void failsWhenAutoAssociatedTokenHasKycKey() {
        Assertions.setMaxStackTraceElementsDisplayed(200);

        subject = new CryptoTransferHandler(validator, false);
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        givenTxn();
        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(tokenReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(0L, 0L, tokenReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN));
    }

    @Test
    void happyPathWorksWithAutoCreation() {
        subject = new CryptoTransferHandler(validator, false);
        refreshWritableStores();
        writableTokenStore.put(nonFungibleToken.copyBuilder().kycKey((Key) null).build());
        writableTokenStore.put(fungibleToken.copyBuilder().kycKey((Key) null).build());
        givenStoresAndConfig(handleContext);
        givenTxn();
        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.putAndIncrementCount(copy);
                    writableAccountStore.putAndIncrementCountAlias(ecKeyAlias.value(), asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(tokenReceiverId).build();
                    writableAccountStore.putAndIncrementCount(copy);
                    writableAccountStore.putAndIncrementCountAlias(
                            edKeyAlias.value(), asAccount(0L, 0L, tokenReceiver));
                    writableTokenRelStore.put(fungibleTokenRelation
                            .copyBuilder()
                            .kycGranted(true)
                            .accountId(tokenReceiverId)
                            .build());
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

        final var initialSenderBalance = writableAccountStore.get(ownerId).tinybarBalance();
        final var initialFeeCollectorBalance =
                writableAccountStore.get(feeCollectorId).tinybarBalance();
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(StreamBuilder.class)).willReturn(transferRecordBuilder);
        given(stack.getBaseBuilder(CryptoTransferStreamBuilder.class)).willReturn(transferRecordBuilder);

        subject.handle(handleContext);

        assertThat(writableAccountStore.modifiedAliasesInState()).hasSize(2);
        assertThat(writableAccountStore.modifiedAccountsInState()).hasSize(4); // includes fee collector for custom fees
        assertThat(writableAccountStore.modifiedAccountsInState())
                .contains(ownerId, asAccount(0L, 0L, hbarReceiver), asAccount(0L, 0L, tokenReceiver));

        assertThat(writableAccountStore.get(asAccount(0L, 0L, hbarReceiver))).isNotNull();
        assertThat(writableAccountStore.get(asAccount(0L, 0L, tokenReceiver))).isNotNull();
        assertThat(writableAliases.get(ecKeyAlias)).isEqualTo(hbarReceiverId);
        assertThat(writableAliases.get(edKeyAlias)).isEqualTo(tokenReceiverId);

        final var endSenderBalance = writableAccountStore.get(ownerId).tinybarBalance();
        // 1_000 for custom fee, 1_000 for transfer
        assertThat(endSenderBalance).isEqualTo(initialSenderBalance - 1_000 - 1_000);

        final var feeCollectorBalance = writableAccountStore.get(feeCollectorId).tinybarBalance();
        assertThat(feeCollectorBalance)
                .isEqualTo(
                        initialFeeCollectorBalance
                                + 1_000 // fixed custom fee
                                + 1_000 // hbar fallback royalty fee
                        );
    }

    @Test
    void failsOnRepeatedAliasAndCorrespondingNumber() {
        final var txnBody = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                aaWith(ownerId, -2_000),
                                aaWith(unknownAliasedId, +1_000),
                                aaWith(asAccount(0L, 0L, hbarReceiver), +1_000))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .transfers(List.of(aaWith(ownerId, -1_000), aaWith(unknownAliasedId1, +1_000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWith(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        givenTxn(txnBody, payerId);

        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(tokenReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(0L, 0L, tokenReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @Test
    void failsOnRepeatedAliasAndCorrespondingNumberInTokenTransferList() {
        final var txnBody = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, -1_000), aaWith(unknownAliasedId, +1_000))
                        .build())
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .transfers(List.of(
                                        aaWith(ownerId, -2_000),
                                        aaWith(unknownAliasedId1, +1_000),
                                        aaWith(asAccount(0L, 0L, tokenReceiver), +1_000)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWith(ownerId, unknownAliasedId1, 1))
                                .build())
                .build();
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        givenTxn(txnBody, payerId);

        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(tokenReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(0L, 0L, tokenReceiver));
                    given(cryptoCreateRecordBuilder.status()).willReturn(SUCCESS);
                    return cryptoCreateRecordBuilder;
                });
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @Test
    void checkCalculateFeesHandlesExceptionInternally() {
        // need to set up a transaction that fails because of insufficient payer balance for custom fee
        config = defaultConfig().getOrCreateConfig();
        // setup all of the standard stores
        givenStoresAndConfig(handleContext);
        // make txn with owner sending 1000 w/ allowances to unknown aliased id
        // and token transfer of FT from owner 1000 w/ allowances to unknown aliased id
        // and one NFT from owner to unknown aliased ID
        givenTxnWithAllowances();
        ensureAliasesStep = new EnsureAliasesStep(body);
        replaceAliasesWithIDsInOp = new ReplaceAliasesWithIDsInOp();
        associateTokenRecepientsStep = new AssociateTokenRecipientsStep(body);

        CryptoCreateStreamBuilder cryptoCreateRecordBuilder = mock(CryptoCreateStreamBuilder.class);

        given(handleContext.dispatch(
                        argThat(options -> CryptoCreateStreamBuilder.class.equals(options.streamBuilderType())
                                && spenderId.equals(options.payerId()))))
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(hbarReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(ecKeyAlias, asAccount(0L, 0L, hbarReceiver));
                    return cryptoCreateRecordBuilder;
                })
                .will((invocation) -> {
                    final var copy =
                            account.copyBuilder().accountId(tokenReceiverId).build();
                    writableAccountStore.put(copy);
                    writableAliases.put(edKeyAlias, asAccount(0L, 0L, tokenReceiver));
                    writableTokenRelStore.put(fungibleTokenRelation
                            .copyBuilder()
                            .kycGranted(true)
                            .accountId(tokenReceiverId)
                            .build());
                    return cryptoCreateRecordBuilder;
                });

        when(handleContext.dispatchMetadata()).thenReturn(mock(DispatchMetadata.class));

        FeeContext feeContext = mock(DispatchHandleContext.class);
        FeeCalculatorFactory feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        FeeCalculator feeCalculator = mock(FeeCalculator.class);
        Fees fees = mock(Fees.class);
        when(feeContext.body())
                .thenReturn(TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder().accountID(ACCOUNT_ID_3333))
                        .cryptoTransfer(body)
                        .build());
        when(feeContext.configuration()).thenReturn(config);
        when(feeContext.readableStore(ReadableTokenStore.class)).thenReturn(readableTokenStore);
        when(feeContext.readableStore(ReadableTokenRelationStore.class)).thenReturn(readableTokenRelStore);
        when(feeContext.readableStore(ReadableAccountStore.class)).thenReturn(readableAccountStore);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);
        when(feeCalculatorFactory.feeCalculator(any())).thenReturn(feeCalculator);
        when(feeCalculator.addBytesPerTransaction(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.addRamByteSeconds(anyLong())).thenReturn(feeCalculator);
        when(feeCalculator.calculate()).thenReturn(fees);

        // this confirms that the exception thrown by customFeeAssessor.assessNumberOfCustomFees() is
        // handled properly by calculateFees()
        assertThatNoException().isThrownBy(() -> subject.calculateFees(feeContext));
    }

    @Mock(strictness = LENIENT)
    protected PreHandleContext preHandleContext;

    @Test
    void testPreHandler() {
        givenStoresAndConfig(handleContext);
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(preHandleContext.configuration()).willReturn(configuration);
        body = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(aaWith(ownerId, 1_000), aaWith(unknownAliasedId, -1_000))
                        .build())
                .build();
        givenTxn(body, payerId);
        given(preHandleContext.body()).willReturn(txn);
        // this is a debit transfer, not a credit, so it should fail with invalid account id
        Assertions.assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    private HandleContext mockContext(final TransactionBody txn) {
        final var context = mock(HandleContext.class);
        given(context.configuration()).willReturn(config);
        given(context.body()).willReturn(txn);
        return context;
    }

    private static TestConfigBuilder defaultConfig() {
        return HederaTestConfigBuilder.create()
                .withValue("ledger.transfers.maxLen", 10)
                .withValue("ledger.tokenTransfers.maxLen", 10)
                .withValue("ledger.nftTransfers.maxLen", 10);
    }
}
