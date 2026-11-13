// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ReadableEndpointManifestStoreImpl;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the outbound sync loop for all active CLPR channels. The manager
 * periodically scans channels for pending outbound messages and initiates
 * sync calls to peer endpoints.
 *
 * <p>Concurrency is bounded by {@code clpr.maxConcurrentSyncs}. At most one
 * outbound sync per Channel runs at a time (enforced via per-Channel locks).
 *
 * <p>State is read from the latest immutable state via the state accessor.
 * All writes happen through standard consensus (submitBundle HAPI transaction).
 *
 * <p>The registry of Channels to sync is in-memory. The {@code CHANNELS} state cannot be enumerated, so
 * {@link #start()} rebuilds the registry from the channel index in state
 * ({@link ReadableChannelStore#syncableChannelIds()}); after that, handlers report Channels that activate or close.
 */
@Singleton
public class ClprChannelManager implements ClprChannelLifecycle {
    private static final Logger logger = LogManager.getLogger(ClprChannelManager.class);

    static final long DEFAULT_SYNC_INTERVAL_MS = 1000L;

    private final ConfigProvider configProvider;
    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore syncSemaphore;
    private final Set<String> ongoingChannelSyncs = ConcurrentHashMap.newKeySet();
    private final ClprSynchronizer synchronizer;
    private final ClprEndpointClientCache clientCache;

    private volatile boolean started = false;

    /** Per-channel outbound sync timers, used to cancel ticks on channel close or re-activation. */
    private final Map<Bytes, ScheduledFuture<?>> channelSyncFutures = new ConcurrentHashMap<>();

    /**
     * Local registry of known channel IDs. Populated from state observations
     * during sync ticks and from external registration events.
     */
    private final Set<Bytes> knownChannelIds = ConcurrentHashMap.newKeySet();

    /**
     * Per-channel cache of the peer's most recently reported view of <em>this</em> ledger's
     * endpoint-manifest version (fed from inbound {@code ClprQueueMetadata.endpoint_manifest_version}
     * via {@link #recordPeerObservedManifestVersion}). Compared against the local
     * {@code ClprEndpointManifest.version()} at outbound-sync time to gate manifest-proof emission
     * (see #335). Node-local and in-memory by design — NOT consensus state; an absent entry is
     * treated as version {@code 0} (peer assumed maximally stale), so the first outbound cycle after
     * a local manifest advance proactively pushes the proof, then stops once the peer reports it
     * caught up.
     */
    private final Map<Bytes, Long> peerObservedManifestVersions = new ConcurrentHashMap<>();

    @Inject
    public ClprChannelManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprSynchronizer synchronizer,
            @NonNull final ClprEndpointClientCache clientCache) {
        this.configProvider = requireNonNull(configProvider);
        this.stateAccessor = requireNonNull(stateAccessor);
        this.synchronizer = requireNonNull(synchronizer);
        this.clientCache = requireNonNull(clientCache);
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        this.syncSemaphore = new Semaphore(clprConfig.maxConcurrentSyncs());
        this.scheduler = Executors.newScheduledThreadPool(clprConfig.maxConcurrentSyncs(), r -> {
            final var thread = new Thread(r, "clpr-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the sync orchestration loop, or catches it up if it already runs. Called each time the platform
     * becomes active, including after a reconnect with no intervening {@link #stop()}.
     *
     * <p>Each call registers every Channel in the channel index of the latest state. After a restart, Channels
     * that were already syncable never re-trigger {@link #onChannelActivated}; and after a reconnect, this node never
     * handled the activations it missed. Without this, neither kind of Channel would ever sync again.
     */
    public void start() {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            logger.info("CLPR is disabled, sync orchestrator will not start");
            return;
        }
        final boolean firstStart = !started;
        started = true;
        registerIndexedChannels();
        // Schedule ticks for any channels registered before now; they would otherwise never tick and never
        // exchange messages. A channel that already has a tick keeps it.
        for (final var channelId : knownChannelIds) {
            channelSyncFutures.computeIfAbsent(channelId, this::scheduleTick);
        }
        if (firstStart) {
            logger.info("CLPR sync orchestrator started (maxConcurrentSyncs={})", clprConfig.maxConcurrentSyncs());
        }
    }

    /**
     * Registers every Channel in the channel index of the latest state. Leaves the registry as it is if the state
     * is unavailable or cannot be read.
     */
    private void registerIndexedChannels() {
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState == null ? null : wrappedState.get();
            if (state == null) {
                return;
            }
            final var channelIds = new ReadableStoreFactoryImpl(state)
                    .readableStore(ReadableChannelStore.class)
                    .syncableChannelIds();
            int registered = 0;
            for (final var channelId : channelIds) {
                if (knownChannelIds.add(channelId)) {
                    registered++;
                }
            }
            logger.info("CLPR registered {} of {} indexed channel(s)", registered, channelIds.size());
        } catch (final Exception e) {
            logger.error("Failed to register the indexed CLPR channels", e);
        }
    }

    /** Schedules a fixed-delay sync tick for a single channel at the given interval. */
    private ScheduledFuture<?> scheduleTick(@NonNull final Bytes channelId) {
        return scheduler.scheduleWithFixedDelay(
                () -> syncChannel(channelId),
                ClprChannelManager.DEFAULT_SYNC_INTERVAL_MS,
                ClprChannelManager.DEFAULT_SYNC_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the sync orchestrator gracefully.
     */
    public void stop() {
        started = false;
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled()) {
            scheduler.shutdownNow();
            clientCache.shutdownAll();
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        clientCache.shutdownAll();
        logger.info("CLPR sync orchestrator stopped");
    }

    /**
     * Single tick of one Channel's sync timer. Looks up the Channel in the latest
     * committed state and, if it is active with pending outbound messages, initiates a sync.
     *
     * @param channelId the Channel this tick fires for
     */
    void syncChannel(@NonNull final Bytes channelId) {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            return;
        }
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState == null ? null : wrappedState.get();
            if (state == null) {
                return;
            }
            final var storeFactory = new ReadableStoreFactoryImpl(state);
            final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
            final var channel = channelStore.getChannel(channelId);
            if (channel == null) {
                // Channel not (yet) in committed state. May be a freshly registered id
                // whose round has not committed, or a rolled-back registration. Skip this
                // tick without unscheduling — cleanup is driven by an observed CLOSED status
                // or an explicit onChannelClosed() call only.
                return;
            }
            if (channel.status() == ClprChannelStatus.CLOSED) {
                // Self-healing drop: the handler's onChannelClosed callback may have been
                // lost to a transaction rollback, or this id may have transitioned to CLOSED
                // before the orchestrator observed it. Either way, an observed-CLOSED channel
                // has no future sync work, so cancel its timer and drop it now.
                logger.info("CLPR sync: removing CLOSED channel {}", channelId.toHex());
                unscheduleChannel(channelId);
                return;
            }
            // Dial targets come from Channel.endpoint_manifest.endpoints() — populated at
            // ClprCompleteChannel time from the required manifest proof — with no config
            // fallback (spec §4.7).
            initiateSync(channel);
        } catch (final Exception e) {
            logger.error("Error during CLPR sync for channel {}", channelId.toHex(), e);
        }
    }

    /**
     * Initiates an outbound sync for a specific channel. Called by the sync tick
     * or by external triggers when a channel is known to have pending messages.
     *
     * @param channel   the channel metadata
     */
    public void initiateSync(@NonNull final ClprChannel channel) {
        requireNonNull(channel);
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled()) {
            return;
        }
        final String channelId = channel.channelId().toHex();

        // Skip if channel is CLOSED or PENDING (no syncing allowed in either state)
        if (channel.status() == ClprChannelStatus.CLOSED || channel.status() == ClprChannelStatus.PENDING) {
            logger.debug(
                    "[CLPR-SYNC-MANAGER] skipping ineligible channel conn={} status={}", channelId, channel.status());
            return;
        }

        // Skip only when there is no pending outbound work. "Pending work" is either queued
        // messages, a closing handshake, OR a local manifest advance the peer has not yet observed. A moved endpoint
        // must propagate even with an empty message queue, or peers stay stuck dialing the
        // obsolete address.
        final boolean noQueuedMessages = channel.nextMessageId() - 1 <= channel.ackedMessageId();
        final long peerObservedManifestVersion = peerObservedManifestVersions.getOrDefault(channel.channelId(), 0L);
        final boolean manifestStaleOnPeer = readLocalManifestVersion() > peerObservedManifestVersion;
        final boolean closing =
                channel.status() == ClprChannelStatus.CLOSING || channel.status() == ClprChannelStatus.DRAINED;
        if (noQueuedMessages && !manifestStaleOnPeer && !closing) {
            logger.debug(
                    "[CLPR-SYNC-MANAGER] skipping — no queued messages and peer manifest current conn={} "
                            + "nextMsgId={} ackedMsgId={} peerObservedManifestVersion={}",
                    channelId,
                    channel.nextMessageId(),
                    channel.ackedMessageId(),
                    peerObservedManifestVersion);
            return;
        }

        // Resolve the dial-target list from Channel.endpoint_manifest.endpoints() (spec §4.7, the
        // authoritative cached peer manifest), populated at ClprCompleteChannel time from the required
        // manifest proof. A cached manifest may legitimately have no endpoints yet (e.g. the peer's
        // genesis placeholder); until a later manifest update there is nothing to dial, so skip before
        // taking a sync slot rather than dispatching a sync that can only warn and return every tick.
        final List<ClprEndpoint> providedEndpoints = resolveDialTargets(channel);
        if (providedEndpoints.isEmpty()) {
            logger.debug("[CLPR-SYNC-MANAGER] skipping — cached peer manifest has no dial targets conn={}", channelId);
            return;
        }

        // Skip if already syncing this channel
        if (!ongoingChannelSyncs.add(channelId)) {
            logger.debug("[CLPR-SYNC-MANAGER] skipping already running sync conn={}", channelId);
            return;
        }

        // Try to acquire a sync slot.
        // This semaphore ensures only a limited number of sync operations are performed concurrently.
        if (!syncSemaphore.tryAcquire()) {
            // The channel is synced again on a later cycle
            logger.debug("Max concurrent sync reached, skipping sync for channel {}", channelId);
            ongoingChannelSyncs.remove(channelId);
            return;
        }

        // Hand the dial targets to the synchronizer, along with the local ClprEndpointManifest.version()
        // so it can decide whether to embed a manifest proof in the outbound bundle (see #335).
        scheduler.execute(() -> {
            try {
                final long localManifestVersion = readLocalManifestVersion();
                this.synchronizer.synchronize(
                        channel, providedEndpoints, localManifestVersion, peerObservedManifestVersion);
            } catch (final Exception e) {
                logger.error("Sync failed for channel {}", channelId, e);
            } finally {
                syncSemaphore.release();
                ongoingChannelSyncs.remove(channelId);
            }
        });
    }

    /**
     * Resolves the dial-target list for {@code channel} from its cached endpoint manifest.
     * Returns an unmodifiable list (possibly empty); never {@code null}.
     */
    @NonNull
    private List<ClprEndpoint> resolveDialTargets(@NonNull final ClprChannel channel) {
        return channel.hasEndpointManifest()
                ? List.copyOf(channel.endpointManifestOrThrow().endpoints())
                : List.of();
    }

    /**
     * Reads the current {@code ClprEndpointManifest.version()} from the latest immutable
     * state. Returns 0 when state is unavailable — the sync tick still runs; the peer will
     * be reported as up-to-date and no manifest proof is embedded (the next tick with state
     * available will re-evaluate).
     */
    private long readLocalManifestVersion() {
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState == null ? null : wrappedState.get();
            if (state == null) {
                return 0L;
            }
            final var manifestStore = new ReadableEndpointManifestStoreImpl(state.getReadableStates(ClprService.NAME));
            return manifestStore.get().version();
        } catch (final Exception e) {
            logger.warn("Failed to read local ClprEndpointManifest.version(); falling back to 0", e);
            return 0L;
        }
    }

    /**
     * Registers a channel ID in the local registry. Called when a channel
     * is observed (e.g., during submit_bundle handling).
     *
     * @param channelId the 32-byte channel ID
     */
    public void registerChannel(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        knownChannelIds.add(channelId);
    }

    /**
     * Registers the channel and schedules its outbound sync timer at the default interval.
     * If the orchestrator has not started (CLPR disabled or pre-start), only the registry is
     * updated; no timer is scheduled.
     */
    @Override
    public void onChannelActivated(@NonNull final Bytes channelId) {
        registerChannel(channelId);
        if (!started || scheduler.isShutdown()) {
            return;
        }
        final var prior = channelSyncFutures.put(channelId, scheduleTick(channelId));
        if (prior != null) {
            prior.cancel(false);
        }
    }

    /**
     * @return unmodifiable set of known channel IDs
     */
    public Set<Bytes> knownChannelsIds() {
        return Collections.unmodifiableSet(knownChannelIds);
    }

    /** Removes the channel from the local registry, cancels its sync timer, and purges its caches. */
    @Override
    public void onChannelClosed(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        unscheduleChannel(channelId);
    }

    /** Cancels a channel's sync timer (if any) and removes it from all local registries. */
    private void unscheduleChannel(@NonNull final Bytes channelId) {
        final var future = channelSyncFutures.remove(channelId);
        if (future != null) {
            future.cancel(false);
        }
        if (knownChannelIds.remove(channelId)) {
            peerObservedManifestVersions.remove(channelId);
            logger.debug("Removed channel {} from local registry", channelId.toHex());
        }
    }

    /**
     * Records the peer's reported cache of our manifest version (see #335). Node-local, in-memory,
     * last-write-wins per channel; consumed at outbound-sync time by {@link #initiateSync}.
     */
    @Override
    public void recordPeerObservedManifestVersion(@NonNull final Bytes channelId, final long peerObservedVersion) {
        requireNonNull(channelId);
        peerObservedManifestVersions.put(channelId, peerObservedVersion);
    }

    /**
     * Returns the peer's last-reported cache of our manifest version for {@code channelId}
     * (see #335), or {@code 0} if none has been recorded yet. This is the same node-local signal
     * consumed by {@link #initiateSync} on the outbound path; the inbound responder reads it to
     * decide whether to embed our manifest for a peer whose cached view of us is behind.
     */
    public long peerObservedManifestVersion(@NonNull final Bytes channelId) {
        requireNonNull(channelId);
        return peerObservedManifestVersions.getOrDefault(channelId, 0L);
    }

    /**
     * Checks if the channel manager is currently started.
     *
     * @return true if the channel manager is started, false otherwise
     */
    public boolean started() {
        return started && !scheduler.isShutdown();
    }

    @VisibleForTesting
    ScheduledExecutorService scheduler() {
        return this.scheduler;
    }

    @VisibleForTesting
    ScheduledFuture<?> syncTickFuture(@NonNull final Bytes channelId) {
        return channelSyncFutures.get(channelId);
    }
}
