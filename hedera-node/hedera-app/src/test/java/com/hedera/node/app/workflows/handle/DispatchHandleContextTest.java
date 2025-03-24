// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.spi.authorization.SystemPrivilege.IMPERMISSIBLE;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.workflows.DispatchOptions.independentDispatch;
import static com.hedera.node.app.spi.workflows.DispatchOptions.setupDispatch;
import static com.hedera.node.app.spi.workflows.DispatchOptions.subDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static com.hedera.node.app.workflows.handle.steps.HollowAccountCompletionsTest.asTxn;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.ChildFeeContextImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.services.ServiceScopeLookup;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import com.hedera.node.app.spi.workflows.ComputeDispatchFeesAsTopLevel;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.DispatchOptions.PropagateFeeChargingStrategy;
import com.hedera.node.app.spi.workflows.DispatchOptions.StakingRewards;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.store.ServiceApiFactory;
import com.hedera.node.app.store.StoreFactoryImpl;
import com.hedera.node.app.store.WritableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.dispatch.ChildDispatchFactory;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.validation.AttributeValidatorImpl;
import com.hedera.node.app.workflows.handle.validation.ExpiryValidatorImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DispatchHandleContextTest extends StateTestBase implements Scenarios {
    private static final Fees FEES = new Fees(1L, 2L, 3L);
    public static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);
    private static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1_234).build();
    private static final AccountID NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3).build();
    private static final SignatureVerification FAILED_VERIFICATION =
            new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, false);
    private static final TransactionBody MISSING_FUNCTION_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .build();
    private static final TransactionBody CRYPTO_TRANSFER_TXN_BODY = TransactionBody.newBuilder()
            .transactionID(
                    TransactionID.newBuilder().accountID(PAYER_ACCOUNT_ID).build())
            .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
            .build();
    private static final TransactionInfo CRYPTO_TRANSFER_TXN_INFO = new TransactionInfo(
            Transaction.DEFAULT, CRYPTO_TRANSFER_TXN_BODY, SignatureMap.DEFAULT, Bytes.EMPTY, CRYPTO_TRANSFER, null);

    @Mock
    private AppKeyVerifier verifier;

    @Mock
    private FeeAccumulator feeAccumulator;

    @Mock
    private TransactionChecker transactionChecker;

    @Mock
    private NetworkInfo networkInfo;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private TransactionDispatcher dispatcher;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ServiceScopeLookup serviceScopeLookup;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock
    private ResourcePriceCalculator resourcePriceCalculator;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private Authorizer authorizer;

    @Mock
    private SignatureVerification verification;

    @Mock
    private ThrottleAdviser throttleAdviser;

    @Mock
    private EntityNumGenerator entityNumGenerator;

    @Mock
    private ChildDispatchFactory childDispatchFactory;

    @Mock
    private Dispatch childDispatch;

    @Mock
    private DispatchProcessor dispatchProcessor;

    @Mock(strictness = LENIENT)
    private State baseState;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<EntityNumber> entityNumberState;

    @Mock
    private WritableSingletonState<EntityCounts> entityCountsState;

    @Mock
    private ExchangeRateInfo exchangeRateInfo;

    @Mock
    private SavepointStackImpl stack;

    @Mock
    private WritableStoreFactory writableStoreFactory;

    @Mock
    private NodeInfo creatorInfo;

    @Mock
    private VerificationAssistant assistant;

    @Mock
    private Predicate<Key> signatureTest;

    @Mock
    private DeduplicationCache deduplicationCache;

    private ServiceApiFactory apiFactory;
    private ReadableStoreFactory readableStoreFactory;
    private StoreFactoryImpl storeFactory;
    private DispatchHandleContext subject;

    private static final AccountID payerId = ALICE.accountID();
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
    private static final TransactionBody txBody = asTxn(transferBody, payerId, CONSENSUS_NOW);
    private final Configuration configuration = HederaTestConfigBuilder.createConfig();
    private final RecordStreamBuilder childRecordBuilder =
            new RecordStreamBuilder(REVERSIBLE, NOOP_TRANSACTION_CUSTOMIZER, USER);
    private final TransactionBody txnBodyWithoutId = TransactionBody.newBuilder()
            .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
            .build();
    private static final TransactionInfo txnInfo = new TransactionInfo(
            Transaction.newBuilder().body(txBody).build(),
            txBody,
            SignatureMap.DEFAULT,
            Bytes.EMPTY,
            CRYPTO_TRANSFER,
            null);

    final PreHandleResult result =
            PreHandleResult.preHandleFailure(payerId, null, INVALID_PAYER_ACCOUNT_ID, txnInfo, null, null, null, null);

    @BeforeEach
    void setup() {
        when(serviceScopeLookup.getServiceName(any())).thenReturn(TokenService.NAME);
        readableStoreFactory = new ReadableStoreFactory(baseState);
        apiFactory = new ServiceApiFactory(stack, configuration);
        storeFactory = new StoreFactoryImpl(readableStoreFactory, writableStoreFactory, apiFactory);
        subject = createContext(txBody);

        mockNeeded();
    }

    @Test
    void numTxnSignaturesConsultsVerifier() {
        given(verifier.numSignaturesVerified()).willReturn(2);
        assertThat(subject.numTxnSignatures()).isEqualTo(2);
    }

    @Test
    void dispatchComputeFeesDelegatesWithBodyAndNotFree() {
        given(dispatcher.dispatchComputeFees(any())).willReturn(FEES);
        assertThat(subject.dispatchComputeFees(CRYPTO_TRANSFER_TXN_BODY, PAYER_ACCOUNT_ID))
                .isSameAs(FEES);
    }

    @Test
    void dispatchComputeThrowsWithMissingBody() {
        Assertions.assertThatThrownBy(() -> subject.dispatchComputeFees(MISSING_FUNCTION_TXN_BODY, PAYER_ACCOUNT_ID))
                .isInstanceOf(HandleException.class);
    }

    @Test
    void dispatchComputeFeesDelegatesWithFree() {
        given(authorizer.hasWaivedFees(PAYER_ACCOUNT_ID, CRYPTO_TRANSFER, CRYPTO_TRANSFER_TXN_BODY))
                .willReturn(true);
        assertThat(subject.dispatchComputeFees(CRYPTO_TRANSFER_TXN_BODY, PAYER_ACCOUNT_ID))
                .isSameAs(Fees.FREE);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void usesBlockRecordManagerForInfo() {
        assertThat(subject.blockRecordInfo()).isSameAs(blockRecordManager);
    }

    @Test
    void getsResourcePrices() {
        assertThat(subject.resourcePriceCalculator()).isNotNull();
    }

    @Test
    void getsFeeCalculator(@Mock FeeCalculator feeCalculator) {
        given(verifier.numSignaturesVerified()).willReturn(2);
        given(feeManager.createFeeCalculator(
                        any(),
                        eq(Key.DEFAULT),
                        eq(CRYPTO_TRANSFER_TXN_INFO.functionality()),
                        eq(2),
                        eq(0),
                        eq(CONSENSUS_NOW),
                        eq(TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES),
                        eq(false),
                        eq(readableStoreFactory)))
                .willReturn(feeCalculator);
        final var factory = subject.feeCalculatorFactory();
        assertThat(factory.feeCalculator(TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES))
                .isSameAs(feeCalculator);
    }

    @Test
    void getsAttributeValidator() {
        assertThat(subject.attributeValidator()).isInstanceOf(AttributeValidatorImpl.class);
    }

    @Test
    void getsExpiryValidator() {
        assertThat(subject.expiryValidator()).isInstanceOf(ExpiryValidatorImpl.class);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithInvalidArguments() {
        final var allArgs = new Object[] {
            CONSENSUS_NOW,
            creatorInfo,
            txnInfo,
            configuration,
            authorizer,
            blockRecordManager,
            resourcePriceCalculator,
            feeManager,
            storeFactory,
            payerId,
            verifier,
            CONTRACT_CALL,
            Key.newBuilder().build(),
            exchangeRateManager,
            stack,
            entityNumGenerator,
            dispatcher,
            networkInfo,
            childDispatchFactory,
            dispatchProcessor,
            throttleAdviser,
            feeAccumulator,
            EMPTY_METADATA,
            transactionChecker,
            List.of(result)
        };

        final var constructor = DispatchHandleContext.class.getConstructors()[0];
        for (int i = 0; i < allArgs.length; i++) {
            final var index = i;
            // Skip signatureMapSize, payerKey, and preHandleResults
            if (index == 2 || index == 4 || index == 24) {
                continue;
            }
            assertThatThrownBy(() -> {
                        final var argsWithNull = Arrays.copyOf(allArgs, allArgs.length);
                        argsWithNull[index] = null;
                        constructor.newInstance(argsWithNull);
                    })
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void getsExpectedValues() {
        assertThat(subject.body()).isSameAs(txBody);
        assertThat(subject.networkInfo()).isSameAs(networkInfo);
        assertThat(subject.payer()).isEqualTo(payerId);
        assertThat(subject.networkInfo()).isEqualTo(networkInfo);
        assertThat(subject.savepointStack()).isEqualTo(stack);
        assertThat(subject.configuration()).isEqualTo(configuration);
        assertThat(subject.authorizer()).isEqualTo(authorizer);
        assertThat(subject.storeFactory()).isEqualTo(storeFactory);
        assertThat(subject.entityNumGenerator()).isEqualTo(entityNumGenerator);
        assertThat(subject.keyVerifier()).isEqualTo(verifier);
    }

    @Nested
    @DisplayName("Handling of stack data")
    final class StackDataTest {
        @Test
        void testGetStack() {
            final var context = createContext(txBody);
            final var actual = context.savepointStack();
            assertThat(actual).isEqualTo(stack);
        }
    }

    @Nested
    @DisplayName("Requesting keys of child transactions")
    final class KeyRequestTest {
        @SuppressWarnings("ConstantConditions")
        @Test
        void testAllKeysForTransactionWithInvalidParameters() {
            final var bob = BOB.accountID();
            assertThatThrownBy(() -> subject.allKeysForTransaction(null, bob)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.allKeysForTransaction(txBody, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void testAllKeysForTransactionSuccess() throws PreCheckException {
            doAnswer(invocation -> {
                        final var innerContext = invocation.getArgument(0, PreHandleContext.class);
                        innerContext.requireKey(BOB.account().key());
                        innerContext.optionalKey(CAROL.account().key());
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            final var keys = subject.allKeysForTransaction(txBody, ERIN.accountID());
            assertThat(keys.payerKey()).isEqualTo(ERIN.account().key());
            assertThat(keys.requiredNonPayerKeys())
                    .containsExactly(BOB.account().key());
            assertThat(keys.optionalNonPayerKeys())
                    .containsExactly(CAROL.account().key());
        }

        @Test
        void testAllKeysForTransactionWithFailingPureCheck() throws PreCheckException {
            doThrow(new PreCheckException(INVALID_TRANSACTION_BODY))
                    .when(dispatcher)
                    .dispatchPureChecks(any());
            assertThatThrownBy(() -> subject.allKeysForTransaction(txBody, ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TRANSACTION_BODY));
        }

        @Test
        void testAllKeysForTransactionWithFailingPreHandle() throws PreCheckException {
            doThrow(new PreCheckException(INSUFFICIENT_ACCOUNT_BALANCE))
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // gathering keys should not throw exceptions except for inability to read a key.
            assertThatThrownBy(() -> subject.allKeysForTransaction(txBody, ERIN.accountID()))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(UNRESOLVABLE_REQUIRED_SIGNERS));
        }
    }

    @Nested
    @DisplayName("Dispatching fee computation")
    final class FeeDispatchTest {
        @SuppressWarnings("ConstantConditions")
        @Test
        void invokesComputeFeesDispatchWithChildFeeContextImpl() {
            final var fees = new Fees(1L, 2L, 3L);
            given(dispatcher.dispatchComputeFees(any())).willReturn(fees);
            final var captor = ArgumentCaptor.forClass(FeeContext.class);
            final var result = subject.dispatchComputeFees(txBody, account1002, ComputeDispatchFeesAsTopLevel.NO);
            verify(dispatcher).dispatchComputeFees(captor.capture());
            final var feeContext = captor.getValue();
            assertInstanceOf(ChildFeeContextImpl.class, feeContext);
            assertSame(fees, result);
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void invokesComputeFeesDispatchWithNoTransactionId() {
            final var fees = new Fees(1L, 2L, 3L);
            given(dispatcher.dispatchComputeFees(any())).willReturn(fees);
            final var captor = ArgumentCaptor.forClass(FeeContext.class);
            final var result =
                    subject.dispatchComputeFees(txnBodyWithoutId, account1002, ComputeDispatchFeesAsTopLevel.NO);
            verify(dispatcher).dispatchComputeFees(captor.capture());
            final var feeContext = captor.getValue();
            assertInstanceOf(ChildFeeContextImpl.class, feeContext);
            assertSame(fees, result);
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void failsAsExpectedWithoutAvailableApi() {
        assertThrows(
                IllegalArgumentException.class, () -> subject.storeFactory().serviceApi(Object.class));
    }

    @Nested
    @DisplayName("Handling of dispatcher")
    final class DispatcherTest {
        private static final Predicate<Key> VERIFIER_CALLBACK = key -> true;
        private static final String FOOD_SERVICE = "FOOD_SERVICE";
        private static final Map<String, String> BASE_DATA = Map.of(
                A_KEY, APPLE,
                B_KEY, BANANA,
                C_KEY, CHERRY,
                D_KEY, DATE,
                E_KEY, EGGPLANT,
                F_KEY, FIG,
                G_KEY, GRAPE);

        @Mock(strictness = LENIENT)
        private State baseState;

        @Mock(strictness = LENIENT, answer = Answers.RETURNS_SELF)
        private RecordStreamBuilder childRecordBuilder;

        @BeforeEach
        void setup() {
            final var baseKVState = new MapWritableKVState<>(FRUIT_STATE_KEY, new HashMap<>(BASE_DATA));
            final var writableStates =
                    MapWritableStates.builder().state(baseKVState).build();
            final var readableStates = MapReadableStates.builder()
                    .state(new MapReadableKVState(FRUIT_STATE_KEY, new HashMap<>(BASE_DATA)))
                    .build();
            when(baseState.getReadableStates(FOOD_SERVICE)).thenReturn(readableStates);
            when(baseState.getWritableStates(FOOD_SERVICE)).thenReturn(writableStates);
            final var accountsState = new MapWritableKVState<AccountID, Account>("ACCOUNTS");
            accountsState.put(ALICE.accountID(), ALICE.account());
            when(baseState.getWritableStates(TokenService.NAME))
                    .thenReturn(MapWritableStates.builder().state(accountsState).build());

            doAnswer(invocation -> {
                        final var childContext = invocation.getArgument(0, HandleContext.class);
                        final var childStack = (SavepointStackImpl) childContext.savepointStack();
                        childStack
                                .getWritableStates(FOOD_SERVICE)
                                .get(FRUIT_STATE_KEY)
                                .put(A_KEY, ACAI);
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchHandle(any());
        }

        @SuppressWarnings("ConstantConditions")
        @Test
        void testDispatchWithInvalidArguments() {
            assertThatThrownBy(() -> subject.dispatch(subDispatch(
                            AccountID.DEFAULT,
                            null,
                            VERIFIER_CALLBACK,
                            emptySet(),
                            StreamBuilder.class,
                            StakingRewards.ON,
                            UsePresetTxnId.NO,
                            NOOP_FEE_CHARGING,
                            PropagateFeeChargingStrategy.YES)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> subject.dispatch(subDispatch(
                            AccountID.DEFAULT,
                            txBody,
                            VERIFIER_CALLBACK,
                            emptySet(),
                            null,
                            StakingRewards.ON,
                            UsePresetTxnId.NO,
                            NOOP_FEE_CHARGING,
                            PropagateFeeChargingStrategy.YES)))
                    .isInstanceOf(NullPointerException.class);
        }

        private static Stream<Arguments> createContextDispatchers() {
            return Stream.of(Arguments.of(
                    (Consumer<HandleContext>) context ->
                            context.dispatch(independentDispatch(ALICE.accountID(), txBody, StreamBuilder.class)),
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatch(DispatchOptions.subDispatch(
                            ALICE.accountID(),
                            txBody,
                            VERIFIER_CALLBACK,
                            emptySet(),
                            StreamBuilder.class,
                            StakingRewards.OFF,
                            UsePresetTxnId.NO,
                            NOOP_FEE_CHARGING,
                            PropagateFeeChargingStrategy.YES))),
                    Arguments.of((Consumer<HandleContext>) context -> context.dispatch(
                            setupDispatch(ALICE.accountID(), txBody, StreamBuilder.class, NOOP_FEE_CHARGING)))));
        }

        @ParameterizedTest
        @MethodSource("createContextDispatchers")
        void testDispatchPreHandleFails(final Consumer<HandleContext> contextDispatcher) throws PreCheckException {
            final var txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder().accountID(ALICE.accountID()))
                    .consensusSubmitMessage(ConsensusSubmitMessageTransactionBody.DEFAULT)
                    .build();
            doThrow(new PreCheckException(ResponseCodeEnum.INVALID_TOPIC_ID))
                    .when(dispatcher)
                    .dispatchPureChecks(any());
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            contextDispatcher.accept(context);

            verify(dispatcher, never()).dispatchHandle(any());
        }

        @Test
        void testDispatchPrecedingWithNonEmptyStackDoesntFail() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);
            stack.createSavepoint();

            assertThatNoException()
                    .isThrownBy(() ->
                            context.dispatch(independentDispatch(AccountID.DEFAULT, txBody, StreamBuilder.class)));
            verify(dispatcher, never()).dispatchHandle(any());
            verify(stack).commitTransaction(any());
        }

        @Test
        void testDispatchPrecedingWithChangedDataDoesntFail() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);
            final Map<String, String> newData = new HashMap<>(BASE_DATA);
            newData.put(B_KEY, BLUEBERRY);

            assertThatNoException()
                    .isThrownBy(() ->
                            context.dispatch(independentDispatch(ALICE.accountID(), txBody, StreamBuilder.class)));
            assertThatNoException()
                    .isThrownBy((() ->
                            context.dispatch(independentDispatch(ALICE.accountID(), txBody, StreamBuilder.class))));
            verify(dispatchProcessor, times(2)).processDispatch(any());
        }

        @Test
        void testDispatchPrecedingIsCommitted() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            Mockito.lenient().when(verifier.verificationFor((Key) any())).thenReturn(verification);

            context.dispatch(independentDispatch(ALICE.accountID(), txBody, StreamBuilder.class));

            verify(dispatchProcessor).processDispatch(childDispatch);
            verify(stack).commitTransaction(any());
        }

        @Test
        void testRemovableDispatchPrecedingIsNotCommitted() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            Mockito.lenient().when(verifier.verificationFor((Key) any())).thenReturn(verification);

            context.dispatch(setupDispatch(ALICE.accountID(), txBody, StreamBuilder.class, NOOP_FEE_CHARGING));

            verify(dispatchProcessor).processDispatch(childDispatch);
            verify(stack, never()).commitFullStack();
        }

        @Test
        void testChildWithPaidRewardsUpdatedPaidRewards() {
            final var context = createContext(txBody, HandleContext.TransactionCategory.USER);

            Mockito.lenient().when(verifier.verificationFor((Key) any())).thenReturn(verification);
            given(childDispatch.streamBuilder()).willReturn(childRecordBuilder);
            given(childRecordBuilder.getPaidStakingRewards())
                    .willReturn(List.of(
                            AccountAmount.newBuilder()
                                    .accountID(PAYER_ACCOUNT_ID)
                                    .amount(+1)
                                    .build(),
                            AccountAmount.newBuilder()
                                    .accountID(NODE_ACCOUNT_ID)
                                    .amount(+2)
                                    .build()));
            assertThat(context.dispatchPaidRewards()).isSameAs(Collections.emptyMap());

            context.dispatch(subDispatch(
                    ALICE.accountID(),
                    txBody,
                    VERIFIER_CALLBACK,
                    emptySet(),
                    StreamBuilder.class,
                    StakingRewards.ON,
                    UsePresetTxnId.NO,
                    NOOP_FEE_CHARGING,
                    PropagateFeeChargingStrategy.YES));

            verify(dispatchProcessor).processDispatch(childDispatch);
            verify(stack, never()).commitFullStack();
            assertThat(context.dispatchPaidRewards())
                    .containsExactly(Map.entry(PAYER_ACCOUNT_ID, +1L), Map.entry(NODE_ACCOUNT_ID, +2L));
        }
    }

    @Test
    void testExchangeRateInfo() {
        assertSame(exchangeRateInfo, subject.exchangeRateInfo());
    }

    @Test
    void usesAssistantInVerification() {
        given(verifier.verificationFor(Key.DEFAULT, assistant)).willReturn(FAILED_VERIFICATION);
        assertThat(subject.keyVerifier().verificationFor(Key.DEFAULT, assistant))
                .isSameAs(FAILED_VERIFICATION);
    }

    @Test
    void getsPrivilegedAuthorization() {
        given(authorizer.hasPrivilegedAuthorization(payerId, txnInfo.functionality(), txnInfo.txBody()))
                .willReturn(IMPERMISSIBLE);
        assertThat(subject.hasPrivilegedAuthorization()).isSameAs(IMPERMISSIBLE);
    }

    private DispatchHandleContext createContext(final TransactionBody txBody) {
        return createContext(txBody, HandleContext.TransactionCategory.USER);
    }

    private DispatchHandleContext createContext(
            final TransactionBody txBody, final HandleContext.TransactionCategory category) {
        final HederaFunctionality function;
        try {
            function = functionOf(txBody);
        } catch (final UnknownHederaFunctionality e) {
            throw new RuntimeException(e);
        }

        final TransactionInfo txnInfo = new TransactionInfo(
                Transaction.newBuilder().body(txBody).build(),
                txBody,
                SignatureMap.DEFAULT,
                Bytes.EMPTY,
                function,
                null);
        final var result = PreHandleResult.preHandleFailure(
                payerId, null, INVALID_PAYER_ACCOUNT_ID, txnInfo, null, null, null, null);
        final var results = new ArrayList<PreHandleResult>();
        results.add(result);
        return new DispatchHandleContext(
                CONSENSUS_NOW,
                creatorInfo,
                txnInfo,
                configuration,
                authorizer,
                blockRecordManager,
                resourcePriceCalculator,
                feeManager,
                storeFactory,
                payerId,
                verifier,
                CRYPTO_TRANSFER,
                Key.DEFAULT,
                exchangeRateManager,
                stack,
                entityNumGenerator,
                dispatcher,
                networkInfo,
                childDispatchFactory,
                dispatchProcessor,
                throttleAdviser,
                feeAccumulator,
                EMPTY_METADATA,
                transactionChecker,
                results);
    }

    private void mockNeeded() {
        lenient()
                .when(childDispatchFactory.createChildDispatch(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(childDispatch);
        lenient().when(childDispatch.streamBuilder()).thenReturn(childRecordBuilder);
        lenient()
                .when(stack.getWritableStates(TokenService.NAME))
                .thenReturn(MapWritableStates.builder()
                        .state(MapWritableKVState.builder("ACCOUNTS").build())
                        .state(MapWritableKVState.builder("ALIASES").build())
                        .build());
        lenient().when(writableStates.<EntityNumber>getSingleton(anyString())).thenReturn(entityNumberState);
        lenient().when(writableStates.<EntityCounts>getSingleton(anyString())).thenReturn(entityCountsState);
        lenient().when(stack.getWritableStates(EntityIdService.NAME)).thenReturn(writableStates);
        lenient().when(stack.getReadableStates(TokenService.NAME)).thenReturn(defaultTokenReadableStates());
        lenient().when(exchangeRateManager.exchangeRateInfo(any())).thenReturn(exchangeRateInfo);
        given(baseState.getWritableStates(TokenService.NAME)).willReturn(writableStates);
        given(baseState.getReadableStates(TokenService.NAME)).willReturn(defaultTokenReadableStates());
        given(baseState.getReadableStates(EntityIdService.NAME)).willReturn(writableStates);
    }
}
