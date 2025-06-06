// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.query;

import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.components.QueryInjectionComponent;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.service.addressbook.impl.handlers.AddressBookHandlers;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusHandlers;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.file.impl.handlers.FileHandlers;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkAdminHandlers;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleHandlers;
import com.hedera.node.app.service.token.impl.handlers.TokenHandlers;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.records.RecordCache;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.throttle.SynchronizedThrottleAccumulator;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.ingest.IngestChecker;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.app.workflows.query.annotations.OperatorQueries;
import com.hedera.node.app.workflows.query.annotations.UserQueries;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.Codec;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.InstantSource;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Singleton;

/**
 * Module for Query processing.
 */
@Module(subcomponents = {QueryInjectionComponent.class})
public interface QueryWorkflowInjectionModule {
    Runnable NO_OP = () -> {};

    @Provides
    @Singleton
    @UserQueries
    static QueryWorkflow provideUserQueryWorkflow(
            @NonNull final Function<ResponseType, AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final SubmissionManager submissionManager,
            @NonNull final QueryChecker queryChecker,
            @NonNull final IngestChecker ingestChecker,
            @NonNull final QueryDispatcher dispatcher,
            @NonNull final Codec<Query> queryParser,
            @NonNull final ConfigProvider configProvider,
            @NonNull final RecordCache recordCache,
            @NonNull final Authorizer authorizer,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final SynchronizedThrottleAccumulator synchronizedThrottleAccumulator,
            @NonNull final InstantSource instantSource,
            @NonNull final OpWorkflowMetrics opWorkflowMetrics) {
        return new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                true);
    }

    @Provides
    @Singleton
    @OperatorQueries
    static QueryWorkflow provideOperatorQueryWorkflow(
            @NonNull final Function<ResponseType, AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final SubmissionManager submissionManager,
            @NonNull final QueryChecker queryChecker,
            @NonNull final IngestChecker ingestChecker,
            @NonNull final QueryDispatcher dispatcher,
            @NonNull final Codec<Query> queryParser,
            @NonNull final ConfigProvider configProvider,
            @NonNull final RecordCache recordCache,
            @NonNull final Authorizer authorizer,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final SynchronizedThrottleAccumulator synchronizedThrottleAccumulator,
            @NonNull final InstantSource instantSource,
            @NonNull final OpWorkflowMetrics opWorkflowMetrics) {
        return new QueryWorkflowImpl(
                stateAccessor,
                submissionManager,
                queryChecker,
                ingestChecker,
                dispatcher,
                queryParser,
                configProvider,
                recordCache,
                authorizer,
                exchangeRateManager,
                feeManager,
                synchronizedThrottleAccumulator,
                instantSource,
                opWorkflowMetrics,
                false);
    }

    @Provides
    @Singleton
    static Function<ResponseType, AutoCloseableWrapper<State>> provideStateAccess(
            @NonNull final WorkingStateAccessor workingStateAccessor) {
        return responseType -> new AutoCloseableWrapper<>(workingStateAccessor.getState(), NO_OP);
    }

    @Provides
    static QueryHandlers provideQueryHandlers(
            @NonNull final ConsensusHandlers consensusHandlers,
            @NonNull final FileHandlers fileHandlers,
            @NonNull final NetworkAdminHandlers networkHandlers,
            @NonNull final Supplier<ContractHandlers> contractHandlers,
            @NonNull final ScheduleHandlers scheduleHandlers,
            @NonNull final TokenHandlers tokenHandlers,
            @NonNull final AddressBookHandlers addressBookHandlers) {
        return new QueryHandlers(
                consensusHandlers.consensusGetTopicInfoHandler(),
                contractHandlers.get().contractGetBySolidityIDHandler(),
                contractHandlers.get().contractCallLocalHandler(),
                contractHandlers.get().contractGetInfoHandler(),
                contractHandlers.get().contractGetBytecodeHandler(),
                contractHandlers.get().contractGetRecordsHandler(),
                tokenHandlers.cryptoGetAccountBalanceHandler(),
                tokenHandlers.cryptoGetAccountInfoHandler(),
                tokenHandlers.cryptoGetAccountRecordsHandler(),
                tokenHandlers.cryptoGetLiveHashHandler(),
                tokenHandlers.cryptoGetStakersHandler(),
                fileHandlers.fileGetContentsHandler(),
                fileHandlers.fileGetInfoHandler(),
                networkHandlers.networkGetAccountDetailsHandler(),
                networkHandlers.networkGetByKeyHandler(),
                networkHandlers.networkGetExecutionTimeHandler(),
                networkHandlers.networkGetVersionInfoHandler(),
                networkHandlers.networkTransactionGetReceiptHandler(),
                networkHandlers.networkTransactionGetRecordHandler(),
                networkHandlers.networkTransactionGetFastRecordHandler(),
                scheduleHandlers.scheduleGetInfoHandler(),
                tokenHandlers.tokenGetInfoHandler(),
                tokenHandlers.tokenGetAccountNftInfosHandler(),
                tokenHandlers.tokenGetNftInfoHandler(),
                tokenHandlers.tokenGetNftInfosHandler());
    }

    @Provides
    static Codec<Query> provideQueryParser() {
        return Query.PROTOBUF;
    }
}
