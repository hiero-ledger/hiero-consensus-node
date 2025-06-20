// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.initializeBuilderInfo;
import static com.hedera.node.app.workflows.handle.dispatch.ChildDispatchFactory.NO_OP_KEY_VERIFIER;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.UNKNOWN_FAILURE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.KVStateChangeListener;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.ResourcePriceCalculatorImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.EntityNumGeneratorImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.info.NodeInfoImpl;
import com.hedera.node.app.records.impl.BlockRecordInfoImpl;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.services.ServiceScopeLookup;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.store.ServiceApiFactory;
import com.hedera.node.app.store.StoreFactoryImpl;
import com.hedera.node.app.store.WritableStoreFactory;
import com.hedera.node.app.throttle.AppThrottleAdviser;
import com.hedera.node.app.throttle.NetworkUtilizationManager;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchHandleContext;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.RecordDispatch;
import com.hedera.node.app.workflows.handle.dispatch.ChildDispatchFactory;
import com.hedera.node.app.workflows.handle.record.TokenContextImpl;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflow;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;

/**
 * Constructs a {@link Dispatch} appropriate for a standalone transaction executor that does not want to enforce
 * normal signing requirements but simply execute one or more transactions.
 */
@Singleton
public class StandaloneDispatchFactory {
    private final FeeManager feeManager;
    private final Authorizer authorizer;
    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;
    private final DispatchProcessor dispatchProcessor;
    private final PreHandleWorkflow preHandleWorkflow;
    private final ServiceScopeLookup serviceScopeLookup;
    private final ExchangeRateManager exchangeRateManager;
    private final StoreMetricsService storeMetricsService;
    private final ChildDispatchFactory childDispatchFactory;
    private final TransactionDispatcher transactionDispatcher;
    private final NetworkUtilizationManager networkUtilizationManager;
    private final TransactionChecker transactionChecker;

    @Inject
    public StandaloneDispatchFactory(
            @NonNull final FeeManager feeManager,
            @NonNull final Authorizer authorizer,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final PreHandleWorkflow preHandleWorkflow,
            @NonNull final ServiceScopeLookup serviceScopeLookup,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final StoreMetricsService storeMetricsService,
            @NonNull final ChildDispatchFactory childDispatchFactory,
            @NonNull final TransactionDispatcher transactionDispatcher,
            @NonNull final NetworkUtilizationManager networkUtilizationManager,
            @NonNull final TransactionChecker transactionChecker) {
        this.feeManager = requireNonNull(feeManager);
        this.authorizer = requireNonNull(authorizer);
        this.networkInfo = requireNonNull(networkInfo);
        this.configProvider = requireNonNull(configProvider);
        this.dispatchProcessor = requireNonNull(dispatchProcessor);
        this.preHandleWorkflow = requireNonNull(preHandleWorkflow);
        this.childDispatchFactory = requireNonNull(childDispatchFactory);
        this.transactionDispatcher = requireNonNull(transactionDispatcher);
        this.serviceScopeLookup = requireNonNull(serviceScopeLookup);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.storeMetricsService = requireNonNull(storeMetricsService);
        this.networkUtilizationManager = requireNonNull(networkUtilizationManager);
        this.transactionChecker = requireNonNull(transactionChecker);
    }

    /**
     * Constructs a new {@link Dispatch} for the given transaction body, state, and consensus time. When the
     * dispatch is processed with the {@link DispatchProcessor}, its side effects will be committed to the
     * {@link State} provided.
     *
     * @param state the state to use
     * @param transactionBody the transaction body to use
     * @param consensusNow the consensus time to use
     * @return a new dispatch
     */
    public Dispatch newDispatch(
            @NonNull final State state,
            @NonNull final TransactionBody transactionBody,
            @NonNull final Instant consensusNow) {
        final var config = configProvider.getConfiguration();
        final var consensusConfig = config.getConfigData(ConsensusConfig.class);
        final var blockStreamConfig = config.getConfigData(BlockStreamConfig.class);
        final var stack = SavepointStackImpl.newRootStack(
                state,
                consensusConfig.handleMaxPrecedingRecords(),
                consensusConfig.handleMaxFollowingRecords(),
                new BoundaryStateChangeListener(storeMetricsService, () -> config),
                new KVStateChangeListener(),
                blockStreamConfig.streamMode());
        final var readableStoreFactory = new ReadableStoreFactory(stack);
        final var entityIdStore = new WritableEntityIdStore(stack.getWritableStates(EntityIdService.NAME));
        final var consensusTransaction = consensusTransactionFor(transactionBody);
        final var creatorInfo = creatorInfoFor(transactionBody);
        final var preHandleResult = preHandleWorkflow.getCurrentPreHandleResult(
                creatorInfo, consensusTransaction, readableStoreFactory, ignore -> {});
        final var tokenContext = new TokenContextImpl(config, stack, consensusNow, entityIdStore);
        final var txnInfo = requireNonNull(preHandleResult.txInfo());
        final var writableStoreFactory =
                new WritableStoreFactory(stack, serviceScopeLookup.getServiceName(txnInfo.txBody()), entityIdStore);
        final var serviceApiFactory = new ServiceApiFactory(stack, config);
        final var priceCalculator =
                new ResourcePriceCalculatorImpl(consensusNow, txnInfo, feeManager, readableStoreFactory);
        final var storeFactory = new StoreFactoryImpl(readableStoreFactory, writableStoreFactory, serviceApiFactory);
        final var entityNumGenerator = new EntityNumGeneratorImpl(entityIdStore);
        final var throttleAdvisor = new AppThrottleAdviser(networkUtilizationManager, consensusNow);
        final var baseBuilder = initializeBuilderInfo(
                stack.getBaseBuilder(StreamBuilder.class), txnInfo, exchangeRateManager.exchangeRates());
        final var feeAccumulator = new FeeAccumulator(
                serviceApiFactory.getApi(TokenServiceApi.class), (FeeStreamBuilder) baseBuilder, stack);
        final var blockRecordInfo = BlockRecordInfoImpl.from(state);
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
                requireNonNull(txnInfo.payerID()),
                NO_OP_KEY_VERIFIER,
                txnInfo.functionality(),
                preHandleResult.payerKey() == null ? Key.DEFAULT : preHandleResult.payerKey(),
                exchangeRateManager,
                stack,
                entityNumGenerator,
                transactionDispatcher,
                networkInfo,
                childDispatchFactory,
                dispatchProcessor,
                throttleAdvisor,
                feeAccumulator,
                EMPTY_METADATA,
                transactionChecker,
                preHandleResult.innerResults(),
                HandleContext.TransactionCategory.USER);
        final var fees = transactionDispatcher.dispatchComputeFees(dispatchHandleContext);
        return new RecordDispatch(
                baseBuilder,
                config,
                fees,
                txnInfo,
                requireNonNull(txnInfo.payerID()),
                readableStoreFactory,
                feeAccumulator,
                NO_OP_KEY_VERIFIER,
                creatorInfo,
                consensusNow,
                preHandleResult.getRequiredKeys(),
                preHandleResult.getHollowAccounts(),
                dispatchHandleContext,
                stack,
                ParentTxnFactory.getTxnCategory(preHandleResult),
                tokenContext,
                preHandleResult,
                HandleContext.ConsensusThrottling.ON,
                null);
    }

    private ConsensusTransaction consensusTransactionFor(@NonNull final TransactionBody transactionBody) {
        final var signedTransaction =
                new SignedTransaction(TransactionBody.PROTOBUF.toBytes(transactionBody), SignatureMap.DEFAULT);
        final var transaction = Transaction.newBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(signedTransaction))
                .build();
        final var transactionBytes = Transaction.PROTOBUF.toBytes(transaction);
        final var consensusTransaction = new TransactionWrapper(transactionBytes);
        consensusTransaction.setMetadata(temporaryPreHandleResult());
        return consensusTransaction;
    }

    private NodeInfo creatorInfoFor(@NonNull final TransactionBody transactionBody) {
        return new NodeInfoImpl(0, transactionBody.nodeAccountIDOrThrow(), 0, List.of(), Bytes.EMPTY, List.of(), false);
    }

    private PreHandleResult temporaryPreHandleResult() {
        return new PreHandleResult(null, null, UNKNOWN_FAILURE, OK, null, null, null, null, null, null, -1);
    }
}
