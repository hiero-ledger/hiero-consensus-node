// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.app.info.DiskStartupNetworks.tryToExport;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.handlers.HistoryHandlers;
import com.hedera.node.app.service.addressbook.impl.handlers.AddressBookHandlers;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusHandlers;
import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.impl.handlers.FileHandlers;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkAdminHandlers;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceImpl;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleHandlers;
import com.hedera.node.app.service.token.impl.handlers.TokenHandlers;
import com.hedera.node.app.service.util.impl.UtilServiceImpl;
import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.workflows.dispatcher.TransactionHandlers;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.CacheConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Singleton;

@Module
public interface HandleWorkflowModule {
    @Provides
    @Singleton
    static EntityIdFactory provideEntityIdFactory(@NonNull final AppContext appContext) {
        return appContext.idFactory();
    }

    @Provides
    @Singleton
    static Supplier<ContractHandlers> provideContractHandlers(@NonNull final ContractServiceImpl contractService) {
        return contractService::handlers;
    }

    @Provides
    @Singleton
    static ScheduleHandlers provideScheduleHandlers(@NonNull final ScheduleServiceImpl scheduleService) {
        return scheduleService.handlers();
    }

    @Provides
    @Singleton
    static UtilHandlers provideUtilHandlers(@NonNull final UtilServiceImpl utilService) {
        return utilService.handlers();
    }

    @Provides
    @Singleton
    static HintsHandlers provideHintsHandlers(@NonNull final HintsService hintsService) {
        return hintsService.handlers();
    }

    @Provides
    @Singleton
    static HistoryHandlers provideHistoryHandlers(@NonNull final HistoryService historyService) {
        return historyService.handlers();
    }

    @Provides
    @Singleton
    static EthereumTransactionHandler provideEthereumTransactionHandler(
            @NonNull final ContractServiceImpl contractService) {
        return contractService.handlers().ethereumTransactionHandler();
    }

    @Provides
    @Singleton
    static BiConsumer<Roster, Path> provideRosterExportHelper() {
        return (roster, path) -> {
            final var network = Network.newBuilder()
                    .nodeMetadata(roster.rosterEntries().stream()
                            .map(entry -> new NodeMetadata(entry, null))
                            .toList())
                    .build();
            tryToExport(network, path);
        };
    }

    Runnable NO_OP = () -> {};

    @Provides
    static Supplier<AutoCloseableWrapper<State>> provideStateSupplier(
            @NonNull final WorkingStateAccessor workingStateAccessor) {
        return () -> new AutoCloseableWrapper<>(workingStateAccessor.getState(), NO_OP);
    }

    @Provides
    @Named("CacheWarmer")
    static Executor provideCacheWarmerExecutor(@NonNull final ConfigProvider configProvider) {
        final var config = configProvider.getConfiguration();
        final int parallelism = config.getConfigData(CacheConfig.class).warmThreads();
        return new ForkJoinPool(parallelism);
    }

    @Provides
    @Named("FreezeService")
    static Executor provideFreezeServiceExecutor() {
        return new ForkJoinPool(
                1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, Thread.getDefaultUncaughtExceptionHandler(), true);
    }

    @Provides
    @Singleton
    static TransactionHandlers provideTransactionHandlers(
            @NonNull final NetworkAdminHandlers networkAdminHandlers,
            @NonNull final ConsensusHandlers consensusHandlers,
            @NonNull final FileHandlers fileHandlers,
            @NonNull final Supplier<ContractHandlers> contractHandlers,
            @NonNull final ScheduleHandlers scheduleHandlers,
            @NonNull final TokenHandlers tokenHandlers,
            @NonNull final UtilHandlers utilHandlers,
            @NonNull final AddressBookHandlers addressBookHandlers,
            @NonNull final HintsHandlers hintsHandlers,
            @NonNull final HistoryHandlers historyHandlers) {
        return new TransactionHandlers(
                consensusHandlers.consensusCreateTopicHandler(),
                consensusHandlers.consensusUpdateTopicHandler(),
                consensusHandlers.consensusDeleteTopicHandler(),
                consensusHandlers.consensusSubmitMessageHandler(),
                contractHandlers.get().contractCreateHandler(),
                contractHandlers.get().contractUpdateHandler(),
                contractHandlers.get().contractCallHandler(),
                contractHandlers.get().contractDeleteHandler(),
                contractHandlers.get().contractSystemDeleteHandler(),
                contractHandlers.get().contractSystemUndeleteHandler(),
                contractHandlers.get().ethereumTransactionHandler(),
                contractHandlers.get().lambdaSStoreHandler(),
                contractHandlers.get().hookDispatchHandler(),
                tokenHandlers.cryptoCreateHandler(),
                tokenHandlers.cryptoUpdateHandler(),
                tokenHandlers.cryptoTransferHandler(),
                tokenHandlers.cryptoDeleteHandler(),
                tokenHandlers.cryptoApproveAllowanceHandler(),
                tokenHandlers.cryptoDeleteAllowanceHandler(),
                tokenHandlers.cryptoAddLiveHashHandler(),
                tokenHandlers.cryptoDeleteLiveHashHandler(),
                fileHandlers.fileCreateHandler(),
                fileHandlers.fileUpdateHandler(),
                fileHandlers.fileDeleteHandler(),
                fileHandlers.fileAppendHandler(),
                fileHandlers.fileSystemDeleteHandler(),
                fileHandlers.fileSystemUndeleteHandler(),
                networkAdminHandlers.freezeHandler(),
                networkAdminHandlers.networkUncheckedSubmitHandler(),
                scheduleHandlers.scheduleCreateHandler(),
                scheduleHandlers.scheduleSignHandler(),
                scheduleHandlers.scheduleDeleteHandler(),
                tokenHandlers.tokenCreateHandler(),
                tokenHandlers.tokenUpdateHandler(),
                tokenHandlers.tokenMintHandler(),
                tokenHandlers.tokenBurnHandler(),
                tokenHandlers.tokenDeleteHandler(),
                tokenHandlers.tokenAccountWipeHandler(),
                tokenHandlers.tokenFreezeAccountHandler(),
                tokenHandlers.tokenUnfreezeAccountHandler(),
                tokenHandlers.tokenGrantKycToAccountHandler(),
                tokenHandlers.tokenRevokeKycFromAccountHandler(),
                tokenHandlers.tokenAssociateToAccountHandler(),
                tokenHandlers.tokenDissociateFromAccountHandler(),
                tokenHandlers.tokenFeeScheduleUpdateHandler(),
                tokenHandlers.tokenPauseHandler(),
                tokenHandlers.tokenUnpauseHandler(),
                tokenHandlers.tokenUpdateNftsHandler(),
                tokenHandlers.tokenRejectHandler(),
                tokenHandlers.tokenAirdropsHandler(),
                tokenHandlers.tokenCancelAirdropHandler(),
                addressBookHandlers.nodeCreateHandler(),
                addressBookHandlers.nodeUpdateHandler(),
                addressBookHandlers.nodeDeleteHandler(),
                tokenHandlers.tokenClaimAirdropHandler(),
                hintsHandlers.keyPublicationHandler(),
                hintsHandlers.preprocessingVoteHandler(),
                hintsHandlers.partialSignatureHandler(),
                utilHandlers.prngHandler(),
                utilHandlers.atomicBatchHandler(),
                historyHandlers.historyProofKeyPublicationHandler(),
                historyHandlers.historyProofSignatureHandler(),
                historyHandlers.historyProofVoteHandler(),
                hintsHandlers.crsPublicationHandler());
    }
}
