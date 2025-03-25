// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.dispatch;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.functionalityForType;
import static com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId.NO;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.PRE_HANDLE_FAILURE;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.SO_FAR_SO_GOOD;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySortedSet;
import static java.util.Collections.unmodifiableSortedSet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.ResourcePriceCalculatorImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.EntityNumGeneratorImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.services.ServiceScopeLookup;
import com.hedera.node.app.signature.AppKeyVerifier;
import com.hedera.node.app.signature.DefaultKeyVerifier;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.key.KeyComparator;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.ConsensusThrottling;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.store.ServiceApiFactory;
import com.hedera.node.app.store.StoreFactoryImpl;
import com.hedera.node.app.store.WritableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchHandleContext;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.RecordDispatch;
import com.hedera.node.app.workflows.handle.record.TokenContextImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.app.workflows.purechecks.PureChecksContextImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A factory for constructing child dispatches.This also gets the pre-handle result for the child transaction,
 * and signature verifications for the child transaction.
 */
@Singleton
public class ChildDispatchFactory {
    private static final KeyComparator KEY_COMPARATOR = new KeyComparator();
    public static final NoOpKeyVerifier NO_OP_KEY_VERIFIER = new NoOpKeyVerifier();
    private static final Set<HederaFunctionality> RECURSIVE_FUNCTIONS =
            EnumSet.of(CONTRACT_CALL, CONTRACT_CREATE, ETHEREUM_TRANSACTION);

    private final TransactionDispatcher dispatcher;
    private final Authorizer authorizer;
    private final NetworkInfo networkInfo;
    private final FeeManager feeManager;
    private final DispatchProcessor dispatchProcessor;
    private final ServiceScopeLookup serviceScopeLookup;
    private final ExchangeRateManager exchangeRateManager;
    private final Function<SemanticVersion, SoftwareVersion> softwareVersionFactory;
    private final TransactionChecker transactionChecker;

    @Inject
    public ChildDispatchFactory(
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final Authorizer authorizer,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final FeeManager feeManager,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final ServiceScopeLookup serviceScopeLookup,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final TransactionChecker transactionChecker,
            @NonNull final DeduplicationCache deduplicationCache,
            @NonNull final Function<SemanticVersion, SoftwareVersion> softwareVersionFactory) {
        this.dispatcher = requireNonNull(dispatcher);
        this.authorizer = requireNonNull(authorizer);
        this.networkInfo = requireNonNull(networkInfo);
        this.feeManager = requireNonNull(feeManager);
        this.dispatchProcessor = requireNonNull(dispatchProcessor);
        this.serviceScopeLookup = requireNonNull(serviceScopeLookup);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.softwareVersionFactory = requireNonNull(softwareVersionFactory);
        this.transactionChecker = requireNonNull(transactionChecker);
    }

    /**
     * Creates a child dispatch. This method computes the transaction info and initializes record builder for the child
     * transaction. This method also computes a pre-handle result for the child transaction.
     *
     * @param config                  the configuration
     * @param stack                   the savepoint stack
     * @param readableStoreFactory    the readable store factory
     * @param creatorInfo             the node info of the creator
     * @param topLevelFunction        the top level functionality
     * @param consensusNow            the consensus time
     * @param blockRecordInfo         the block record info
     * @param options                 the dispatch options
     * @param overridePreHandleResult the override pre-handle result for the inner transaction from atomic batch
     * @return the child dispatch
     * @throws HandleException if the child stack base builder cannot be created
     */
    public Dispatch createChildDispatch(
            @NonNull final Configuration config,
            @NonNull final SavepointStackImpl stack,
            @NonNull final ReadableStoreFactory readableStoreFactory,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final HederaFunctionality topLevelFunction,
            @NonNull final ThrottleAdviser throttleAdviser,
            @NonNull final Instant consensusNow,
            @NonNull final BlockRecordInfo blockRecordInfo,
            @NonNull final DispatchOptions<?> options,
            @Nullable final PreHandleResult overridePreHandleResult) {
        requireNonNull(config);
        requireNonNull(stack);
        requireNonNull(readableStoreFactory);
        requireNonNull(creatorInfo);
        requireNonNull(topLevelFunction);
        requireNonNull(throttleAdviser);
        requireNonNull(consensusNow);
        requireNonNull(blockRecordInfo);
        requireNonNull(options);
        // If there is an override pre-handle result, then this is an atomic batch inner transaction.
        // If there is an override pre-handle result, we re-use it, and this dispatch will check signatures using the
        // results from the override pre-handle result
        final var preHandleResult = overridePreHandleResult != null
                ? overridePreHandleResult
                : preHandleChild(options.body(), options.payerId(), config, readableStoreFactory, creatorInfo);
        final var childVerifier = overridePreHandleResult != null
                ? new DefaultKeyVerifier(
                        0, config.getConfigData(HederaConfig.class), overridePreHandleResult.getVerificationResults())
                : getKeyVerifier(options.effectiveKeyVerifier(), config, options.authorizingKeys());
        boolean isLastAllowedPreset = false;
        if (options.body().hasScheduleCreate()) {
            final var scheduledFunction = functionalityForType(options.body()
                    .scheduleCreateOrThrow()
                    .scheduledTransactionBodyOrElse(SchedulableTransactionBody.DEFAULT)
                    .data()
                    .kind());
            isLastAllowedPreset = RECURSIVE_FUNCTIONS.contains(scheduledFunction);
        }
        final var body = options.usePresetTxnId() == NO
                ? options.body()
                : options.body()
                        .copyBuilder()
                        .transactionID(stack.nextPresetTxnId(isLastAllowedPreset))
                        .build();
        final var childTxnInfo = getTxnInfoFrom(options.payerId(), body);
        final var streamMode = config.getConfigData(BlockStreamConfig.class).streamMode();
        final var childStack = SavepointStackImpl.newChildStack(
                stack, options.reversingBehavior(), options.category(), options.transactionCustomizer(), streamMode);
        final var streamBuilder = initializedForChild(childStack.getBaseBuilder(StreamBuilder.class), childTxnInfo);
        return newChildDispatch(
                streamBuilder,
                childTxnInfo,
                options.payerId(),
                options.category(),
                childStack,
                preHandleResult,
                childVerifier,
                consensusNow,
                options.dispatchMetadata(),
                options.throttling(),
                options.customFeeCharging(),
                creatorInfo,
                config,
                topLevelFunction,
                throttleAdviser,
                authorizer,
                networkInfo,
                feeManager,
                dispatchProcessor,
                blockRecordInfo,
                serviceScopeLookup,
                exchangeRateManager,
                dispatcher);
    }

    private RecordDispatch newChildDispatch(
            // @ChildDispatchScope
            @NonNull final StreamBuilder builder,
            @NonNull final TransactionInfo txnInfo,
            @NonNull final AccountID payerId,
            @NonNull final HandleContext.TransactionCategory category,
            @NonNull final SavepointStackImpl childStack,
            @NonNull final PreHandleResult preHandleResult,
            @NonNull final AppKeyVerifier keyVerifier,
            @NonNull final Instant consensusNow,
            @NonNull final DispatchMetadata dispatchMetadata,
            @NonNull final ConsensusThrottling consensusThrottling,
            @Nullable FeeCharging customFeeCharging,
            // @UserTxnScope
            @NonNull final NodeInfo creatorInfo,
            @NonNull final Configuration config,
            @NonNull final HederaFunctionality topLevelFunction,
            @NonNull final ThrottleAdviser throttleAdviser,
            // @Singleton
            @NonNull final Authorizer authorizer,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final FeeManager feeManager,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final BlockRecordInfo blockRecordInfo,
            @NonNull final ServiceScopeLookup serviceScopeLookup,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final TransactionDispatcher dispatcher) {
        final var readableStoreFactory = new ReadableStoreFactory(childStack, softwareVersionFactory);
        final var writableEntityIdStore = new WritableEntityIdStore(childStack.getWritableStates(EntityIdService.NAME));
        final var entityNumGenerator = new EntityNumGeneratorImpl(writableEntityIdStore);
        final var writableStoreFactory = new WritableStoreFactory(
                childStack, serviceScopeLookup.getServiceName(txnInfo.txBody()), writableEntityIdStore);
        final var serviceApiFactory = new ServiceApiFactory(childStack, config);
        final var priceCalculator =
                new ResourcePriceCalculatorImpl(consensusNow, txnInfo, feeManager, readableStoreFactory);
        final var storeFactory = new StoreFactoryImpl(readableStoreFactory, writableStoreFactory, serviceApiFactory);
        final var childFeeAccumulator =
                new FeeAccumulator(serviceApiFactory.getApi(TokenServiceApi.class), (FeeStreamBuilder) builder);
        final var dispatchHandleContext = new DispatchHandleContext(
                consensusNow,
                creatorInfo,
                txnInfo,
                config,
                authorizer,
                blockRecordInfo,
                priceCalculator,
                feeManager,
                storeFactory,
                payerId,
                keyVerifier,
                topLevelFunction,
                Key.DEFAULT,
                exchangeRateManager,
                childStack,
                entityNumGenerator,
                dispatcher,
                networkInfo,
                this,
                dispatchProcessor,
                throttleAdviser,
                childFeeAccumulator,
                dispatchMetadata,
                transactionChecker,
                null);
        final var childFees = dispatchHandleContext.dispatchComputeFees(txnInfo.txBody(), payerId);
        final var congestionMultiplier = feeManager.congestionMultiplierFor(
                txnInfo.txBody(), txnInfo.functionality(), storeFactory.asReadOnly());
        if (congestionMultiplier > 1) {
            builder.congestionMultiplier(congestionMultiplier);
        }
        final var childTokenContext =
                new TokenContextImpl(config, childStack, consensusNow, writableEntityIdStore, softwareVersionFactory);
        return new RecordDispatch(
                builder,
                config,
                childFees,
                txnInfo,
                payerId,
                readableStoreFactory,
                childFeeAccumulator,
                keyVerifier,
                creatorInfo,
                consensusNow,
                preHandleResult.getRequiredKeys(),
                preHandleResult.getHollowAccounts(),
                dispatchHandleContext,
                childStack,
                category,
                childTokenContext,
                preHandleResult,
                consensusThrottling,
                customFeeCharging);
    }

    /**
     * Dispatches the pre-handle checks for the child transaction. This runs pureChecks and then dispatches pre-handle
     * for child transaction.
     *
     * @param txBody the transaction body
     * @param syntheticPayerId the synthetic payer id
     * @param config the configuration
     * @param readableStoreFactory the readable store factory
     * @param creatorInfo the creator info
     * @return the pre-handle result
     */
    private PreHandleResult preHandleChild(
            @NonNull final TransactionBody txBody,
            @NonNull final AccountID syntheticPayerId,
            @NonNull final Configuration config,
            @NonNull final ReadableStoreFactory readableStoreFactory,
            @NonNull final NodeInfo creatorInfo) {
        try {
            final var pureChecksContext = new PureChecksContextImpl(txBody, dispatcher);
            dispatcher.dispatchPureChecks(pureChecksContext);
            final var preHandleContext = new PreHandleContextImpl(
                    readableStoreFactory,
                    txBody,
                    syntheticPayerId,
                    config,
                    dispatcher,
                    transactionChecker,
                    creatorInfo);
            dispatcher.dispatchPreHandle(preHandleContext);
            return new PreHandleResult(
                    null,
                    null,
                    SO_FAR_SO_GOOD,
                    OK,
                    null,
                    preHandleContext.requiredNonPayerKeys(),
                    null,
                    preHandleContext.requiredHollowAccounts(),
                    null,
                    null,
                    0);
        } catch (final PreCheckException e) {
            return new PreHandleResult(
                    null,
                    null,
                    PRE_HANDLE_FAILURE,
                    e.responseCode(),
                    null,
                    Collections.emptySet(),
                    null,
                    Collections.emptySet(),
                    null,
                    null,
                    0);
        }
    }

    /**
     * A {@link AppKeyVerifier} that always returns {@link SignatureVerificationImpl} with a
     * passed verification.
     */
    public static class NoOpKeyVerifier implements AppKeyVerifier {
        private static final SignatureVerification PASSED_VERIFICATION =
                new SignatureVerificationImpl(Key.DEFAULT, Bytes.EMPTY, true);

        @NonNull
        @Override
        public SignatureVerification verificationFor(@NonNull final Key key) {
            return PASSED_VERIFICATION;
        }

        @NonNull
        @Override
        public SignatureVerification verificationFor(
                @NonNull final Key key, @NonNull final VerificationAssistant callback) {
            return PASSED_VERIFICATION;
        }

        @NonNull
        @Override
        public SignatureVerification verificationFor(@NonNull final Bytes evmAlias) {
            return PASSED_VERIFICATION;
        }

        @Override
        public int numSignaturesVerified() {
            return 0;
        }
    }

    /**
     * Returns a {@link AppKeyVerifier} based on the callback. If the callback is null, then it returns a
     * {@link NoOpKeyVerifier}. Otherwise, it returns a verifier that forwards calls to
     * {@link AppKeyVerifier#verificationFor(Key)} to a
     * {@link DefaultKeyVerifier#verificationFor(Key, VerificationAssistant)} with a verification assistant
     * returns true exactly when the callback returns true for its key.
     * <p>
     * A null callback is useful for internal dispatches that do not need further signature verifications;
     * for example, hollow account completion and auto account creation.
     *
     * @param callback        the callback
     * @param config          the configuration
     * @param authorizingKeys any simple keys that authorized this verifier
     * @return the key verifier
     */
    public static AppKeyVerifier getKeyVerifier(
            @Nullable final Predicate<Key> callback,
            @NonNull final Configuration config,
            @NonNull final Set<Key> authorizingKeys) {
        final var keys = asSortedSet(authorizingKeys);
        return callback == null
                ? authorizingKeys.isEmpty()
                        ? NO_OP_KEY_VERIFIER
                        : new NoOpKeyVerifier() {
                            @Override
                            public SortedSet<Key> authorizingSimpleKeys() {
                                return keys;
                            }
                        }
                : new AppKeyVerifier() {
                    private final AppKeyVerifier verifier =
                            new DefaultKeyVerifier(0, config.getConfigData(HederaConfig.class), emptyMap());

                    @NonNull
                    @Override
                    public SignatureVerification verificationFor(@NonNull final Key key) {
                        // Within the child HandleContext, a key structure has a valid signature ONLY if
                        // the given callback returns true for enough primitive keys in the structure
                        return verifier.verificationFor(key, (k, v) -> callback.test(k));
                    }

                    @NonNull
                    @Override
                    public SignatureVerification verificationFor(
                            @NonNull final Key key, @NonNull final VerificationAssistant callback) {
                        return verifier.verificationFor(key, callback);
                    }

                    @NonNull
                    @Override
                    public SignatureVerification verificationFor(@NonNull final Bytes evmAlias) {
                        // We do not yet support completing hollow accounts from an internal dispatch
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int numSignaturesVerified() {
                        return 0;
                    }

                    @Override
                    public SortedSet<Key> authorizingSimpleKeys() {
                        return keys;
                    }
                };
    }

    /**
     * Provides the transaction information for the given dispatched transaction body.
     *
     * @param payerId the payer id
     * @param txBody  the transaction body
     * @return the transaction information
     */
    public static TransactionInfo getTxnInfoFrom(
            @NonNull final AccountID payerId, @NonNull final TransactionBody txBody) {
        requireNonNull(payerId);
        requireNonNull(txBody);
        final var bodyBytes = TransactionBody.PROTOBUF.toBytes(txBody);
        final var signedTransaction =
                SignedTransaction.newBuilder().bodyBytes(bodyBytes).build();
        final var signedTransactionBytes = SignedTransaction.PROTOBUF.toBytes(signedTransaction);
        final var transaction = Transaction.newBuilder()
                .signedTransactionBytes(signedTransactionBytes)
                .build();
        return new TransactionInfo(
                transaction,
                txBody,
                txBody.transactionIDOrElse(TransactionID.DEFAULT),
                payerId,
                SignatureMap.DEFAULT,
                signedTransactionBytes,
                functionOfTxn(txBody),
                null);
    }

    /**
     * Provides the functionality of the transaction body.
     *
     * @param txBody the transaction body
     * @return the functionality
     */
    public static HederaFunctionality functionOfTxn(final TransactionBody txBody) {
        try {
            return functionOf(txBody);
        } catch (final UnknownHederaFunctionality e) {
            throw new IllegalArgumentException("Unknown Hedera Functionality", e);
        }
    }

    /**
     * Initializes the user stream item builder with the transaction information.
     *
     * @param builder the stream item builder
     * @param txnInfo the transaction info
     */
    private StreamBuilder initializedForChild(
            @NonNull final StreamBuilder builder, @NonNull final TransactionInfo txnInfo) {
        builder.transaction(txnInfo.transaction())
                .functionality(txnInfo.functionality())
                .transactionBytes(txnInfo.signedBytes())
                .memo(txnInfo.txBody().memo());
        final var transactionID = txnInfo.txBody().transactionID();
        if (transactionID != null) {
            builder.transactionID(transactionID);
        }
        return builder;
    }

    /**
     * Returns the given set of keys as a sorted set.
     *
     * @param keys the keys
     * @return the sorted set
     */
    private static SortedSet<Key> asSortedSet(@NonNull final Set<Key> keys) {
        return keys.isEmpty()
                ? emptySortedSet()
                : unmodifiableSortedSet(new TreeSet<>(KEY_COMPARATOR) {
                    {
                        addAll(keys);
                    }
                });
    }
}
