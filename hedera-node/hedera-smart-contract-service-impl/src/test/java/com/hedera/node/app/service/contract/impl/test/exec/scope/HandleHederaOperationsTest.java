// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.AN_ED25519_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CANONICAL_ALIAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_ACCOUNTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_LEDGER_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_DURATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthAccountCreationFromHapi;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationFromParent;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.SUPPRESSING_TRANSACTION_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UncheckedParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleHederaOperationsTest {
    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private BlockRecordInfo blockRecordInfo;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private HandleContext context;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableContractStateStore stateStore;

    @Mock
    private ContractCreateStreamBuilder contractCreateRecordBuilder;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private SystemContractGasCalculator gasCalculator;

    @Mock
    private PendingCreationMetadataRef pendingCreationMetadataRef;

    @Mock
    private EntityNumGenerator entityNumGenerator;

    @Mock
    private HandleContext.SavepointStack stack;

    private HandleHederaOperations subject;

    @BeforeEach
    void setUp() {
        subject = new HandleHederaOperations(
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_CONTRACTS_CONFIG,
                context,
                tinybarValues,
                gasCalculator,
                DEFAULT_HEDERA_CONFIG,
                HederaFunctionality.CONTRACT_CALL,
                pendingCreationMetadataRef,
                DEFAULT_ACCOUNTS_CONFIG,
                entityIdFactory);
    }

    @Test
    void returnsContextualStore() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableContractStateStore.class)).willReturn(stateStore);

        assertSame(stateStore, subject.getStore());
    }

    @Test
    void validatesShard() {
        assertSame(
                HederaOperations.MISSING_CONTRACT_ID,
                subject.shardAndRealmValidated(
                        ContractID.newBuilder().shardNum(1).contractNum(2L).build()));
    }

    @Test
    void validatesRealm() {
        assertSame(
                HederaOperations.MISSING_CONTRACT_ID,
                subject.shardAndRealmValidated(
                        ContractID.newBuilder().realmNum(1).contractNum(2L).build()));
    }

    @Test
    void returnsUnchangedWithMatchingShardRealm() {
        final var plausibleId = ContractID.newBuilder()
                .shardNum(DEFAULT_HEDERA_CONFIG.shard())
                .realmNum(DEFAULT_HEDERA_CONFIG.realm())
                .contractNum(3456L)
                .build();
        assertSame(plausibleId, subject.shardAndRealmValidated(plausibleId));
    }

    @Test
    void usesExpectedLimit() {
        assertEquals(DEFAULT_CONTRACTS_CONFIG.maxNumber(), subject.contractCreationLimit());
        assertEquals(DEFAULT_ACCOUNTS_CONFIG.maxNumber(), subject.accountCreationLimit());
    }

    @Test
    void delegatesEntropyToBlockRecordInfo() {
        final var pretendEntropy = Bytes.fromHex("0123456789");
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(pretendEntropy);
        assertSame(pretendEntropy, subject.entropy());
    }

    @Test
    void returnsZeroEntropyIfNMinus3HashMissing() {
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
        assertSame(HandleHederaOperations.ZERO_ENTROPY, subject.entropy());
    }

    @Test
    void createsNewSavepointWhenBeginningScope() {
        given(context.savepointStack()).willReturn(stack);

        final var nestedScope = subject.begin();

        assertSame(subject, nestedScope);
        verify(stack).createSavepoint();
    }

    @Test
    void rollsBackSavepointWhenReverting() {
        given(context.savepointStack()).willReturn(stack);

        subject.revert();

        verify(stack).rollback();
    }

    @Test
    void peekNumberUsesContext() {
        given(context.entityNumGenerator()).willReturn(entityNumGenerator);
        given(entityNumGenerator.peekAtNewEntityNum()).willReturn(123L);
        assertEquals(123L, subject.peekNextEntityNumber());
    }

    @Test
    void useNumberUsesContext() {
        given(context.entityNumGenerator()).willReturn(entityNumGenerator);
        given(entityNumGenerator.newEntityNum()).willReturn(123L);
        assertEquals(123L, subject.useNextEntityNumber());
    }

    @Test
    void commitIsNoopUntilSavepointExposesIt() {
        given(context.savepointStack()).willReturn(stack);

        subject.commit();

        verify(stack).commit();
    }

    @Test
    void lazyCreationCostInGasTest() {
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(gasCalculator.feeCalculatorPriceInTinyBars(any(), eq(A_NEW_ACCOUNT_ID)))
                .willReturn(5L);
        given(gasCalculator.topLevelGasPriceInTinyBars()).willReturn(1000L);
        assertEquals(5L, subject.lazyCreationCostInGas(NON_SYSTEM_LONG_ZERO_ADDRESS));
    }

    @Test
    void gasPriceInTinybarsDelegates() {
        given(tinybarValues.topLevelTinybarGasPrice()).willReturn(1234L);
        assertEquals(1234L, subject.gasPriceInTinybars());
    }

    @Test
    void valueInTinybarsDelegates() {
        given(tinybarValues.asTinybars(1L)).willReturn(2L);
        assertEquals(2L, subject.valueInTinybars(1L));
    }

    @Test
    void collectFeeStillTransfersAllToNetworkFunding() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.collectFee(TestHelpers.NON_SYSTEM_ACCOUNT_ID, 123L);

        verify(tokenServiceApi)
                .transferFromTo(
                        TestHelpers.NON_SYSTEM_ACCOUNT_ID,
                        AccountID.newBuilder()
                                .accountNum(DEFAULT_LEDGER_CONFIG.fundingAccount())
                                .build(),
                        123L);
    }

    @Test
    void refundFeeStillTransfersAllFromNetworkFunding() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.refundFee(TestHelpers.NON_SYSTEM_ACCOUNT_ID, 123L);

        verify(tokenServiceApi)
                .transferFromTo(
                        AccountID.newBuilder()
                                .accountNum(DEFAULT_LEDGER_CONFIG.fundingAccount())
                                .build(),
                        TestHelpers.NON_SYSTEM_ACCOUNT_ID,
                        123L);
    }

    @Test
    void chargeStorageRentIsNoop() {
        assertDoesNotThrow(() -> subject.chargeStorageRent(
                ContractID.newBuilder().contractNum(1L).build(), 2L, true));
    }

    @Test
    void updateStorageMetadataUsesApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.updateStorageMetadata(NON_SYSTEM_CONTRACT_ID, Bytes.EMPTY, 2);

        verify(tokenServiceApi).updateStorageMetadata(NON_SYSTEM_CONTRACT_ID, Bytes.EMPTY, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createContractWithNonSelfAdminParentDispatchesAsExpectedThenMarksCreated() throws ParseException {
        final var parent = Account.newBuilder()
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(124L)))
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthContractCreation = synthContractCreationFromParent(pendingId, parent);

        final var synthAccountCreation =
                synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, synthContractCreation);
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreation)
                .build();
        final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(captor.capture())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS);

        final var dispatchOptions = captor.getValue();
        assertEquals(synthTxn, dispatchOptions.body());
        assertInternalFinisherAsExpected(dispatchOptions.transactionCustomizer(), synthContractCreation);
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    @Test
    void translatesCreateContractHandleException() {
        final var parent = Account.newBuilder()
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(124L)))
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.dispatch(any())).willThrow(new HandleException(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED));
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        final long accountNum = NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow();
        final var e = Assertions.assertThrows(
                ResourceExhaustedException.class, () -> subject.createContract(666L, accountNum, CANONICAL_ALIAS));
        assertEquals(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED, e.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createContractWithSelfAdminParentDispatchesAsExpectedThenMarksCreated() throws ParseException {
        final var parent = Account.newBuilder()
                .key(Key.newBuilder().contractID(ContractID.newBuilder().contractNum(123L)))
                .accountId(AccountID.newBuilder().accountNum(123L).build())
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .stakedNodeId(3)
                .declineReward(true)
                .autoRenewSeconds(666L)
                .maxAutoAssociations(321)
                .memo("Something")
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthContractCreation = synthContractCreationFromParent(pendingId, parent)
                .copyBuilder()
                .adminKey(Key.newBuilder()
                        .contractID(ContractID.newBuilder().contractNum(666L))
                        .build())
                .build();
        final var synthAccountCreation =
                synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, synthContractCreation);
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreation)
                .build();
        final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(captor.capture())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS);

        final var dispatchOptions = captor.getValue();
        assertInternalFinisherAsExpected(dispatchOptions.transactionCustomizer(), synthContractCreation);
        assertEquals(synthTxn, dispatchOptions.body());
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    private void assertInternalFinisherAsExpected(
            @NonNull final UnaryOperator<Transaction> internalFinisher,
            @NonNull final ContractCreateTransactionBody expectedOp)
            throws ParseException {
        Objects.requireNonNull(internalFinisher);

        // The finisher should swap the crypto create body with the contract create body
        final var cryptoCreateBody = TransactionBody.newBuilder()
                .cryptoCreateAccount(CryptoCreateTransactionBody.DEFAULT)
                .build();
        final var cryptoCreateInput = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(cryptoCreateBody))
                        .build()))
                .build();
        final var cryptoCreateOutput = internalFinisher.apply(cryptoCreateInput);
        final var finishedBody = TransactionBody.PROTOBUF.parseStrict(SignedTransaction.PROTOBUF
                .parseStrict(cryptoCreateOutput.signedTransactionBytes().toReadableSequentialData())
                .bodyBytes()
                .toReadableSequentialData());
        assertEquals(expectedOp, finishedBody.contractCreateInstanceOrThrow());

        // The finisher should reject transforming anything byt a crypto create
        final var nonCryptoCreateBody = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        final var nonCryptoCreateInput = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(nonCryptoCreateBody))
                        .build()))
                .build();
        assertThrows(IllegalArgumentException.class, () -> internalFinisher.apply(nonCryptoCreateInput));

        // The finisher should propagate any IOExceptions (which should never happen, as only HandleContext is client)
        final var nonsenseInput = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.wrap("NONSENSE"))
                .build();
        assertThrows(UncheckedParseException.class, () -> internalFinisher.apply(nonsenseInput));
    }

    @Test
    void createContractWithBodyDispatchesThenMarksAsContract() {
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        final var pendingId = ContractID.newBuilder().contractNum(666L).build();
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(synthAccountCreationFromHapi(pendingId, CANONICAL_ALIAS, someBody))
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.savepointStack()).willReturn(stack);

        subject.createContract(666L, someBody, CANONICAL_ALIAS);

        verify(context).dispatch(assertArg(options -> {
            assertEquals(A_NEW_ACCOUNT_ID, options.payerId());
            assertEquals(synthTxn, options.body());
            assertEquals(ContractCreateStreamBuilder.class, options.streamBuilderType());
        }));
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createContractInsideEthereumTransactionWithBodyDispatchesThenMarksAsContract() {
        subject = new HandleHederaOperations(
                DEFAULT_LEDGER_CONFIG,
                DEFAULT_CONTRACTS_CONFIG,
                context,
                tinybarValues,
                gasCalculator,
                DEFAULT_HEDERA_CONFIG,
                ETHEREUM_TRANSACTION,
                pendingCreationMetadataRef,
                DEFAULT_ACCOUNTS_CONFIG,
                entityIdFactory);
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(contractCreateRecordBuilder.contractID(any(ContractID.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, someBody, CANONICAL_ALIAS);

        final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
        verify(context).dispatch(captor.capture());
        assertNotSame(SUPPRESSING_TRANSACTION_CUSTOMIZER, captor.getValue().transactionCustomizer());
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    @Test
    void createContractWithFailedDispatchNotImplemented() {
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.dispatch(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        assertThrows(IllegalStateException.class, () -> subject.createContract(666L, someBody, CANONICAL_ALIAS));
    }

    @Test
    void deleteUnaliasedContractUsesApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow());
        verify(tokenServiceApi).deleteContract(CALLED_CONTRACT_ID);
    }

    @Test
    void deleteAliasedContractUsesApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        subject.deleteAliasedContract(CANONICAL_ALIAS);
        verify(tokenServiceApi)
                .deleteContract(
                        ContractID.newBuilder().evmAddress(CANONICAL_ALIAS).build());
    }

    @Test
    void getModifiedAccountNumbersIsNotActuallyNeeded() {
        assertSame(Collections.emptyList(), subject.getModifiedAccountNumbers());
    }

    @Test
    void getOriginalSlotsUsedDelegatesToApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(tokenServiceApi.originalKvUsageFor(A_NEW_CONTRACT_ID)).willReturn(123L);
        assertEquals(123L, subject.getOriginalSlotsUsed(A_NEW_CONTRACT_ID));
    }

    @Test
    void externalizeHollowAccountMerge() {
        // given
        var contractId = ContractID.newBuilder().contractNum(1001).build();
        given(context.savepointStack()).willReturn(stack);
        given(stack.addRemovableChildRecordBuilder(ContractCreateStreamBuilder.class, CONTRACT_CREATE))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractID(contractId)).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.transaction(any(Transaction.class))).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);

        // when
        subject.externalizeHollowAccountMerge(contractId, VALID_CONTRACT_ADDRESS.evmAddress());

        // then
        verify(contractCreateRecordBuilder).contractID(contractId);
        verify(contractCreateRecordBuilder).contractCreateResult(any(ContractFunctionResult.class));
    }
}
