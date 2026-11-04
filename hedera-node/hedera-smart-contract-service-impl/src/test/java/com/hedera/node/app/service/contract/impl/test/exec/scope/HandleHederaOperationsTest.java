// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
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
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RELAYER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_DURATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthAccountCreationFromHapi;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthContractCreationFromParent;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REMOVABLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.SUPPRESSING_SIGNED_TX_CUSTOMIZER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.PendingCreationMetadataRef;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractDeleteStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.entityid.EntityNumGenerator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UncheckedParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HandleHederaOperationsTest {
    private static final Configuration BOTH_MODE_CONFIG = HederaTestConfigBuilder.create()
            .withValue("blockStream.streamMode", "BOTH")
            .getOrCreateConfig();

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

    @Mock
    private FeeCharging.Context feeChargingContext;

    @Mock
    private ContractMetrics contractMetrics;

    private HandleHederaOperations subject;

    @BeforeEach
    void setUp() {
        createSubject(DEFAULT_CONTRACTS_CONFIG);
    }

    private void createSubject(final ContractsConfig contractsConfig) {
        subject = new HandleHederaOperations(
                contractsConfig,
                context,
                tinybarValues,
                gasCalculator,
                DEFAULT_HEDERA_CONFIG,
                HederaFunctionality.CONTRACT_CALL,
                pendingCreationMetadataRef,
                DEFAULT_ACCOUNTS_CONFIG,
                entityIdFactory,
                contractMetrics);
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
    void collectAndRefundGasFeesUseTheContextAndReplay() {
        given(context.tryToCharge(RELAYER_ID, 69L)).willReturn(true);
        given(context.tryToCharge(NON_SYSTEM_ACCOUNT_ID, 123L)).willReturn(true);
        subject.collectGasFee(RELAYER_ID, 69L, false);
        subject.collectGasFee(NON_SYSTEM_ACCOUNT_ID, 123L, true);
        subject.refundGasFee(RELAYER_ID, 12L);
        subject.refundGasFee(NON_SYSTEM_ACCOUNT_ID, 42L);

        verify(context).tryToCharge(RELAYER_ID, 69L);
        verify(context).tryToCharge(NON_SYSTEM_ACCOUNT_ID, 123L);
        verify(context).refundBestEffort(RELAYER_ID, 12L);
        verify(context).refundBestEffort(NON_SYSTEM_ACCOUNT_ID, 42L);

        assertEquals(
                List.of(
                        new HederaOperations.GasChargingEvent(
                                HederaOperations.GasChargingAction.CHARGE, RELAYER_ID, 69L, false),
                        new HederaOperations.GasChargingEvent(
                                HederaOperations.GasChargingAction.CHARGE, NON_SYSTEM_ACCOUNT_ID, 123L, true),
                        new HederaOperations.GasChargingEvent(
                                HederaOperations.GasChargingAction.REFUND, RELAYER_ID, 12L, false),
                        new HederaOperations.GasChargingEvent(
                                HederaOperations.GasChargingAction.REFUND, NON_SYSTEM_ACCOUNT_ID, 42L, false)),
                subject.gasChargingEvents());
    }

    @Test
    void collectGasFeeFailsWhenChargeIsCappedShortOfRequestedAmount() {
        // tryToCharge returns false when the payer balance could not cover the full amount (the
        // charge is silently capped). collectGasFee must fail closed rather than under-collect.
        given(context.tryToCharge(RELAYER_ID, 69L)).willReturn(false);
        final var e = assertThrows(HandleException.class, () -> subject.collectGasFee(RELAYER_ID, 69L, false));
        assertEquals(INSUFFICIENT_PAYER_BALANCE, e.getStatus());
        // No charge event is recorded for a failed collection
        assertTrue(subject.gasChargingEvents().isEmpty());
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
    void updateHookStorageSlotsUsesApi() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        subject.updateHookStorageSlots(NON_SYSTEM_ACCOUNT_ID, 5);
        verify(tokenServiceApi).updateHookStorageSlots(NON_SYSTEM_ACCOUNT_ID, 5, false);
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
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(contractCreateRecordBuilder.createdContractID(any(ContractID.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(captor.capture())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(contractCreateRecordBuilder.createdEvmAddress(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.evmCreateTransactionResult(any())).willReturn(contractCreateRecordBuilder);

        subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS);

        final var dispatchOptions = captor.getValue();
        assertEquals(synthTxn, dispatchOptions.body());
        assertInternalFinisherAsExpected(dispatchOptions.signedTxCustomizer(), synthContractCreation);
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
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(contractCreateRecordBuilder.createdContractID(any(ContractID.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.createdEvmAddress(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.evmCreateTransactionResult(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(captor.capture())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getAccountById(NON_SYSTEM_ACCOUNT_ID)).willReturn(parent);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, NON_SYSTEM_ACCOUNT_ID.accountNumOrThrow(), CANONICAL_ALIAS);

        final var dispatchOptions = captor.getValue();
        assertInternalFinisherAsExpected(dispatchOptions.signedTxCustomizer(), synthContractCreation);
        assertEquals(synthTxn, dispatchOptions.body());
        verify(tokenServiceApi)
                .markAsContract(AccountID.newBuilder().accountNum(666L).build(), NON_SYSTEM_ACCOUNT_ID);
    }

    private void assertInternalFinisherAsExpected(
            @NonNull final UnaryOperator<SignedTransaction> internalFinisher,
            @NonNull final ContractCreateTransactionBody expectedOp)
            throws ParseException {
        Objects.requireNonNull(internalFinisher);

        // The finisher should swap the crypto create body with the contract create body
        final var cryptoCreateBody = TransactionBody.newBuilder()
                .cryptoCreateAccount(CryptoCreateTransactionBody.DEFAULT)
                .build();
        final var cryptoCreateInput = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(cryptoCreateBody))
                .build();
        final var cryptoCreateOutput = internalFinisher.apply(cryptoCreateInput);
        final var finishedBody = TransactionBody.PROTOBUF.parseStrict(cryptoCreateOutput.bodyBytes());
        assertEquals(expectedOp, finishedBody.contractCreateInstanceOrThrow());

        // The finisher should reject transforming anything but a crypto create
        final var nonCryptoCreateBody = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.DEFAULT)
                .build();
        final var nonCryptoCreateInput = SignedTransaction.newBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(nonCryptoCreateBody))
                .build();
        assertThrows(IllegalArgumentException.class, () -> internalFinisher.apply(nonCryptoCreateInput));

        // The finisher should propagate any IOExceptions (which should never happen, as only HandleContext is client)
        final var nonsenseInput =
                SignedTransaction.newBuilder().bodyBytes(Bytes.wrap("NONSENSE")).build();
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
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(contractCreateRecordBuilder.createdContractID(any(ContractID.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.createdEvmAddress(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.evmCreateTransactionResult(any())).willReturn(contractCreateRecordBuilder);
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
                DEFAULT_CONTRACTS_CONFIG,
                context,
                tinybarValues,
                gasCalculator,
                DEFAULT_HEDERA_CONFIG,
                ETHEREUM_TRANSACTION,
                pendingCreationMetadataRef,
                DEFAULT_ACCOUNTS_CONFIG,
                entityIdFactory,
                contractMetrics);
        final var someBody = ContractCreateTransactionBody.newBuilder()
                .adminKey(AN_ED25519_KEY)
                .autoRenewAccountId(NON_SYSTEM_ACCOUNT_ID)
                .autoRenewPeriod(SOME_DURATION)
                .build();
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);
        given(context.storeFactory()).willReturn(storeFactory);
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(contractCreateRecordBuilder.createdContractID(any(ContractID.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(context.dispatch(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status()).willReturn(SUCCESS);
        given(contractCreateRecordBuilder.createdEvmAddress(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.evmCreateTransactionResult(any())).willReturn(contractCreateRecordBuilder);
        given(context.payer()).willReturn(A_NEW_ACCOUNT_ID);

        subject.createContract(666L, someBody, CANONICAL_ALIAS);

        final var captor = ArgumentCaptor.forClass(DispatchOptions.class);
        verify(context).dispatch(captor.capture());
        assertNotSame(SUPPRESSING_SIGNED_TX_CUSTOMIZER, captor.getValue().signedTxCustomizer());
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
    void deleteUnaliasedContractExternalizesSuccessfulDeletion() throws ParseException {
        givenDeletableContract(CALLED_CONTRACT_ID, liveContract());
        final var childBuilder = givenDeletionRecordBuilder();

        subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID);

        verify(tokenServiceApi).deleteContract(CALLED_CONTRACT_ID);
        assertSyntheticDeletion(childBuilder);
    }

    @Test
    void deleteAliasedContractResolvesNumericIdBeforeRemovingAlias() throws ParseException {
        final var aliasedId =
                ContractID.newBuilder().evmAddress(CANONICAL_ALIAS).build();
        givenDeletableContract(aliasedId, liveContract());
        final var childBuilder = givenDeletionRecordBuilder();

        subject.deleteAliasedContract(CANONICAL_ALIAS, A_NEW_ACCOUNT_ID);

        final var inOrder = inOrder(accountStore, tokenServiceApi);
        inOrder.verify(accountStore).getContractById(aliasedId);
        inOrder.verify(tokenServiceApi).deleteContract(aliasedId);
        assertSyntheticDeletion(childBuilder);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledExternalizationStillDeletesWithoutConsultingChildRecordCapacity(final boolean aliased) {
        createSubject(HederaTestConfigBuilder.create()
                .withValue("contracts.selfDestructs.externalization.enabled", false)
                .getOrCreateConfig()
                .getConfigData(ContractsConfig.class));
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);

        if (aliased) {
            subject.deleteAliasedContract(CANONICAL_ALIAS, A_NEW_ACCOUNT_ID);
            verify(tokenServiceApi)
                    .deleteContract(
                            ContractID.newBuilder().evmAddress(CANONICAL_ALIAS).build());
        } else {
            subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID);
            verify(tokenServiceApi).deleteContract(CALLED_CONTRACT_ID);
        }

        verify(context, never()).savepointStack();
        verify(storeFactory, never()).readableStore(ReadableAccountStore.class);
        verify(context, never()).dispatch(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrAlreadyDeletedContractDoesNotExternalizeDeletion(final boolean alreadyDeleted) {
        givenDeletableContract(
                CALLED_CONTRACT_ID,
                alreadyDeleted ? liveContract().copyBuilder().deleted(true).build() : null);

        subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID);

        // Preserve the API's existing cleanup behavior without reporting a new deletion.
        verify(tokenServiceApi).deleteContract(CALLED_CONTRACT_ID);
        verify(context, never()).savepointStack();
    }

    @Test
    void deletionRequiresBeneficiary() {
        assertThrows(
                NullPointerException.class,
                () -> subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), null));
        assertThrows(NullPointerException.class, () -> subject.deleteAliasedContract(CANONICAL_ALIAS, null));
        verify(tokenServiceApi, never()).deleteContract(any());
    }

    @Test
    void deletionChildCapacityFailureBecomesResourceExhaustionBeforeStateMutation() {
        givenDeletableContract(CALLED_CONTRACT_ID, liveContract());
        given(context.savepointStack()).willReturn(stack);
        given(stack.addRemovableChildRecordBuilder(ContractDeleteStreamBuilder.class, CONTRACT_DELETE))
                .willThrow(new HandleException(MAX_CHILD_RECORDS_EXCEEDED));

        final var exception = assertThrows(
                ResourceExhaustedException.class,
                () -> subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID));

        assertEquals(MAX_CHILD_RECORDS_EXCEEDED, exception.getStatus());
        verify(tokenServiceApi, never()).deleteContract(any());
    }

    @Test
    void deletionPropagatesUnrelatedHandleException() {
        givenDeletableContract(CALLED_CONTRACT_ID, liveContract());
        given(context.savepointStack()).willReturn(stack);
        final var failure = new HandleException(INVALID_ACCOUNT_ID);
        given(stack.addRemovableChildRecordBuilder(ContractDeleteStreamBuilder.class, CONTRACT_DELETE))
                .willThrow(failure);

        final var exception = assertThrows(
                HandleException.class,
                () -> subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID));

        assertSame(failure, exception);
        verify(tokenServiceApi, never()).deleteContract(any());
    }

    @ParameterizedTest
    @EnumSource(StreamMode.class)
    void committedDeletionAppearsAsSuccessfulChildWithNoDuplicateTransfers(final StreamMode streamMode) {
        givenDeletableContract(CALLED_CONTRACT_ID, liveContract());
        final var realStack = givenRealSavepointStack(streamMode);

        subject.begin();
        subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID);
        subject.commit();
        realStack.commitFullStack();

        assertEquals(Map.of(liveContract().accountIdOrThrow(), A_NEW_ACCOUNT_ID), childBeneficiaries(realStack));
        final var records = outputRecords(realStack);
        assertEquals(2, records.size());
        final var parent = records.getFirst();
        final var deletion = records.getLast();
        assertEquals(SUCCESS, deletion.receiptOrThrow().status());
        assertEquals(CALLED_CONTRACT_ID, deletion.receiptOrThrow().contractID());
        assertEquals(1, deletion.transactionIDOrThrow().nonce());
        assertEquals(parent.consensusTimestamp(), deletion.parentConsensusTimestamp());
        assertEquals(0L, deletion.transactionFee());
        assertTrue(deletion.transferListOrThrow().accountAmounts().isEmpty());
        assertTrue(deletion.tokenTransferLists().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(StreamMode.class)
    void parentRollbackRemovesDeletionEvenAfterNestedScopeCommit(final StreamMode streamMode) {
        givenDeletableContract(CALLED_CONTRACT_ID, liveContract());
        final var realStack = givenRealSavepointStack(streamMode);

        subject.begin();
        subject.deleteUnaliasedContract(CALLED_CONTRACT_ID.contractNumOrThrow(), A_NEW_ACCOUNT_ID);
        subject.commit();
        assertEquals(Map.of(liveContract().accountIdOrThrow(), A_NEW_ACCOUNT_ID), childBeneficiaries(realStack));
        realStack.rollbackFullStack();
        realStack.commitFullStack();

        assertTrue(childBeneficiaries(realStack).isEmpty());
        final var records = outputRecords(realStack);
        assertEquals(1, records.size());
        assertFalse(records.getFirst().receiptOrThrow().hasContractID());
    }

    private void givenDeletableContract(final ContractID lookupId, final Account contract) {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(accountStore.getContractById(lookupId)).willReturn(contract);
    }

    private Account liveContract() {
        return Account.newBuilder()
                .accountId(entityIdFactory.newAccountId(CALLED_CONTRACT_ID.contractNumOrThrow()))
                .smartContract(true)
                .alias(CANONICAL_ALIAS)
                .build();
    }

    private RecordStreamBuilder givenDeletionRecordBuilder() {
        final var childBuilder = new RecordStreamBuilder(REMOVABLE, NOOP_SIGNED_TX_CUSTOMIZER, CHILD);
        given(context.savepointStack()).willReturn(stack);
        given(stack.addRemovableChildRecordBuilder(ContractDeleteStreamBuilder.class, CONTRACT_DELETE))
                .willReturn(childBuilder);
        return childBuilder;
    }

    private void assertSyntheticDeletion(final RecordStreamBuilder childBuilder) throws ParseException {
        final var child = childBuilder.build();
        final var signedTx =
                SignedTransaction.PROTOBUF.parse(child.transaction().signedTransactionBytes());
        final var body = TransactionBody.PROTOBUF.parse(signedTx.bodyBytes());
        assertEquals(
                ContractDeleteTransactionBody.newBuilder()
                        .contractID(CALLED_CONTRACT_ID)
                        .transferAccountID(A_NEW_ACCOUNT_ID)
                        .build(),
                body.contractDeleteInstanceOrThrow());
        assertEquals(SUCCESS, child.transactionRecord().receiptOrThrow().status());
        assertEquals(
                CALLED_CONTRACT_ID, child.transactionRecord().receiptOrThrow().contractID());
        assertEquals(0L, child.transactionRecord().transactionFee());
        assertEquals(1, childBuilder.getNumberOfDeletedAccounts());
        assertEquals(
                A_NEW_ACCOUNT_ID,
                childBuilder.getDeletedAccountBeneficiaryFor(liveContract().accountIdOrThrow()));
        assertEquals(REMOVABLE, childBuilder.reversingBehavior());
        verify(context, never()).dispatch(any());
    }

    private SavepointStackImpl givenRealSavepointStack(final StreamMode streamMode) {
        final var realStack = SavepointStackImpl.newRootStack(
                mock(State.class),
                0,
                10,
                mock(BoundaryStateChangeListener.class),
                mock(ImmediateStateChangeListener.class),
                streamMode,
                TraceDataSizeLimiter.NO_LIMIT);
        final var transactionId = TransactionID.newBuilder()
                .accountID(A_NEW_ACCOUNT_ID)
                .transactionValidStart(new Timestamp(1_234_567L, 890))
                .build();
        final var body = TransactionBody.newBuilder()
                .transactionID(transactionId)
                .contractCall(ContractCallTransactionBody.DEFAULT)
                .build();
        realStack
                .getBaseBuilder(StreamBuilder.class)
                .transactionID(transactionId)
                .signedTx(SignedTransaction.newBuilder()
                        .bodyBytes(TransactionBody.PROTOBUF.toBytes(body))
                        .build())
                .functionality(HederaFunctionality.CONTRACT_CALL)
                .status(SUCCESS)
                .exchangeRate(ExchangeRateSet.DEFAULT);
        given(context.savepointStack()).willReturn(realStack);
        return realStack;
    }

    private List<TransactionRecord> outputRecords(final SavepointStackImpl realStack) {
        return realStack
                .buildHandleOutput(Instant.ofEpochSecond(1_234_568L), ExchangeRateSet.DEFAULT)
                .singleTransactionRecords()
                .stream()
                .map(SingleTransactionRecord::transactionRecord)
                .toList();
    }

    private Map<AccountID, AccountID> childBeneficiaries(final SavepointStackImpl realStack) {
        final Map<AccountID, AccountID> beneficiaries = new HashMap<>();
        realStack.forEachNonBaseBuilder(
                DeleteCapableTransactionStreamBuilder.class,
                builder -> builder.forEachDeletedAccountBeneficiary(beneficiaries::put));
        return beneficiaries;
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
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(context.savepointStack()).willReturn(stack);
        given(stack.addRemovableChildRecordBuilder(ContractCreateStreamBuilder.class, CONTRACT_CREATE))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.createdContractID(contractId)).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.status(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.signedTx(any(SignedTransaction.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.contractCreateResult(any(ContractFunctionResult.class)))
                .willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.createdEvmAddress(any())).willReturn(contractCreateRecordBuilder);
        given(contractCreateRecordBuilder.evmCreateTransactionResult(any())).willReturn(contractCreateRecordBuilder);

        // when
        subject.externalizeHollowAccountMerge(contractId, VALID_CONTRACT_ADDRESS.evmAddress());

        // then
        verify(contractCreateRecordBuilder).createdContractID(contractId);
        verify(contractCreateRecordBuilder).contractCreateResult(any(ContractFunctionResult.class));
    }
}
