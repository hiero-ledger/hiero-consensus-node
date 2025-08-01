// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static com.hedera.hapi.node.base.HederaFunctionality.ATOMIC_BATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_ADD_LIVE_HASH;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_DELETE_LIVE_HASH;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FREEZE;
import static com.hedera.hapi.node.base.HederaFunctionality.LAMBDA_S_STORE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_UNDELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CREATING_SYSTEM_ENTITIES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_NOT_ACTIVE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WAITING_FOR_LEDGER_ID;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.workflows.InnerTransaction.NO;
import static com.hedera.node.app.workflows.InnerTransaction.YES;
import static com.hedera.node.app.workflows.handle.dispatch.DispatchValidator.WorkflowCheck.INGEST;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.annotations.NodeSelfId;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.FeeContextImpl;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.hapi.utils.EthSigsUtils;
import com.hedera.node.app.info.CurrentPlatformStatus;
import com.hedera.node.app.signature.DefaultKeyVerifier;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.SignatureExpander;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.throttle.ThrottleUsage;
import com.hedera.node.app.workflows.InnerTransaction;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.SolvencyPreCheck;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionChecker.RequireMinValidLifetimeBuffer;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.purechecks.PureChecksContextImpl;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code IngestChecker} contains checks that are specific to the ingest workflow
 */
@Singleton
public final class IngestChecker {
    private static final Logger logger = LogManager.getLogger(IngestChecker.class);
    private static final Set<HederaFunctionality> FEATURE_FLAGGED_TRANSACTIONS =
            EnumSet.of(LAMBDA_S_STORE, CRYPTO_CREATE, CONTRACT_CREATE, CRYPTO_UPDATE, CONTRACT_UPDATE, CRYPTO_TRANSFER);
    private static final Set<HederaFunctionality> UNSUPPORTED_TRANSACTIONS =
            EnumSet.of(CRYPTO_ADD_LIVE_HASH, CRYPTO_DELETE_LIVE_HASH);
    private static final Set<HederaFunctionality> PRIVILEGED_TRANSACTIONS =
            EnumSet.of(FREEZE, SYSTEM_DELETE, SYSTEM_UNDELETE);

    private final CurrentPlatformStatus currentPlatformStatus;
    private final BlockStreamManager blockStreamManager;
    private final TransactionChecker transactionChecker;
    private final SolvencyPreCheck solvencyPreCheck;
    private final SignatureVerifier signatureVerifier;
    private final SignatureExpander signatureExpander;
    private final DeduplicationCache deduplicationCache;
    private final TransactionDispatcher dispatcher;
    private final FeeManager feeManager;
    private final AccountID nodeAccount;
    private final Authorizer authorizer;
    private final SynchronizedThrottleAccumulator synchronizedThrottleAccumulator;
    private final InstantSource instantSource;
    private final OpWorkflowMetrics workflowMetrics;

    @Nullable
    private final AtomicBoolean systemEntitiesCreatedFlag;

    /**
     * The result of running all checks.
     */
    public static class Result {
        @Nullable
        private TransactionInfo txnInfo;

        private List<ThrottleUsage> throttleUsages = new ArrayList<>();

        public @NonNull TransactionInfo txnInfoOrThrow() {
            return requireNonNull(txnInfo);
        }

        public void setTxnInfo(@Nullable TransactionInfo txnInfo) {
            this.txnInfo = txnInfo;
        }

        public @NonNull List<ThrottleUsage> throttleUsages() {
            return throttleUsages;
        }

        public void setThrottleUsages(@Nullable List<ThrottleUsage> throttleUsages) {
            this.throttleUsages = throttleUsages;
        }
    }

    /**
     * Constructor of the {@code IngestChecker}
     *
     * @param nodeAccount the {@link AccountID} of the node
     * @param currentPlatformStatus the {@link CurrentPlatformStatus} that contains the current status of the platform
     * @param transactionChecker the {@link TransactionChecker} that pre-processes the bytes of a transaction
     * @param solvencyPreCheck the {@link SolvencyPreCheck} that checks payer balance
     * @param signatureExpander the {@link SignatureExpander} that expands signatures
     * @param signatureVerifier the {@link SignatureVerifier} that verifies signature data
     * @param dispatcher the {@link TransactionDispatcher} that dispatches transactions
     * @param feeManager the {@link FeeManager} that manages {@link com.hedera.node.app.spi.fees.FeeCalculator}s
     * @param synchronizedThrottleAccumulator the {@link SynchronizedThrottleAccumulator} that checks transaction should be throttled
     * @param instantSource the {@link InstantSource} that provides the current time
     * @param workflowMetrics the {@link OpWorkflowMetrics} that manages the metrics for all operations
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Inject
    public IngestChecker(
            @NodeSelfId @NonNull final AccountID nodeAccount,
            @NonNull final CurrentPlatformStatus currentPlatformStatus,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final TransactionChecker transactionChecker,
            @NonNull final SolvencyPreCheck solvencyPreCheck,
            @NonNull final SignatureExpander signatureExpander,
            @NonNull final SignatureVerifier signatureVerifier,
            @NonNull final DeduplicationCache deduplicationCache,
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final FeeManager feeManager,
            @NonNull final Authorizer authorizer,
            @NonNull final SynchronizedThrottleAccumulator synchronizedThrottleAccumulator,
            @NonNull final InstantSource instantSource,
            @NonNull final OpWorkflowMetrics workflowMetrics,
            @Nullable final AtomicBoolean systemEntitiesCreatedFlag) {
        this.nodeAccount = requireNonNull(nodeAccount, "nodeAccount must not be null");
        this.currentPlatformStatus = requireNonNull(currentPlatformStatus, "currentPlatformStatus must not be null");
        this.blockStreamManager = requireNonNull(blockStreamManager, "blockStreamManager must not be null");
        this.transactionChecker = requireNonNull(transactionChecker, "transactionChecker must not be null");
        this.solvencyPreCheck = requireNonNull(solvencyPreCheck, "solvencyPreCheck must not be null");
        this.signatureVerifier = requireNonNull(signatureVerifier, "signatureVerifier must not be null");
        this.signatureExpander = requireNonNull(signatureExpander, "signatureExpander must not be null");
        this.deduplicationCache = requireNonNull(deduplicationCache, "deduplicationCache must not be null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null");
        this.feeManager = requireNonNull(feeManager, "feeManager must not be null");
        this.authorizer = requireNonNull(authorizer, "authorizer must not be null");
        this.synchronizedThrottleAccumulator =
                requireNonNull(synchronizedThrottleAccumulator, "synchronizedThrottleAccumulator must not be null");
        this.instantSource = requireNonNull(instantSource, "instantSource must not be null");
        this.workflowMetrics = requireNonNull(workflowMetrics, "workflowMetrics must not be null");
        this.systemEntitiesCreatedFlag = systemEntitiesCreatedFlag;
    }

    /**
     * Verifies the platform is active and this node should be processing HAPI operations.
     *
     * @throws PreCheckException if the node is unable to process HAPI operations
     */
    public void verifyPlatformActive() throws PreCheckException {
        if (currentPlatformStatus.get() != ACTIVE) {
            throw new PreCheckException(PLATFORM_NOT_ACTIVE);
        }
    }

    /**
     * Verifies the network is ready to handle transactions.
     *
     * @throws PreCheckException if the node is unable to process HAPI operations
     */
    public void verifyReadyForTransactions() throws PreCheckException {
        verifyPlatformActive();
        if (systemEntitiesCreatedFlag != null && !systemEntitiesCreatedFlag.get()) {
            throw new PreCheckException(CREATING_SYSTEM_ENTITIES);
        }
        if (!blockStreamManager.hasLedgerId()) {
            throw new PreCheckException(WAITING_FOR_LEDGER_ID);
        }
    }

    /**
     * Runs all the ingest checks on a {@link Transaction}
     *
     * @param state the {@link State} to use
     * @param serializedTransaction the {@link Transaction} to check
     * @param configuration the {@link Configuration} to use
     * @param result the {@link Result} to populate with the results of the checks
     * @throws PreCheckException if a check fails
     */
    public void runAllChecks(
            @NonNull final State state,
            @NonNull final Bytes serializedTransaction,
            @NonNull final Configuration configuration,
            @NonNull final Result result)
            throws PreCheckException {
        runAllChecks(state, serializedTransaction, configuration, result, NO);
    }

    private void runAllChecks(
            @NonNull final State state,
            @NonNull final Bytes serializedTransaction,
            @NonNull final Configuration configuration,
            @NonNull final Result result,
            @NonNull final InnerTransaction innerTransaction)
            throws PreCheckException {
        requireNonNull(result);

        // During ingest we approximate consensus time with wall clock time
        final var consensusTime = instantSource.instant();

        // 1. Check the syntax
        final var maxBytes = maxIngestParseSize(configuration);
        TransactionInfo txInfo;
        if (innerTransaction == YES) {
            txInfo = transactionChecker.parseSignedAndCheck(serializedTransaction, maxBytes);
        } else {
            txInfo = transactionChecker.parseAndCheck(serializedTransaction, maxBytes);
            result.setTxnInfo(txInfo);
        }

        // check jumbo size after parsing
        transactionChecker.checkJumboTransactionBody(txInfo);
        final var txBody = txInfo.txBody();
        final var functionality = txInfo.functionality();

        // 1a. Verify the transaction has been sent to *this* node
        if (!nodeAccount.equals(txBody.nodeAccountID()) && innerTransaction == NO) {
            throw new PreCheckException(INVALID_NODE_ACCOUNT);
        }

        // 2. Check the time box of the transaction
        transactionChecker.checkTimeBox(txBody, consensusTime, RequireMinValidLifetimeBuffer.YES);

        // This should never happen, because HapiUtils#checkFunctionality() will throw
        // UnknownHederaFunctionality if it cannot map to a proper value, and WorkflowOnset
        // will convert that to INVALID_TRANSACTION_BODY.
        assert functionality != HederaFunctionality.NONE;

        // 3. Deduplicate
        if (deduplicationCache.contains(txInfo.transactionID())) {
            throw new PreCheckException(DUPLICATE_TRANSACTION);
        }

        // 4. Check throttles
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        try {
            checkThrottles(txInfo, state, hederaConfig, result.throttleUsages());
        } finally {
            // Always keep throttle usages up to date for refund logic
            result.setThrottleUsages(result.throttleUsages());
        }

        // 4a. Run pure checks
        final var pureChecksContext = new PureChecksContextImpl(txBody, dispatcher);
        dispatcher.dispatchPureChecks(pureChecksContext);

        // 5. Get payer account
        final var storeFactory = new ReadableStoreFactory(state);
        final var payer = solvencyPreCheck.getPayerAccount(storeFactory, txInfo.payerID());
        final var payerKey = payer.key();
        // There should, absolutely, be a key for this account. If there isn't, then something is wrong in
        // state. So we will log this with a warning. We will also have to do something about the fact that
        // the key is missing -- so we will fail with unauthorized.
        if (payerKey == null) {
            // FUTURE: Have an alert and metric in our monitoring tools to make sure we are aware if this happens
            logger.warn("Payer account {} has no key, indicating a problem with state", txInfo.payerID());
            throw new PreCheckException(UNAUTHORIZED);
        }

        // 6. Verify payer's signatures
        verifyPayerSignature(txInfo, payer, configuration);

        // 7. Check payer solvency
        final var numSigs = txInfo.signatureMap().sigPair().size();
        final FeeContext feeContext = new FeeContextImpl(
                consensusTime,
                txInfo,
                payerKey,
                txInfo.payerID(),
                feeManager,
                storeFactory,
                configuration,
                authorizer,
                numSigs,
                dispatcher);
        final var fees = dispatcher.dispatchComputeFees(feeContext);
        solvencyPreCheck.checkSolvency(txInfo, payer, fees, INGEST);

        // 8. Re-run checks against inner transactions
        if (functionality == ATOMIC_BATCH) {
            for (final Bytes bytes : txBody.atomicBatch().transactions()) {
                runAllChecks(state, bytes, configuration, result, YES);
            }
        }
    }

    private void checkThrottles(
            @NonNull final TransactionInfo txInfo,
            @NonNull final State state,
            @NonNull final HederaConfig hederaConfig,
            @NonNull final List<ThrottleUsage> throttleUsages)
            throws PreCheckException {
        assertThrottlingPreconditions(txInfo, hederaConfig);
        if (hederaConfig.ingestThrottleEnabled()
                && synchronizedThrottleAccumulator.shouldThrottle(txInfo, state, throttleUsages)) {
            workflowMetrics.incrementThrottled(txInfo.functionality());
            throw new PreCheckException(BUSY);
        }
    }

    private static int maxIngestParseSize(Configuration configuration) {
        final var jumboTxnEnabled =
                configuration.getConfigData(JumboTransactionsConfig.class).isEnabled();
        final var jumboMaxTxnSize =
                configuration.getConfigData(JumboTransactionsConfig.class).maxTxnSize();
        final var transactionMaxBytes =
                configuration.getConfigData(HederaConfig.class).transactionMaxBytes();
        return jumboTxnEnabled ? jumboMaxTxnSize : transactionMaxBytes;
    }

    private void assertThrottlingPreconditions(
            @NonNull final TransactionInfo txInfo, @NonNull final HederaConfig hederaConfig) throws PreCheckException {
        final var function = txInfo.functionality();
        if (UNSUPPORTED_TRANSACTIONS.contains(function)) {
            throw new PreCheckException(NOT_SUPPORTED);
        }
        if (PRIVILEGED_TRANSACTIONS.contains(function)) {
            final var payerNum =
                    txInfo.payerID() == null ? Long.MAX_VALUE : txInfo.payerID().accountNumOrElse(Long.MAX_VALUE);
            // This adds a mild restriction that privileged transactions can only
            // be issued by system accounts; (FUTURE) consider giving non-trivial
            // minimum fees to privileged transactions that fail with NOT_SUPPORTED
            // at consensus, and adding them to normal throttle buckets, c.f.
            // https://github.com/hashgraph/hedera-services/issues/12559
            if (payerNum >= hederaConfig.firstUserEntity()) {
                throw new PreCheckException(NOT_SUPPORTED);
            }
        }
        if (FEATURE_FLAGGED_TRANSACTIONS.contains(function)) {
            if (!hederaConfig.hooksEnabled()) {
                switch (function) {
                    case LAMBDA_S_STORE -> throw new PreCheckException(HOOKS_NOT_ENABLED);
                    case CRYPTO_CREATE ->
                        validateTruePreCheck(
                                txInfo.txBody()
                                        .cryptoCreateAccountOrThrow()
                                        .hookCreationDetails()
                                        .isEmpty(),
                                HOOKS_NOT_ENABLED);
                    case CONTRACT_CREATE ->
                        validateTruePreCheck(
                                txInfo.txBody()
                                        .contractCreateInstanceOrThrow()
                                        .hookCreationDetails()
                                        .isEmpty(),
                                HOOKS_NOT_ENABLED);
                    case CRYPTO_UPDATE -> {
                        final var op = txInfo.txBody().cryptoUpdateAccountOrThrow();
                        validateTruePreCheck(
                                op.hookIdsToDelete().isEmpty()
                                        && op.hookCreationDetails().isEmpty(),
                                HOOKS_NOT_ENABLED);
                    }
                    case CONTRACT_UPDATE -> {
                        final var op = txInfo.txBody().contractUpdateInstanceOrThrow();
                        validateTruePreCheck(
                                op.hookIdsToDelete().isEmpty()
                                        && op.hookCreationDetails().isEmpty(),
                                HOOKS_NOT_ENABLED);
                    }
                    case CRYPTO_TRANSFER -> {
                        final var op = txInfo.txBody().cryptoTransferOrThrow();
                        for (final var adjust :
                                op.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
                            validateFalsePreCheck(
                                    adjust.hasPreTxAllowanceHook() || adjust.hasPrePostTxAllowanceHook(),
                                    HOOKS_NOT_ENABLED);
                        }
                        for (final var tokenTransfers : op.tokenTransfers()) {
                            for (final var adjust : tokenTransfers.transfers()) {
                                validateFalsePreCheck(
                                        adjust.hasPreTxAllowanceHook() || adjust.hasPrePostTxAllowanceHook(),
                                        HOOKS_NOT_ENABLED);
                            }
                            for (final var nftTransfer : tokenTransfers.nftTransfers()) {
                                validateFalsePreCheck(
                                        nftTransfer.hasPreTxSenderAllowanceHook()
                                                || nftTransfer.hasPrePostTxSenderAllowanceHook()
                                                || nftTransfer.hasPreTxReceiverAllowanceHook()
                                                || nftTransfer.hasPrePostTxReceiverAllowanceHook(),
                                        HOOKS_NOT_ENABLED);
                            }
                        }
                    }
                }
            }
        }
    }

    private void verifyPayerSignature(
            @NonNull final TransactionInfo txInfo,
            @NonNull final Account payer,
            @NonNull final Configuration configuration)
            throws PreCheckException {
        final var payerKey = payer.key();
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        final var sigPairs = txInfo.signatureMap().sigPair();

        // Expand the signatures
        final var expandedSigs = new HashSet<ExpandedSignaturePair>();
        signatureExpander.expand(sigPairs, expandedSigs);
        if (!isHollow(payer)) {
            signatureExpander.expand(payerKey, sigPairs, expandedSigs);
        } else {
            // If the payer is hollow, then we need to expand the signature for the payer
            final var originals = txInfo.signatureMap().sigPair().stream()
                    .filter(SignaturePair::hasEcdsaSecp256k1)
                    .filter(pair -> Bytes.wrap(EthSigsUtils.recoverAddressFromPubKey(
                                    pair.pubKeyPrefix().toByteArray()))
                            .equals(payer.alias()))
                    .findFirst();
            validateTruePreCheck(originals.isPresent(), INVALID_SIGNATURE);
            signatureExpander.expand(List.of(originals.get()), expandedSigs);
        }

        // Verify the signatures
        final var results = signatureVerifier.verify(txInfo.signedBytes(), expandedSigs);
        final var verifier = new DefaultKeyVerifier(hederaConfig, results);
        final SignatureVerification payerKeyVerification;
        if (!isHollow(payer)) {
            payerKeyVerification = verifier.verificationFor(payerKey);
        } else {
            payerKeyVerification = verifier.verificationFor(payer.alias());
        }
        // This can happen if the signature map was missing a signature for the payer account.
        if (payerKeyVerification.failed()) {
            throw new PreCheckException(INVALID_SIGNATURE);
        }
    }
}
