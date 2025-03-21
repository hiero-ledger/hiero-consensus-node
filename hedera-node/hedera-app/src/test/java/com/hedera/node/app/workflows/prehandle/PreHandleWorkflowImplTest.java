// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_KEY_SET_ON_NON_INNER_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.workflows.TransactionScenarioBuilder.scenario;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.UNKNOWN_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.nodeDueDiligenceFailure;
import static com.swirlds.platform.system.transaction.TransactionWrapperUtils.createAppPayloadWrapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.signature.DefaultKeyVerifier;
import com.hedera.node.app.signature.SignatureExpander;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionScenarioBuilder;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.transaction.Transaction;
import com.swirlds.platform.system.transaction.TransactionWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class PreHandleWorkflowImplTest extends AppTestBase implements Scenarios {

    private static final long DEFAULT_CONFIG_VERSION = 1L;

    /**
     * We use a mocked dispatcher, so it is easy to fake out interaction between the workflow and some "hypothetical"
     * transaction handlers.
     */
    @Mock
    private TransactionDispatcher dispatcher;

    /**
     * We use a mocked transaction checker, so it is easy to fake out the success or failure of the transaction
     * checker.
     */
    @Mock
    private TransactionChecker transactionChecker;

    /**
     * We use a mocked signature verifier, so it is easy to fake out the success or failure of signature verification.
     */
    @Mock
    private SignatureVerifier signatureVerifier;

    /** We use a mocked {@link SignatureExpander}, so it is easy to fake out expansion of signatures. */
    @Mock
    private SignatureExpander signatureExpander;

    /**
     * We use a mocked {@link ConfigProvider}, so it is easy to provide specific configurations.
     */
    @Mock(strictness = Strictness.LENIENT)
    private ConfigProvider configProvider;

    /** We use a mocked {@link DeduplicationCache}. */
    @Mock
    private DeduplicationCache deduplicationCache;

    /** We use a real functional store factory with our standard test data set. Needed by the workflow. */
    private ReadableStoreFactory storeFactory;

    /** The workflow under test. */
    private PreHandleWorkflow workflow;

    @BeforeEach
    void setUp() {
        final var fakeMerkleState = new FakeState();
        fakeMerkleState.addService(
                TokenService.NAME,
                Map.of(
                        "ACCOUNTS",
                        Map.of(
                                ALICE.accountID(), ALICE.account(),
                                BOB.accountID(),
                                        BOB.account()
                                                .copyBuilder()
                                                .deleted(true)
                                                .build(),
                                ERIN.accountID(), ERIN.account(),
                                STAKING_REWARD_ACCOUNT.accountID(), STAKING_REWARD_ACCOUNT.account()),
                        "ALIASES",
                        Collections.emptyMap()));
        fakeMerkleState.addService(
                EntityIdService.NAME,
                Map.of(
                        ENTITY_ID_STATE_KEY,
                        new AtomicReference<>(EntityNumber.newBuilder().build()),
                        ENTITY_COUNTS_KEY,
                        new AtomicReference<>(EntityCounts.DEFAULT)));
        storeFactory = new ReadableStoreFactory(fakeMerkleState, ServicesSoftwareVersion::new);

        final var config = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), DEFAULT_CONFIG_VERSION);
        when(configProvider.getConfiguration()).thenReturn(config);

        workflow = new PreHandleWorkflowImpl(
                dispatcher,
                transactionChecker,
                signatureVerifier,
                signatureExpander,
                configProvider,
                deduplicationCache);
    }

    /**
     * This suite of tests verifies that should we encounter unexpected failures in our code, we will still behave in a
     * safe and consistent way.
     */
    @Nested
    @DisplayName("Handling of exceptions caused by bugs in our code")
    final class ExceptionTest {
        private TransactionWrapper platformTx;
        private Stream<Transaction> transactions;

        @BeforeEach
        void setUp() throws PreCheckException {
            final var txInfo = scenario().withPayer(ALICE.accountID()).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            platformTx = createAppPayloadWrapper(txBytes);
            final List<Transaction> list = List.of(platformTx);
            transactions = list.stream();
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
        }

        /**
         * Maybe some random exception happens during pre handle. This is <b>definitely</b> not expected. But if it
         * does, we should still behave in a safe and consistent way. We should fail with "UNKNOWN", and will be retried
         * again during handle. Should it happen again in handle, the node will likely ISS and restart and reconnect,
         * which is a perfectly acceptable outcome.
         */
        @Test
        @DisplayName("Unknown failure due to random exception during handling leads to \"unknown\" failure response")
        void timeoutExceptionDueToRandomErrorLeadsToUnknownFailureResponseTest() throws PreCheckException {
            doAnswer(invocation -> {
                        throw new Exception("Random error!");
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            workflow.preHandle(storeFactory, NODE_1.asInfo(), transactions, txns -> {});
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(UNKNOWN);
            assertThat(result.status()).isEqualTo(UNKNOWN_FAILURE);
        }
    }

    /**
     * Due diligence tests cover scenarios where a possibly dishonest or broken node sends transactions to other nodes
     * that it shouldn't have sent. For example, if the protobuf bytes cannot even be parsed, then the node has not
     * performed its due diligence and should be charged for this waste of resources.
     */
    @Nested
    @DisplayName("Due-diligence tests")
    @ExtendWith(MockitoExtension.class)
    final class DueDiligenceTests implements Scenarios {

        /**
         * A dishonest node may send, in an event, a transaction that cannot be parsed. It might just be random bytes.
         * Or no bytes. Or too many bytes. In all of those cases, we should immediately terminate with a
         * {@link PreHandleResult} that as a response code, a payer for the node that sent the transaction.
         *
         * <p>Or, after successfully parsing the transaction from protobuf bytes, we perform a whole set of syntactic
         * checks on the transaction using the {@link TransactionChecker}. We don't need to verify every possible bad
         * transaction here (since the tests for {@link TransactionChecker} do that). If **any** failure happens due to
         * a syntactic check, we should immediately terminate with a {@link PreHandleResult} that has the response code
         * of the failure and the payer should be node (as it failed due-diligence checks).
         *
         * <p>Both cases look the same to the handler.
         */
        @Test
        @DisplayName("Fail pre-handle with an attempt to parse invalid protobuf bytes")
        void preHandleBadBytes() throws PreCheckException {
            // Given a transaction that has bad bytes (and therefore fails to parse)
            final Transaction platformTx = createAppPayloadWrapper(randomByteArray(123));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt()))
                    .thenThrow(new PreCheckException(INVALID_TRANSACTION));

            // When we try to pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then we get a failure with INVALID_TRANSACTION
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(INVALID_TRANSACTION);
            assertThat(result.payer()).isEqualTo(NODE_1.nodeAccountID());
        }

        /**
         * It may be that while performing syntactics check we encounter some random {@link Throwable}. If that happens,
         * then the {@link PreHandleResult} will have a status of {@link ResponseCodeEnum#UNKNOWN} and the payer will be
         * the node. But we'll end up trying this again later during the handle flow.
         */
        @Test
        @DisplayName("Fail pre-handle with failed syntactic check with an unknown exception")
        void preHandleFailedSyntacticCheckWithUnknownException() throws PreCheckException {
            // Given a transaction that fails due-diligence checks for some random throwable
            final Transaction platformTx = createAppPayloadWrapper(randomByteArray(123));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt()))
                    .thenThrow(new RuntimeException("Random"));

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // The throwable is caught, and we get an UNKNOWN status code
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(UNKNOWN);
            assertThat(result.payer()).isNull();
        }

        /**
         * It may be that when the transaction is pre-handled, it refers to an account that does not yet exist. This may
         * happen because the transaction is bad, or it may happen because we do not yet have an account object (maybe
         * another in-flight transaction will create it). But every node as part of its due-diligence has to verify the
         * payer signature on the transaction prior to submitting the transaction to the network. So if the payer
         * account does not exist, then the node failed due-diligence and should pay for the transaction.
         */
        @Test
        @DisplayName("Fail pre-handle because the payer account cannot be found")
        void preHandlePayerAccountNotFound() throws PreCheckException {
            // Given a transactionID that refers to an account that does not exist
            // (Erin doesn't exist yet)
            final var txInfo = scenario().withPayer(FRANK.accountID()).txInfo();

            final Transaction platformTx = createAppPayloadWrapper(asByteArray(txInfo.transaction()));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction fails and the node is the payer
            final PreHandleResult result1 = platformTx.getMetadata();
            assertThat(result1.responseCode()).isEqualTo(PAYER_ACCOUNT_NOT_FOUND);
            assertThat(result1.payer()).isEqualTo(NODE_1.nodeAccountID());
            // But we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        /**
         * It may be that when the transaction is pre-handled, it refers to an account that was deleted. This may
         * happen because the transaction is bad, or it may happen because we do not yet have an account object (maybe
         * another in-flight transaction will create it). But every node as part of its due-diligence has to verify the
         * payer signature on the transaction prior to submitting the transaction to the network. So if the payer
         * account was deleted, then the node failed due-diligence and should pay for the transaction.
         */
        @Test
        @DisplayName("Fail pre-handle because the payer account deleted")
        void preHandlePayerAccountDeleted() throws PreCheckException {
            // Given a transactionID that refers to an account that was deleted
            final var txInfo = scenario().withPayer(BOB.accountID()).txInfo();

            final Transaction platformTx = createAppPayloadWrapper(asByteArray(txInfo.transaction()));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction fails and the node is the payer
            final PreHandleResult result1 = platformTx.getMetadata();
            assertThat(result1.responseCode()).isEqualTo(PAYER_ACCOUNT_DELETED);
            assertThat(result1.payer()).isEqualTo(NODE_1.nodeAccountID());
            // But we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        /**
         * The transaction submitted by the user may simply be missing the payer signature. Maybe the payer in the
         * transaction is a valid account ID, and maybe the account exists, but maybe the payer never signed the
         * transaction. In that case, the node failed due diligence again and should pay for the transaction. True, a
         * transaction that would put the proper key on the account may be in-flight, but we never should have gotten to
         * this point if the node had performed proper due-diligence.
         */
        @Test
        @DisplayName("Payer signature is invalid")
        void payerSignatureInvalid(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction with a signature that doesn't work out
            final var txInfo = scenario().withPayer(ALICE.accountID()).txInfo();

            final Transaction platformTx = createAppPayloadWrapper(asByteArray(txInfo.transaction()));
            final var key = ALICE.keyInfo().publicKey();
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of(key, sigFuture));
            when(sigFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(key, null, false));

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction still succeeds (since the payer signature check is async)
            final PreHandleResult result1 = platformTx.getMetadata();
            assertThat(result1.responseCode()).isEqualTo(OK);
            assertThat(result1.payer()).isEqualTo(ALICE.accountID());

            // But when we check the future for the signature, we find it will end up failing.
            // (And the handle workflow will deal with this)
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result1.verificationResults());
            final var result = verifier.verificationFor(key);
            assertThat(result.passed()).isFalse();
            // And we do NOT see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        /**
         * If a node's event contains transactions that DO NOT have that node's account as the node account ID of the
         * transaction, then the node is trying to send transactions that don't belong to it.
         */
        @Test
        @DisplayName("Fail pre-handle because the transaction is not created by the creator")
        void preHandleCreatorAccountNotTxNodeAccount() throws PreCheckException {
            // Given a transactionID that refers to an account OTHER THAN the creator node account.
            // The creator in this scenario is NODE_1.
            final var txInfo =
                    scenario().withNodeAccount(NODE_2.nodeAccountID()).txInfo();

            final Transaction platformTx = createAppPayloadWrapper(asByteArray(txInfo.transaction()));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction fails and the node is the payer
            final PreHandleResult result1 = platformTx.getMetadata();
            assertThat(result1.responseCode()).isEqualTo(INVALID_NODE_ACCOUNT);
            assertThat(result1.payer()).isEqualTo(NODE_1.nodeAccountID());
            // But we do see this transaction registered with the deduplication cache
            verifyNoInteractions(deduplicationCache);
        }

        /**
         * If a node's event contains transactions that DO NOT have that node's account as the node account ID of the
         * transaction, then the node is trying to send transactions that don't belong to it.
         */
        @Test
        @DisplayName("Unparseable previous result is reused")
        void reusesUnparseableTransactionResult() {
            // Given the result of an unparseable transaction that is perfectly good
            final var previousResult =
                    nodeDueDiligenceFailure(NODE_1.nodeAccountID(), INVALID_TRANSACTION, null, DEFAULT_CONFIG_VERSION);

            // When we pre-handle the transaction
            final var result = workflow.preHandleAllTransactions(
                    NODE_1.asInfo(),
                    storeFactory,
                    storeFactory.getStore(ReadableAccountStore.class),
                    createAppPayloadWrapper(new byte[2]).getApplicationTransaction(),
                    previousResult,
                    txns -> {});

            // Then the entire result is re-used
            assertThat(result).isSameAs(previousResult);
        }
    }

    /**
     * After passing all due-diligence checks we gather signatures for signature verification.
     */
    @Nested
    @DisplayName("Transaction Handler pre-handle tests")
    @ExtendWith(MockitoExtension.class)
    final class TransactionHandlerPreHandleTests {
        /**
         * If the transaction has a valid payer, then we next need to perform the pre-handle call on the dispatcher. It
         * may fail with a {@link PreCheckException}. If it does, this response code must be propagated.
         */
        @Test
        @DisplayName("Pre-handle semantic checks fail with PreCheckException")
        void preHandleSemanticChecksFail(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction that fails the semantic check to the transaction handler
            // (NOTE that INVALID_ACCOUNT_AMOUNTS is one such semantic failure scenario)
            final var txInfo = scenario().withPayer(ALICE.accountID()).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            final var key = ALICE.keyInfo().publicKey();
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of(key, sigFuture));
            doThrow(new PreCheckException(INVALID_ACCOUNT_AMOUNTS))
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction failure is INVALID_ACCOUNT_AMOUNTS and the payer is the payer
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(INVALID_ACCOUNT_AMOUNTS);
            assertThat(result.payer()).isEqualTo(ALICE.accountID());
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        /**
         * Perhaps when calling pre-handle on the dispatcher, some random {@link RuntimeException} is thrown, in which
         * case the transaction will fail with a {@link ResponseCodeEnum#UNKNOWN}.
         */
        @Test
        @DisplayName("Pre-handle warming fails with RuntimeException")
        void preHandleWarmingFails() throws PreCheckException {
            // Given a transaction that fails in pre-handle with some random exception
            final var txInfo = scenario().withPayer(ALICE.accountID()).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            doThrow(new RuntimeException()).when(dispatcher).dispatchPreHandle(any());

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction failure is UNKNOWN and the payer is null. There can be no payer in this case.
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(UNKNOWN);
            assertThat(result.payer()).isNull();
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        /**
         * Signature verification is done in a background thread. We store {@link Future}s for the results of those
         * verifications on the {@link PreHandleResult}. If verification failed, we should see it on that future.
         */
        @Test
        @DisplayName("Signature verification fails for non-payer signatures")
        void nonPayerSignatureInvalid(
                @Mock SignatureVerificationFuture goodFuture, @Mock SignatureVerificationFuture badFuture)
                throws Exception {
            // Given a good transaction with a bad non-payer signature
            final var payerAccount = ALICE.accountID();
            final var payerKey = ALICE.keyInfo().publicKey();
            final var badKey = BOB.keyInfo().publicKey();
            final var txInfo = scenario().withPayer(payerAccount).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(goodFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(payerKey, null, true));
            when(badFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(badKey, null, false));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(
                            payerKey, goodFuture, // Payer check passes
                            badKey, badFuture)); // Sig checks fail
            doAnswer(invocation -> {
                        final var ctx = invocation.getArgument(0, PreHandleContext.class);
                        ctx.requireKey(badKey); // we need a non-payer key
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction succeeds
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(payerAccount);
            // and the payer sig check succeeds
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(payerKey);
            assertThat(payerFutureResult.passed()).isTrue();
            // but the other checks fail
            final var nonPayerFutureResult = verifier.verificationFor(badKey);
            assertThat(nonPayerFutureResult.passed()).isFalse();
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        @Test
        @DisplayName("Pre-handle batch key checks fail")
        void preHandleTransactionWithBatchKeyFail(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            final var key = ALICE.keyInfo().publicKey();
            final var txInfo = scenario().withBatchKey(key).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);

            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.responseCode()).isEqualTo(BATCH_KEY_SET_ON_NON_INNER_TRANSACTION);
        }
    }

    /**
     * Tests the normal happy path. A transaction is valid, the payer account exists, and all verification tests pass.
     * 🎉
     */
    @Nested
    @DisplayName("Happy Path Tests")
    @ExtendWith(MockitoExtension.class)
    final class HappyPathTests {
        @Test
        @DisplayName("Happy path with Key-based signature verification")
        void happyPath(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction that is perfectly good
            final var payerAccount = ALICE.accountID();
            final var payerKey = ALICE.keyInfo().publicKey();
            final var txInfo = scenario().withPayer(payerAccount).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(sigFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(payerKey, null, true));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of(payerKey, sigFuture));

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction pre-handle succeeds!
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.status()).isEqualTo(SO_FAR_SO_GOOD);
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(ALICE.accountID());
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(payerKey);
            assertThat(payerFutureResult.passed()).isTrue();
            assertThat(result.txInfo()).isNotNull();
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.configVersion()).isEqualTo(DEFAULT_CONFIG_VERSION);
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        @Test
        @DisplayName(
                "Happy path with Key-based signature verification and a result derived from different config version")
        void happyPathWithoutReuse(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction that is perfectly good
            final var payerAccount = ALICE.accountID();
            final var payerKey = ALICE.keyInfo().publicKey();
            final var txInfo = scenario().withPayer(payerAccount).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(sigFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(payerKey, null, true));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of(payerKey, sigFuture));
            final var previousResult = new PreHandleResult(
                    payerAccount,
                    payerKey,
                    SO_FAR_SO_GOOD,
                    OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Map.of(payerKey, sigFuture),
                    null,
                    DEFAULT_CONFIG_VERSION + 1);

            // When we pre-handle the transaction
            final var result = workflow.preHandleAllTransactions(
                    NODE_1.asInfo(),
                    storeFactory,
                    storeFactory.getStore(ReadableAccountStore.class),
                    platformTx.getApplicationTransaction(),
                    previousResult,
                    txns -> {});

            // Then the transaction pre-handle succeeds!
            assertThat(result.status()).isEqualTo(SO_FAR_SO_GOOD);
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(ALICE.accountID());
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(payerKey);
            assertThat(payerFutureResult.passed()).isTrue();
            assertThat(result.txInfo()).isNotNull();
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.configVersion()).isEqualTo(DEFAULT_CONFIG_VERSION);
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        @Test
        @DisplayName("Happy path for atomic batch transaction reusing previous verification results")
        void happyPathWithoutReuseForAtomicBatch(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction that is perfectly good
            final var payerAccount = ALICE.accountID();
            final var payerKey = ALICE.keyInfo().publicKey();
            final var batchTxInfo = scenario().withPayer(payerAccount).txInfoForBatch();
            final var innerTxInfo = scenario().withPayer(payerAccount).txInfo();
            final var txBytes = asByteArray(batchTxInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(sigFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(payerKey, null, true));
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt()))
                    .thenReturn(batchTxInfo)
                    .thenReturn(innerTxInfo);
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of(payerKey, sigFuture));
            final var previousResult = new PreHandleResult(
                    payerAccount,
                    payerKey,
                    SO_FAR_SO_GOOD,
                    OK,
                    new TransactionScenarioBuilder().txInfoForBatch(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Map.of(payerKey, sigFuture),
                    new ArrayList<>(),
                    DEFAULT_CONFIG_VERSION + 1);

            // When we pre-handle the transaction
            final var result = workflow.preHandleAllTransactions(
                    NODE_1.asInfo(),
                    storeFactory,
                    storeFactory.getStore(ReadableAccountStore.class),
                    platformTx.getApplicationTransaction(),
                    previousResult,
                    txns -> {});

            // Then the transaction pre-handle succeeds!
            assertThat(result.status()).isEqualTo(SO_FAR_SO_GOOD);
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(ALICE.accountID());
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(payerKey);
            assertThat(payerFutureResult.passed()).isTrue();
            assertThat(result.txInfo()).isSameAs(batchTxInfo);

            assertThat(result.innerResults()).size().isEqualTo(1);
            final var innerResult = result.innerResults().get(0);
            assertThat(innerResult.txInfo()).isEqualTo(innerTxInfo);
            final AppKeyVerifier innerVerifier = new DefaultKeyVerifier(1, config, innerResult.verificationResults());
            final var innerPayerResult = innerVerifier.verificationFor(payerKey);
            assertThat(innerPayerResult.passed()).isTrue();
            // And we do see this transaction and inner transaction registered with the deduplication cache
            verify(deduplicationCache, times(2)).add(batchTxInfo.txBody().transactionIDOrThrow());
        }

        @Test
        @DisplayName("Happy path with Key-based signature verification re-using previous verification results")
        void happyPathWithFullReuseOfPreviousResult(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction that is perfectly good
            final var payerAccount = ALICE.accountID();
            final var payerKey = ALICE.keyInfo().publicKey();
            final var txInfo = scenario().withPayer(payerAccount).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(sigFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(payerKey, null, true));
            final var previousResult = new PreHandleResult(
                    payerAccount,
                    payerKey,
                    SO_FAR_SO_GOOD,
                    OK,
                    new TransactionScenarioBuilder().txInfo(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Map.of(payerKey, sigFuture),
                    null,
                    DEFAULT_CONFIG_VERSION);

            // When we pre-handle the transaction
            final var result = workflow.preHandleAllTransactions(
                    NODE_1.asInfo(),
                    storeFactory,
                    storeFactory.getStore(ReadableAccountStore.class),
                    platformTx.getApplicationTransaction(),
                    previousResult,
                    txns -> {});

            // Then the transaction pre-handle succeeds!
            assertThat(result.status()).isEqualTo(SO_FAR_SO_GOOD);
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(ALICE.accountID());
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(payerKey);
            assertThat(payerFutureResult.passed()).isTrue();
            assertThat(result.txInfo()).isNotNull();
            assertThat(result.txInfo()).isSameAs(previousResult.txInfo());
            assertThat(result.configVersion()).isEqualTo(DEFAULT_CONFIG_VERSION);
            // And we do see this transaction registered with the deduplication cache
            verifyNoInteractions(deduplicationCache);
        }

        @Test
        @DisplayName("Happy path with a Hollow Account payer")
        void happyPathHollowAccountAsPayer(@Mock SignatureVerificationFuture sigFuture) throws Exception {
            // Given a transaction that is perfectly good, with a hollow account for the payer (!)
            final var hollowAccountAlias = ERIN.account().alias();
            final var hollowAccountID = ERIN.accountID();
            final var finalizedKey = ERIN.keyInfo().publicKey();
            final var txInfo = scenario().withPayer(hollowAccountID).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any())).thenReturn(Map.of(finalizedKey, sigFuture));
            when(sigFuture.evmAlias()).thenReturn(hollowAccountAlias);
            when(sigFuture.get(anyLong(), any()))
                    .thenReturn(new SignatureVerificationImpl(finalizedKey, hollowAccountAlias, true));

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction pre-handle succeeds!
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.status()).isEqualTo(SO_FAR_SO_GOOD);
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(hollowAccountID);
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(hollowAccountAlias);
            assertThat(payerFutureResult.passed()).isTrue();
            assertThat(payerFutureResult.evmAlias()).isEqualTo(hollowAccountAlias);
            assertThat(payerFutureResult.key()).isEqualTo(finalizedKey);
            assertThat(result.txInfo()).isNotNull();
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.configVersion()).isEqualTo(DEFAULT_CONFIG_VERSION);
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }

        @Test
        @DisplayName("Happy path with a required non-payer Hollow Account")
        void happyPathHollowAccountsNonPayer(
                @Mock SignatureVerificationFuture payerSigFuture, @Mock SignatureVerificationFuture nonPayerSigFuture)
                throws Exception {
            // Given a transaction that is perfectly good
            final var payerAccountID = ALICE.accountID();
            final var payerKey = ALICE.keyInfo().publicKey();
            final var hollowAccount = ERIN.account();
            final var hollowAccountAlias = hollowAccount.alias();
            final var finalizedKey = ERIN.keyInfo().publicKey();
            final var txInfo = scenario().withPayer(payerAccountID).txInfo();
            final var txBytes = asByteArray(txInfo.transaction());
            final Transaction platformTx = createAppPayloadWrapper(txBytes);
            when(transactionChecker.parseAndCheck(any(Bytes.class), anyInt())).thenReturn(txInfo);
            when(signatureVerifier.verify(any(), any()))
                    .thenReturn(Map.of(payerKey, payerSigFuture, finalizedKey, nonPayerSigFuture));
            when(payerSigFuture.get(anyLong(), any())).thenReturn(new SignatureVerificationImpl(payerKey, null, true));
            when(nonPayerSigFuture.get(anyLong(), any()))
                    .thenReturn(new SignatureVerificationImpl(finalizedKey, hollowAccountAlias, true));
            when(nonPayerSigFuture.evmAlias()).thenReturn(hollowAccountAlias);
            doAnswer(invocation -> {
                        final var ctx = invocation.getArgument(0, PreHandleContext.class);
                        ctx.requireSignatureForHollowAccount(hollowAccount); // we need a hollow account
                        return null;
                    })
                    .when(dispatcher)
                    .dispatchPreHandle(any());

            // When we pre-handle the transaction
            workflow.preHandle(storeFactory, NODE_1.asInfo(), Stream.of(platformTx), txns -> {});

            // Then the transaction pre-handle succeeds!
            final PreHandleResult result = platformTx.getMetadata();
            assertThat(result.status()).isEqualTo(SO_FAR_SO_GOOD);
            assertThat(result.responseCode()).isEqualTo(OK);
            assertThat(result.payer()).isEqualTo(payerAccountID);
            // and the payer sig check succeeds
            final var config = configProvider.getConfiguration().getConfigData(HederaConfig.class);
            final AppKeyVerifier verifier = new DefaultKeyVerifier(1, config, result.verificationResults());
            final var payerFutureResult = verifier.verificationFor(payerKey);
            assertThat(payerFutureResult.passed()).isTrue();
            // and the non-payer sig check for the hollow account works
            final var nonPayerResult = verifier.verificationFor(hollowAccountAlias);
            assertThat(nonPayerResult.evmAlias()).isEqualTo(hollowAccountAlias);
            assertThat(nonPayerResult.key()).isEqualTo(finalizedKey);
            assertThat(result.txInfo()).isNotNull();
            assertThat(result.txInfo()).isSameAs(txInfo);
            assertThat(result.configVersion()).isEqualTo(DEFAULT_CONFIG_VERSION);
            // And we do see this transaction registered with the deduplication cache
            verify(deduplicationCache).add(txInfo.txBody().transactionIDOrThrow());
        }
    }
}
