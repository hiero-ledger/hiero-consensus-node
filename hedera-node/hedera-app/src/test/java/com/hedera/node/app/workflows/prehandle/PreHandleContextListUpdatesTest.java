// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreHandleContextListUpdatesTest {

    private static final Configuration CONFIG = HederaTestConfigBuilder.createConfig();
    public static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                                            .build(),
                                    Key.newBuilder()
                                            .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                                            .build())))
            .build();
    private Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    private Key key = A_COMPLEX_KEY;
    private AccountID payer = AccountID.newBuilder().accountNum(3L).build();
    private Key payerKey = A_COMPLEX_KEY;

    final ContractID otherContractId =
            ContractID.newBuilder().contractNum(123456L).build();

    private Key otherKey = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(1)
                    .keys(KeyList.newBuilder()
                            .keys(Key.newBuilder()
                                    .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                                    .build())))
            .build();
    private Key contractIdKey = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(1)
                    .keys(KeyList.newBuilder()
                            .keys(Key.newBuilder()
                                    .contractID(ContractID.newBuilder()
                                            .contractNum(123456L)
                                            .build())
                                    .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                                    .build())))
            .build();

    @Mock
    private ReadableStoreFactory storeFactory;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private Account account;

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private Account contractAccount;

    @Mock
    private TransactionDispatcher dispatcher;

    @Mock
    private TransactionChecker transactionChecker;

    private PreHandleContext subject;

    @Test
    void gettersWorkAsExpectedWhenOnlyPayerKeyExist() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        final var txn = createAccountTransaction();

        // When we create a PreHandleContext
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // Then the body, payer, and required keys are as expected
        assertEquals(txn, subject.body());
        assertEquals(payerKey, subject.payerKey());
        assertEquals(Set.of(), subject.requiredNonPayerKeys());
    }

    @Test
    void requireSomeOtherKey() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer, and a PreHandleContext
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require some other key on the context
        subject.requireKey(otherKey);

        // Then the requiredNonPayerKeys includes that other key
        assertEquals(Set.of(otherKey), subject.requiredNonPayerKeys());
    }

    @Test
    void requireSomeOtherKeyTwice() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer, and a PreHandleContext
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require some other key on the context more than once
        subject.requireKey(otherKey);
        subject.requireKey(otherKey);

        // Then the requiredNonPayerKeys only includes the key once
        assertEquals(Set.of(otherKey), subject.requiredNonPayerKeys());
    }

    @Test
    void payerIsIgnoredWhenRequired() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer, and a PreHandleContext
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require the payer key on the context
        subject.requireKey(payerKey);

        // Then the call is ignored and the payerKey is not added to requiredNonPayerKeys
        assertEquals(Set.of(), subject.requiredNonPayerKeys());
    }

    @Test
    void failsWhenPayerKeyDoesntExist() throws PreCheckException {
        // Given an account ID that does not exist
        final var txn = createAccountTransaction();
        given(accountStore.getAccountById(payer)).willReturn(null);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);

        // When we create a PreHandleContext, then it fails with INVALID_PAYER_ACCOUNT_ID
        assertThrowsPreCheck(
                () -> new PreHandleContextImpl(storeFactory, txn, CONFIG, dispatcher, transactionChecker, creatorInfo),
                INVALID_PAYER_ACCOUNT_ID);
    }

    @Test
    void returnsIfGivenKeyIsPayer() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer and a PreHandleContext
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require the payer to exist (or throw INVALID_ACCOUNT_ID)
        subject.requireKeyOrThrow(payer, INVALID_ACCOUNT_ID);

        // Then the call succeeds, although the payer key is not added to requiredNonPayerKeys
        assertEquals(payerKey, subject.payerKey());
        assertIterableEquals(Set.of(), subject.requiredNonPayerKeys());

        // And when we try with requireKeyIfReceiverSigRequired, it also succeeds in the same way
        subject.requireKeyIfReceiverSigRequired(payer, INVALID_ACCOUNT_ID);
        assertIterableEquals(Set.of(), subject.requiredNonPayerKeys());
    }

    @Test
    void returnsIfGivenKeyIsInvalidAccountId() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer and a PreHandleContext
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require an accountID that doesn't exist, then we get a PreCheckException
        final var bogus = AccountID.newBuilder().build();
        assertThrowsPreCheck(() -> subject.requireKeyOrThrow(bogus, INVALID_ACCOUNT_ID), INVALID_ACCOUNT_ID);
    }

    @Test
    void addsContractIdKey() throws PreCheckException {
        // Given an account with a key, and a transaction using that account as the payer,
        // and a contract account with a key, and a PreHandleContext
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(accountStore.getContractById(otherContractId)).willReturn(contractAccount);
        given(contractAccount.key()).willReturn(contractIdKey);
        given(contractAccount.keyOrElse(EMPTY_KEY_LIST)).willReturn(contractIdKey);
        given(contractAccount.accountIdOrThrow()).willReturn(asAccount(0L, 0L, otherContractId.contractNum()));
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require the contract account's key,
        subject.requireKeyOrThrow(otherContractId, INVALID_CONTRACT_ID);

        // Then the contract account's key is included in the required non-payer keys
        assertIterableEquals(Set.of(contractIdKey), subject.requiredNonPayerKeys());
    }

    @Test
    void doesntFailForAliasedAccount() throws PreCheckException {
        // Given an account that can be looked up by number or alias and a PreHandleContext
        final var alias = AccountID.newBuilder().alias(Bytes.wrap("test")).build();
        given(accountStore.getAccountById(alias)).willReturn(account);
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(account.accountIdOrThrow()).willReturn(payer);
        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);

        // When we require the account by alias
        subject.requireKeyOrThrow(alias, INVALID_ACCOUNT_ID);

        // Then it isn't added to the list of keys because the key is already the payer key
        assertEquals(payerKey, subject.payerKey());
        assertIterableEquals(Set.of(), subject.requiredNonPayerKeys());
    }

    @Test
    void doesntFailForAliasedContract() throws PreCheckException {
        final var alias = ContractID.newBuilder().evmAddress(Bytes.wrap("test")).build();
        given(accountStore.getContractById(alias)).willReturn(contractAccount);
        given(contractAccount.key()).willReturn(otherKey);
        given(contractAccount.keyOrElse(EMPTY_KEY_LIST)).willReturn(otherKey);
        given(contractAccount.accountIdOrThrow()).willReturn(asAccount(0L, 0L, otherContractId.contractNum()));
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);

        subject = new PreHandleContextImpl(
                        storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo)
                .requireKeyOrThrow(alias, INVALID_CONTRACT_ID);

        assertEquals(payerKey, subject.payerKey());
        assertIterableEquals(Set.of(otherKey), subject.requiredNonPayerKeys());
    }

    @Test
    void failsForInvalidAlias() throws PreCheckException {
        final var alias = AccountID.newBuilder().alias(Bytes.wrap("test")).build();
        given(accountStore.getAccountById(alias)).willReturn(null);
        given(accountStore.getAccountById(payer)).willReturn(account);
        given(account.keyOrThrow()).willReturn(payerKey);
        given(storeFactory.getStore(ReadableAccountStore.class)).willReturn(accountStore);

        subject = new PreHandleContextImpl(
                storeFactory, createAccountTransaction(), CONFIG, dispatcher, transactionChecker, creatorInfo);
        assertThrowsPreCheck(() -> subject.requireKeyOrThrow(alias, INVALID_ACCOUNT_ID), INVALID_ACCOUNT_ID);
    }

    private TransactionBody createAccountTransaction() {
        final var transactionID = TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
        final var createTxnBody = CryptoCreateTransactionBody.newBuilder()
                .key(key)
                .receiverSigRequired(true)
                .memo("Create Account")
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoCreateAccount(createTxnBody)
                .build();
    }
}
