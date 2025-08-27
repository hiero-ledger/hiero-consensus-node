// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HollowAccountCompletionsTest {
    @Mock(strictness = LENIENT)
    private Dispatch dispatch;

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private ReadableAccountStore accountStore;

    @Mock(strictness = LENIENT)
    private AppKeyVerifier keyVerifier;

    @Mock(strictness = LENIENT)
    private ReadableStoreFactory readableStoreFactory;

    @Mock(strictness = LENIENT)
    private PreHandleResult preHandleResult;

    @Mock(strictness = LENIENT)
    private RecordStreamBuilder recordBuilder;

    @Mock
    private EthereumTransactionHandler ethereumTransactionHandler;

    @Mock
    private ParentTxn parentTxn;

    @Mock
    private SavepointStackImpl stack;

    public static final Instant consensusTime = Instant.ofEpochSecond(1_234_567L);
    private static final AccountID payerId =
            AccountID.newBuilder().accountNum(1_234L).build();
    private static final CryptoTransferTransactionBody transferBody = CryptoTransferTransactionBody.newBuilder()
            .tokenTransfers(TokenTransferList.newBuilder()
                    .token(TokenID.DEFAULT)
                    .nftTransfers(NftTransfer.newBuilder()
                            .receiverAccountID(AccountID.DEFAULT)
                            .senderAccountID(AccountID.DEFAULT)
                            .serialNumber(1)
                            .build())
                    .build())
            .build();
    private static final TransactionBody txBody = asTxn(transferBody, payerId, consensusTime);
    private static final SignedTransaction transaction = SignedTransaction.newBuilder()
            .bodyBytes(TransactionBody.PROTOBUF.toBytes(txBody))
            .build();
    private static final Bytes transactionBytes = SignedTransaction.PROTOBUF.toBytes(transaction);

    @InjectMocks
    private HollowAccountCompletions hollowAccountCompletions;

    @BeforeEach
    void setUp() {
        when(dispatch.handleContext()).thenReturn(handleContext);
        when(dispatch.keyVerifier()).thenReturn(keyVerifier);
        when(handleContext.payer()).thenReturn(payerId);
        when(parentTxn.readableStoreFactory()).thenReturn(readableStoreFactory);
        when(parentTxn.readableStoreFactory().getStore(ReadableAccountStore.class))
                .thenReturn(accountStore);
        when(parentTxn.preHandleResult()).thenReturn(preHandleResult);
        when(handleContext.dispatch(any())).thenReturn(recordBuilder);
    }

    @Test
    void completeHollowAccountsNoHollowAccounts() {
        when(parentTxn.preHandleResult().getHollowAccounts()).thenReturn(Collections.emptySet());

        hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);

        verifyNoInteractions(keyVerifier);
        verifyNoInteractions(handleContext);
    }

    @Test
    void doesntCompleteHollowAccountsWithNoImmutabilitySentinelKey() {
        final var hollowAccount = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1).build())
                .key(Key.DEFAULT)
                .alias(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        when(preHandleResult.getHollowAccounts()).thenReturn(Collections.singleton(hollowAccount));
        SignatureVerification verification =
                new SignatureVerificationImpl(Key.DEFAULT, Bytes.wrap(new byte[] {1, 2, 3}), true);
        when(keyVerifier.verificationFor(Bytes.wrap(new byte[] {1, 2, 3}))).thenReturn(verification);
        when(parentTxn.preHandleResult().getHollowAccounts()).thenReturn(Set.of(hollowAccount));
        when(parentTxn.stack()).thenReturn(stack);
        when(stack.hasMoreSystemRecords()).thenReturn(true);

        hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);

        verify(keyVerifier).verificationFor(Bytes.wrap(new byte[] {1, 2, 3}));
        verify(handleContext, never()).dispatch(any());
    }

    @Test
    void completeHollowAccountsWithHollowAccounts() {
        final var hollowAccount = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1).build())
                .key(IMMUTABILITY_SENTINEL_KEY)
                .alias(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        when(parentTxn.preHandleResult().getHollowAccounts()).thenReturn(Collections.singleton(hollowAccount));
        SignatureVerification verification =
                new SignatureVerificationImpl(Key.DEFAULT, Bytes.wrap(new byte[] {1, 2, 3}), true);
        when(keyVerifier.verificationFor(Bytes.wrap(new byte[] {1, 2, 3}))).thenReturn(verification);
        when(parentTxn.preHandleResult().getHollowAccounts()).thenReturn(Set.of(hollowAccount));
        when(parentTxn.stack()).thenReturn(stack);
        when(stack.hasMoreSystemRecords()).thenReturn(true);

        hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);

        verify(keyVerifier).verificationFor(Bytes.wrap(new byte[] {1, 2, 3}));
        verify(handleContext).dispatch(any());
        verify(recordBuilder).accountID(AccountID.newBuilder().accountNum(1).build());
    }

    @Test
    void skipDummyHollowAccountsFromCryptoCreateHandler() {
        final var hollowAccount = Account.newBuilder()
                .accountId(AccountID.DEFAULT)
                .key(IMMUTABILITY_SENTINEL_KEY)
                .alias(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        when(parentTxn.preHandleResult().getHollowAccounts()).thenReturn(Collections.singleton(hollowAccount));
        when(parentTxn.preHandleResult().getHollowAccounts()).thenReturn(Set.of(hollowAccount));
        when(parentTxn.stack()).thenReturn(stack);
        when(stack.hasMoreSystemRecords()).thenReturn(true);

        hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);

        verify(handleContext, never()).dispatch(any());
    }

    @Test
    void completeHollowAccountsWithEthereumTransaction() {
        when(parentTxn.functionality()).thenReturn(ETHEREUM_TRANSACTION);
        final var alias = Bytes.fromHex("89abcdef89abcdef89abcdef89abcdef89abcdef");
        final var hederaConfig = DEFAULT_CONFIG.getConfigData(HederaConfig.class);
        final var idFactory = new FakeEntityIdFactoryImpl(hederaConfig.shard(), hederaConfig.realm());
        final var hollowId = idFactory.newAccountId(1234);
        final var hollowAccount = Account.newBuilder()
                .alias(alias)
                .key(IMMUTABILITY_SENTINEL_KEY)
                .accountId(hollowId)
                .build();
        final var ethTxSigs = new EthTxSigs(Bytes.EMPTY.toByteArray(), alias.toByteArray());
        when(ethereumTransactionHandler.maybeEthTxSigsFor(any(), any(), any())).thenReturn(ethTxSigs);
        when(accountStore.getAccountIDByAlias(hederaConfig.shard(), hederaConfig.realm(), alias))
                .thenReturn(hollowId);
        when(accountStore.getAccountById(hollowId)).thenReturn(hollowAccount);
        final var txnBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(1).build())
                        .build())
                .ethereumTransaction(EthereumTransactionBody.DEFAULT)
                .build();
        final TransactionInfo txnInfo = new TransactionInfo(
                SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(txnBody))
                        .build(),
                txnBody,
                SignatureMap.DEFAULT,
                transactionBytes,
                ETHEREUM_TRANSACTION,
                null);

        when(parentTxn.readableStoreFactory().getStore(ReadableAccountStore.class))
                .thenReturn(accountStore);
        when(parentTxn.config()).thenReturn(DEFAULT_CONFIG);
        when(parentTxn.txnInfo()).thenReturn(txnInfo);
        when(parentTxn.stack()).thenReturn(stack);
        when(stack.hasMoreSystemRecords()).thenReturn(true);

        hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);

        verify(handleContext).dispatch(any());
        verify(recordBuilder).accountID(hollowId);
    }

    @Test
    void ignoreEthereumTransactionIfNoCorrespondingSigs() {
        when(parentTxn.config()).thenReturn(DEFAULT_CONFIG);
        when(parentTxn.functionality()).thenReturn(ETHEREUM_TRANSACTION);
        when(ethereumTransactionHandler.maybeEthTxSigsFor(any(), any(), any())).thenReturn(null);
        final var txnBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(1).build())
                        .build())
                .ethereumTransaction(EthereumTransactionBody.DEFAULT)
                .build();
        final TransactionInfo txnInfo = new TransactionInfo(
                SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(txnBody))
                        .build(),
                txnBody,
                SignatureMap.DEFAULT,
                transactionBytes,
                ETHEREUM_TRANSACTION,
                null);
        when(parentTxn.txnInfo()).thenReturn(txnInfo);

        hollowAccountCompletions.completeHollowAccounts(parentTxn, dispatch);

        verify(handleContext, never()).dispatch(any());
    }

    public static TransactionBody asTxn(
            final CryptoTransferTransactionBody body, final AccountID payerId, Instant consensusTimestamp) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(payerId)
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds(consensusTimestamp.getEpochSecond())
                                .build())
                        .build())
                .memo("test memo")
                .cryptoTransfer(body)
                .build();
    }
}
