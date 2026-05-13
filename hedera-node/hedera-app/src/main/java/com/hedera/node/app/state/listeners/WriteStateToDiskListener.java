// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.ReadableUpgradeFileStore;
import com.hedera.node.app.service.networkadmin.ReadableFreezeStore;
import com.hedera.node.app.service.networkadmin.impl.handlers.ReadableFreezeUpgradeActions;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Listener that will be notified with {@link StateWriteToDiskCompleteNotification} when state is
 * written to disk. This writes {@code NOW_FROZEN_MARKER} to disk when upgrade is pending
 */
@Singleton
public class WriteStateToDiskListener implements StateWriteToDiskCompleteListener {
    private static final Logger log = LogManager.getLogger(WriteStateToDiskListener.class);

    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final Executor executor;
    private final ConfigProvider configProvider;
    private final StartupNetworks startupNetworks;
    private final EntityIdFactory entityIdFactory;
    private final BlockBufferService blockBufferService;
    private final BlockStreamManager blockStreamManager;
    private final BlockRecordManager blockRecordManager;
    private final FreezeMarkerPlatformStatus freezeMarkerPlatformStatus;

    @Inject
    public WriteStateToDiskListener(
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull @Named("FreezeService") final Executor executor,
            @NonNull final ConfigProvider configProvider,
            @NonNull final StartupNetworks startupNetworks,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final BlockBufferService blockBufferService,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final FreezeMarkerPlatformStatus freezeMarkerPlatformStatus) {
        this.stateAccessor = requireNonNull(stateAccessor);
        this.executor = requireNonNull(executor);
        this.configProvider = requireNonNull(configProvider);
        this.startupNetworks = requireNonNull(startupNetworks);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.blockBufferService = requireNonNull(blockBufferService);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.freezeMarkerPlatformStatus = requireNonNull(freezeMarkerPlatformStatus);
    }

    @Override
    public void notify(@NonNull final StateWriteToDiskCompleteNotification notification) {
        try {
            blockBufferService.persistBuffer();
        } catch (final Exception e) {
            log.error("Error while writing block buffer to disk", e);
        }

        if (notification.isFreezeState()) {
            log.info(
                    "StateWriteToDiskCompleteNotification Received : Freeze State Finished. "
                            + "consensusTimestamp: {}, roundNumber: {}, sequence: {}",
                    notification.getConsensusTimestamp(),
                    notification.getRoundNumber(),
                    notification.getSequence());
            final var nowFrozenMarkerGateFuture = nowFrozenMarkerGateFuture(notification);
            nowFrozenMarkerGateFuture.whenComplete((ignore, throwable) -> {
                if (throwable instanceof TimeoutException) {
                    log.warn(
                            "now_frozen.mf gate timed out for freeze state round {}; "
                                    + "externalizing upgrade marker anyway",
                            notification.getRoundNumber());
                } else if (throwable != null) {
                    log.warn(
                            "now_frozen.mf gate completed exceptionally for freeze state round {}; "
                                    + "externalizing upgrade marker anyway",
                            notification.getRoundNumber(),
                            throwable);
                }
                externalizeFreezeIfUpgradePending();
            });
        }
        // We don't archive genesis startup assets until at least one round has actually been handled,
        // since we need these assets to create genesis entities at the beginning of the first round
        if (notification.getRoundNumber() > 0) {
            startupNetworks.archiveStartupNetworks();
        }
    }

    private @NonNull CompletableFuture<Void> nowFrozenMarkerGateFuture(
            @NonNull final StateWriteToDiskCompleteNotification notification) {
        try {
            final var blockStreamFuture = requireNonNull(blockStreamManager.pendingBlockProofsFuture());
            final var wrbWritersFuture = requireNonNull(blockRecordManager.noOpenWrbWritersFuture());
            final var freezeCompleteFuture = requireNonNull(freezeMarkerPlatformStatus.freezeCompleteFuture());
            final var nowFrozenWriteTimeout = configProvider
                    .getConfiguration()
                    .getConfigData(HederaConfig.class)
                    .nowFrozenWriteTimeout();
            log.info(
                    "Freeze state written for round {}; waiting to externalize upgrade marker until "
                            + "pending block proofs, WRB writers, and FREEZE_COMPLETE status complete; "
                            + "timeout={}, blockStreamFutureDone={}, wrbWritersFutureDone={}, "
                            + "freezeCompleteFutureDone={}",
                    notification.getRoundNumber(),
                    nowFrozenWriteTimeout,
                    blockStreamFuture.isDone(),
                    wrbWritersFuture.isDone(),
                    freezeCompleteFuture.isDone());
            return allOf(blockStreamFuture, wrbWritersFuture, freezeCompleteFuture)
                    .orTimeout(nowFrozenWriteTimeout.toNanos(), NANOSECONDS);
        } catch (final RuntimeException e) {
            log.warn(
                    "Unable to get now_frozen.mf gate futures for freeze state round {}; "
                            + "externalizing upgrade marker immediately",
                    notification.getRoundNumber(),
                    e);
            return CompletableFuture.completedFuture(null);
        }
    }

    void externalizeFreezeIfUpgradePending() {
        try (final var wrappedState = stateAccessor.get()) {
            final var readableStoreFactory = new ReadableStoreFactoryImpl(wrappedState.get());
            final var readableFreezeStore = readableStoreFactory.readableStore(ReadableFreezeStore.class);
            final var readableUpgradeFileStore = readableStoreFactory.readableStore(ReadableUpgradeFileStore.class);
            final var readableNodeStore = readableStoreFactory.readableStore(ReadableNodeStore.class);
            final var readableStakingInfoStore = readableStoreFactory.readableStore(ReadableStakingInfoStore.class);

            final var upgradeActions = new ReadableFreezeUpgradeActions(
                    configProvider.getConfiguration(),
                    readableFreezeStore,
                    executor,
                    readableUpgradeFileStore,
                    readableNodeStore,
                    readableStakingInfoStore,
                    entityIdFactory);
            log.info("Externalizing freeze if upgrade is pending");
            upgradeActions.externalizeFreezeIfUpgradePending();
        } catch (final Exception e) {
            log.error("Error while responding to freeze state notification", e);
        }
    }
}
