// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprPeerEndpoints;
import com.hedera.hapi.node.state.clpr.ClprPeerEndpointsEntry;
import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.hedera.node.app.service.clpr.impl.ReadableEndpointManifestStoreImpl;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.security.auth.x500.X500Principal;
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
 */
@Singleton
public class ClprChannelManager implements ClprChannelLifecycle {
    private static final Logger logger = LogManager.getLogger(ClprChannelManager.class);

    static final long DEFAULT_SYNC_INTERVAL_MS = 1000L;

    /** Cadence of the scheduled task that flushes the peer endpoints file when it has changed. */
    private static final long PEER_ENDPOINTS_FLUSH_INTERVAL_MS = 1000L;

    private final ConfigProvider configProvider;
    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final ScheduledExecutorService scheduler;
    private final Semaphore syncSemaphore;
    private final Set<String> ongoingChannelSyncs = ConcurrentHashMap.newKeySet();
    private final ClprSynchronizer synchronizer;
    private final NetworkInfo networkInfo;
    private final ClprLeafCertManager leafCertManager;
    private final ClprEndpointClientCache clientCache;

    private volatile boolean started = false;
    private boolean discoveryEnabled = false;

    /** Per-channel outbound sync timers, used to cancel ticks on channel close or re-activation. */
    private final Map<Bytes, ScheduledFuture<?>> channelSyncFutures = new ConcurrentHashMap<>();

    /**
     * Local registry of known channel IDs. Populated from state observations
     * during sync ticks and from external registration events.
     */
    private final Set<Bytes> knownChannelIds = ConcurrentHashMap.newKeySet();

    /**
     * Per-channel cache of known peer endpoints. Seeded from
     * {@code ClprLedgerConfiguration.endpoints} and updated via discovery.
     */
    private final Map<Bytes, List<ClprEndpoint>> peerEndpointCache = new ConcurrentHashMap<>();

    /**
     * Prebuilt peer-CA trust index for the inbound mTLS listener, keyed by the Distinguished Name (DN) of the CA
     * certificate. It is a pure function of {@link #peerEndpointCache} and recomputed eagerly whenever that cache
     * changes. The value is a list because distinct ledgers may share a CA subject DN; it is normally a single
     * element.
     * <b>Invariant:</b> every mutation of {@link #peerEndpointCache} must call {@link #rebuildPeerCaCache()}.
     */
    private volatile Map<X500Principal, List<X509Certificate>> cachedPeerCaCertificatesByIssuer = Map.of();

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

    /**
     * Node-local file (not consensus state) recording {@code knownChannelIds} and
     * {@link #peerEndpointCache}. Flushed by a single scheduled task whenever they change (and once
     * on {@link #stop()}), and read <b>exactly once</b> at {@link #start()} so a restarted node can
     * re-register channels and re-seed peer endpoints (the {@code CHANNELS} key/value state
     * cannot be iterated to rebuild them).
     *
     * <p>The file is optional: if it is absent or fails to parse, {@link #readCache()} returns an
     * empty record and the node simply starts with an empty registry (the pre-rehydration behaviour)
     * and rebuilds it as channels activate. Because it is read only at start-up, the in-memory
     * registry is authoritative for the rest of the process lifetime — a file that appears or changes
     * after {@code start()} is not picked up and will be overwritten by the next flush. (To seed a
     * fresh node with pre-existing channels, place the file before the node starts.)
     */
    private final Path peerEndpointsPath;

    /** Set when {@code knownChannelIds}/{@link #peerEndpointCache} change; drives the scheduled flush. */
    private final AtomicBoolean peerEndpointsDirty = new AtomicBoolean(false);

    /** Latches the one-time warning that discovery is suppressed while mTLS is enabled. */
    private final AtomicBoolean discoveryMtlsWarned = new AtomicBoolean(false);

    @Inject
    public ClprChannelManager(
            @NonNull final ConfigProvider configProvider,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprSynchronizer synchronizer,
            @NonNull final ClprLeafCertManager leafCertManager,
            @NonNull final ClprEndpointClientCache clientCache) {
        this.configProvider = requireNonNull(configProvider);
        this.stateAccessor = requireNonNull(stateAccessor);
        this.synchronizer = requireNonNull(synchronizer);
        this.networkInfo = requireNonNull(networkInfo);
        this.leafCertManager = requireNonNull(leafCertManager);
        this.clientCache = requireNonNull(clientCache);
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        this.peerEndpointsPath = Paths.get(clprConfig.peerEndpointsFile());
        this.syncSemaphore = new Semaphore(clprConfig.maxConcurrentSyncs());
        this.scheduler = Executors.newScheduledThreadPool(clprConfig.maxConcurrentSyncs(), r -> {
            final var thread = new Thread(r, "clpr-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the sync orchestration loop. Should be called once during node startup
     * after the platform is active.
     */
    public void start() {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            logger.info("CLPR is disabled, sync orchestrator will not start");
            return;
        }
        if (started) {
            return;
        }
        started = true;
        // Rebuild the in-memory registry and peer endpoint cache from the node-local disk cache.
        // After a restart the loaded state already has these channels ACTIVE, so
        // onChannelActivated never fires for them; without this they would never tick again,
        // and seedEndpointsFromConfig would only restore this node's own (self) endpoints.
        rehydrateFromDisk();
        // Schedule ticks for any channels that activated before the orchestrator started;
        // they would otherwise never tick and never exchange messages.
        for (final var channelId : knownChannelIds) {
            channelSyncFutures.computeIfAbsent(channelId, this::scheduleTick);
        }
        // Single scheduled task that writes the peer endpoints file only when it has changed —
        // mutation sites just flag it dirty. Rehydration above may already have re-registered
        // channels, but it doesn't dirty the file (it's reading what's already there).
        scheduler.scheduleWithFixedDelay(
                this::flushPeerEndpointsIfDirty,
                PEER_ENDPOINTS_FLUSH_INTERVAL_MS,
                PEER_ENDPOINTS_FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        final var discoveryInterval = clprConfig.discoveryIntervalSeconds();
        if (discoveryInterval > 0) {
            scheduler.scheduleWithFixedDelay(
                    this::discoveryTick, discoveryInterval, discoveryInterval, TimeUnit.SECONDS);
            logger.info(
                    "CLPR sync orchestrator started (maxConcurrentSyncs={}, discoveryIntervalSeconds={})",
                    clprConfig.maxConcurrentSyncs(),
                    discoveryInterval);
            discoveryEnabled = true;
        } else {
            logger.info(
                    "CLPR sync orchestrator started (maxConcurrentSyncs={}, discovery disabled)",
                    clprConfig.maxConcurrentSyncs());
            discoveryEnabled = false;
        }
    }

    /**
     * Re-registers, re-seeds peer endpoints for, and recovers the sync interval of every still-syncable
     * Channel recorded in the node-local disk cache. Called once from {@link #start()} so that, after
     * a node restart, Channels that were already ACTIVE in the loaded state (and therefore never
     * re-trigger {@link #onChannelActivated}) rejoin the outbound sync loop and can initiate syncs to
     * their peer. The caller's scheduling loop then starts the timers.
     *
     * <p>The cache is the only enumerable record of which Channels this node was tracking (the
     * {@code CHANNELS} key/value state cannot be iterated) and the only surviving source of peer
     * endpoints (the local ledger config would yield this node's own endpoints). Each cached id is
     * cross-checked against committed state: entries with no channel record, or in PENDING/CLOSED
     * status, are skipped. No sync interval is recovered here — {@link #scheduleTick} always uses
     * the fixed {@link #DEFAULT_SYNC_INTERVAL_MS}.
     *
     * <p><b>Call exactly once</b>, from {@link #start()} (which guards against re-entry via the
     * {@code started} flag). This method is <b>not</b> idempotent with respect to the endpoint cache:
     * it {@code put}s the file's endpoints into {@link #peerEndpointCache}, so calling it again after
     * the node has discovered newer endpoints would overwrite them with the (older) on-disk copy.
     */
    private void rehydrateFromDisk() {
        final var cache = readCache();
        if (cache.entries().isEmpty()) {
            return;
        }
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState == null ? null : wrappedState.get();
            final var storeFactory = state == null ? null : new ReadableStoreFactoryImpl(state);
            final var channelStore =
                    storeFactory == null ? null : storeFactory.readableStore(ReadableChannelStore.class);
            int rehydrated = 0;
            for (final var entry : cache.entries()) {
                final var channelId = entry.channelId();
                if (channelStore != null) {
                    final var channel = channelStore.getChannel(channelId);
                    if (channel == null) {
                        // Stale cache entry: no committed channel record. Skip.
                        continue;
                    }
                    final var status = channel.status();
                    if (status == ClprChannelStatus.PENDING || status == ClprChannelStatus.CLOSED) {
                        // Not syncable; should not be cached, but skip defensively.
                        continue;
                    }
                }
                knownChannelIds.add(channelId);
                if (!entry.endpoints().isEmpty()) {
                    peerEndpointCache.put(channelId, new ArrayList<>(entry.endpoints()));
                }
                rehydrated++;
            }
            rebuildPeerCaCache();
            logger.info("CLPR rehydration registered {} channel(s) from disk cache {}", rehydrated, peerEndpointsPath);
        } catch (final Exception e) {
            logger.error("Error during CLPR channel rehydration from disk cache {}", peerEndpointsPath, e);
        }
    }

    /**
     * Marks the in-memory registry + peer endpoint cache as changed. Cheap and side-effect-free:
     * the actual disk write is performed by the single scheduled {@link #flushPeerEndpointsIfDirty()}
     * task (and once more on {@link #stop()}). This mirrors how the rest of the node flushes
     * node-local files — accumulate in memory, write from one place on a cadence — rather than doing
     * blocking I/O at each mutation site.
     */
    private void markPeerEndpointsDirty() {
        peerEndpointsDirty.set(true);
    }

    /**
     * Recomputes and publishes {@link #cachedPeerCaCertificatesByIssuer} from the current
     * {@link #peerEndpointCache}. Must be invoked after every write to {@link #peerEndpointCache}.
     * {@code synchronized} so concurrent writers serialize their rebuild+publish (each mutates before
     * calling this, so the last publish reflects all mutations); readers never take the lock.
     */
    private synchronized void rebuildPeerCaCache() {
        cachedPeerCaCertificatesByIssuer = buildPeerCaCertificates();
    }

    /**
     * Scheduled flush: if the registry/endpoints changed since the last write, serialize them to the
     * node-local file. On failure the dirty flag is restored so the next tick retries.
     */
    private void flushPeerEndpointsIfDirty() {
        if (peerEndpointsDirty.compareAndSet(true, false) && !writePeerEndpointsToDisk()) {
            peerEndpointsDirty.set(true);
        }
    }

    /**
     * Serializes the in-memory registry + endpoint cache to the disk file via an atomic replace.
     *
     * @return {@code true} on success, {@code false} if the write failed (so the caller can retry)
     */
    private synchronized boolean writePeerEndpointsToDisk() {
        try {
            final var entries = new ArrayList<ClprPeerEndpointsEntry>(knownChannelIds.size());
            for (final var channelId : knownChannelIds) {
                entries.add(ClprPeerEndpointsEntry.newBuilder()
                        .channelId(channelId)
                        .endpoints(new ArrayList<>(peerEndpointCache.getOrDefault(channelId, List.of())))
                        .build());
            }
            final var record = ClprPeerEndpoints.newBuilder().entries(entries).build();
            final var absolutePath = peerEndpointsPath.toAbsolutePath();
            Files.createDirectories(requireNonNull(absolutePath.getParent()));
            final var tmp = Files.createTempFile(
                    absolutePath.getParent(), absolutePath.getFileName().toString(), ".tmp");
            try {
                try (final var fout = Files.newOutputStream(tmp)) {
                    ClprPeerEndpoints.JSON.write(record, new WritableStreamingData(fout));
                }
                try {
                    Files.move(tmp, absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException ignore) {
                    Files.move(tmp, absolutePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        } catch (final Exception e) {
            logger.error("Failed to persist CLPR peer endpoints to {}", peerEndpointsPath, e);
            return false;
        }
    }

    /** Reads the disk cache, returning an empty cache if the file is absent or unreadable. */
    private ClprPeerEndpoints readCache() {
        try {
            if (!Files.exists(peerEndpointsPath)) {
                return ClprPeerEndpoints.DEFAULT;
            }
            try (final var fin = Files.newInputStream(peerEndpointsPath)) {
                return ClprPeerEndpoints.JSON.parse(new ReadableStreamingData(fin));
            }
        } catch (final Exception e) {
            logger.error("Failed to read CLPR channel cache from {}", peerEndpointsPath, e);
            return ClprPeerEndpoints.DEFAULT;
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
        peerEndpointsDirty.set(false);
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled()) {
            scheduler.shutdownNow();
            clientCache.shutdownAll();
            return;
        }
        writePeerEndpointsToDisk();
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
            // When the manifest feature flag is off (default until every peer verifier has
            // migrated), the channel's endpoint_manifest stays empty and would starve
            // the outbound sync. Preserve the pre-#346 behavior: seed the peer-endpoint
            // cache from ClprLedgerConfiguration.endpoints on first observation, and pass
            // those endpoints into synchronize() directly. When the flag is on, dial targets
            // come from Channel.endpoint_manifest.endpoints() — populated at
            // ClprCompleteChannel time from the required manifest proof — and there is
            // deliberately no config fallback (spec §4.7).
            if (!clprConfig.endpointManifestEnabled() && !peerEndpointCache.containsKey(channelId)) {
                seedEndpointsFromConfig(channelId, storeFactory);
            }
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
        // messages OR — when the endpoint-manifest feature is on — a local manifest advance the
        // peer has not yet observed. A moved endpoint must propagate even with an empty
        // message queue, or peers stay stuck dialing the obsolete address.
        final boolean noQueuedMessages = channel.nextMessageId() - 1 <= channel.ackedMessageId();
        final long peerObservedManifestVersion = peerObservedManifestVersions.getOrDefault(channel.channelId(), 0L);
        final boolean manifestStaleOnPeer = configProvider
                        .getConfiguration()
                        .getConfigData(ClprConfig.class)
                        .endpointManifestEnabled()
                && readLocalManifestVersion() > peerObservedManifestVersion;
        if (noQueuedMessages && !manifestStaleOnPeer) {
            logger.debug(
                    "[CLPR-SYNC-MANAGER] skipping — no queued messages and peer manifest current conn={} "
                            + "nextMsgId={} ackedMsgId={} peerObservedManifestVersion={}",
                    channelId,
                    channel.nextMessageId(),
                    channel.ackedMessageId(),
                    peerObservedManifestVersion);
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
            logger.warn("Max concurrent sync reached, skipping sync for channel {}", channelId);
            ongoingChannelSyncs.remove(channelId);
            return;
        }

        // Pick the dial-target source based on the manifest feature flag, then hand the
        // resulting list to the synchronizer. The synchronizer is agnostic to which source
        // it came from — this class is the single point that resolves the flag.
        //   * flag OFF (default): endpoints seeded from ClprLedgerConfiguration.endpoints on
        //     first observation. Pre-#346 legacy path for peers whose verifier contracts
        //     haven't migrated to the manifest-aware ABI yet.
        //   * flag ON: endpoints read from Channel.endpoint_manifest.endpoints() (spec §4.7,
        //     the authoritative cached peer manifest). Populated at ClprCompleteChannel
        //     time from the required manifest proof; a channel with an empty manifest
        //     yields an empty list and the sync tick is skipped by the synchronizer.
        // Also read the local ClprEndpointManifest.version() so the synchronizer can decide
        // whether to embed a manifest proof in the outbound bundle (see #335).
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        final List<ClprEndpoint> providedEndpoints = resolveDialTargets(channel, clprConfig);
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
     * Resolves the dial-target list for {@code channel} per the manifest feature flag.
     * Returns an unmodifiable list (possibly empty); never {@code null}.
     */
    @NonNull
    private List<ClprEndpoint> resolveDialTargets(
            @NonNull final ClprChannel channel, @NonNull final ClprConfig clprConfig) {
        if (clprConfig.endpointManifestEnabled()) {
            return channel.hasEndpointManifest()
                    ? List.copyOf(channel.endpointManifestOrThrow().endpoints())
                    : List.of();
        }
        final var cached = peerEndpointCache.get(channel.channelId());
        return cached == null ? List.of() : List.copyOf(cached);
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
     * Single tick of the discovery loop. For each channel whose endpoint cache is
     * non-empty, picks one known peer at random and calls its {@code discoverEndpoints}
     * RPC, merging the response into the local cache. Channels with no cached
     * endpoints are skipped (the sync tick will seed them from ledger config first).
     */
    void discoveryTick() {
        final var configuration = configProvider.getConfiguration();
        final var clprConfig = configuration.getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            return;
        }

        // When mTLS is enabled, the endpoint addresses available in the peer endpoint cache are referring to the
        // mTLS port for sync, not the port used by discovery.
        // In this way, we are not running discovery when mTLS is on. This is fine because discovery will be deprecated
        // by the upcoming Endpoint Manifest feature.
        if (leafCertManager.isMtlsEnabled()) {
            if (discoveryMtlsWarned.compareAndSet(false, true)) {
                logger.warn("CLPR discovery is suppressed while mTLS is enabled: the advertised endpoint "
                        + "port serves the mTLS sync listener only. Peers are learned via ledger config "
                        + "and channel completion.");
            }
            return;
        }
        final var timeout = Duration.ofSeconds(clprConfig.syncTimeoutSeconds());
        final var thisNodeIdentity = getNodeIdentity();
        for (final var entry : peerEndpointCache.entrySet()) {
            final var channelId = entry.getKey();
            final var endpoints = entry.getValue();
            if (endpoints == null || endpoints.isEmpty()) {
                continue;
            }
            final var peer = endpoints.get(ThreadLocalRandom.current().nextInt(endpoints.size()));
            final var svc = peer.serviceEndpoint();
            if (svc == null) {
                continue;
            }
            final var host = svc.ipAddress();
            final var port = svc.port();
            if (thisNodeIdentity.isSelf(host, port)) {
                logger.debug("Skipping self ({}:{}) for discovery on channel {}", host, port, channelId.toHex());
                continue;
            }
            // Discovery is only reached when mTLS is disabled (see the guard at the top of this method),
            // so the client connects in plaintext (null leaf credentials + no peer cert needed). The
            // client is cached and reused per peer by the cache.
            try {
                final var client = clientCache.clientFor(host, port, null, null);
                final var discovered = client.discoverEndpoints(channelId, timeout);
                if (!discovered.isEmpty()) {
                    mergeDiscoveredEndpoints(channelId, discovered);
                    logger.debug(
                            "Discovered {} endpoint(s) for channel {} via peer {}:{}",
                            discovered.size(),
                            channelId.toHex(),
                            host,
                            port);
                }
            } catch (final ClprEndpointClient.ClprDiscoveryException e) {
                logger.debug("discoverEndpoints call to {}:{} failed for channel {}", host, port, channelId.toHex(), e);
            } catch (final Exception e) {
                logger.warn("Unexpected error during discovery tick for channel {}", channelId.toHex(), e);
            }
        }
    }

    /**
     * Seeds the peer endpoint cache for a channel from the ledger configuration's
     * endpoints list.
     */
    @VisibleForTesting
    void seedEndpointsFromConfig(@NonNull final Bytes channelId, @NonNull final ReadableStoreFactoryImpl storeFactory) {
        try {
            final var configStore = storeFactory.readableStore(ReadableLedgerConfigurationStore.class);
            final var ledgerConfig = configStore.getConfiguration();
            if (!ledgerConfig.endpoints().isEmpty()) {
                final var all = ledgerConfig.endpoints();
                // Truncate to the configured max_peer_endpoints (spec §3.10.5).
                // Zero means no peer endpoint limit is enforced.
                final var throttles = ledgerConfig.throttles();
                final int rawPeerLimit = throttles != null ? throttles.maxPeerEndpoints() : 0;
                final int cap = rawPeerLimit > 0 ? Math.min(rawPeerLimit, all.size()) : all.size();
                peerEndpointCache.put(channelId, new ArrayList<>(all.subList(0, cap)));
                rebuildPeerCaCache();
                logger.info(
                        "Seeded {} endpoints (of {} total) for channel {} from ledger configuration",
                        cap,
                        all.size(),
                        channelId.toHex());
            } else {
                logger.warn("No seed endpoints in ledger configuration for channel {}", channelId.toHex());
            }
        } catch (final Exception e) {
            logger.error("Could not seed CLPR endpoints from ledger config", e);
        }
    }

    /**
     * Registers a channel ID in the local registry. Called when a channel
     * is observed (e.g., during submit_bundle handling or via discovery).
     *
     * @param channelId the 32-byte channel ID
     */
    public void registerChannel(@NonNull final Bytes channelId) {
        requireNonNull(channelId);

        if (knownChannelIds.add(channelId)) {
            markPeerEndpointsDirty();
        }
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
            peerEndpointCache.remove(channelId);
            rebuildPeerCaCache();
            peerObservedManifestVersions.remove(channelId);
            logger.debug("Removed channel {} from local registry", channelId.toHex());
            markPeerEndpointsDirty();
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
     * Populates {@link #peerEndpointCache} for {@code channelId} with the peer's endpoints
     * attested at channel-completion time. The caller is expected to have already truncated
     * the list to this ledger's {@code max_peer_endpoints} limit (spec §3.10.5). Replaces any
     * pre-existing entry so the first sync tick sees the freshly verified set rather than stale
     * discovery data.
     */
    @Override
    public void seedPeerEndpoints(@NonNull final Bytes channelId, @NonNull final List<ClprEndpoint> endpoints) {
        requireNonNull(channelId);
        requireNonNull(endpoints);
        if (endpoints.isEmpty()) {
            return;
        }
        peerEndpointCache.put(channelId, new ArrayList<>(endpoints));
        rebuildPeerCaCache();
        markPeerEndpointsDirty();
    }

    /**
     * Returns the known peer endpoints for a channel, or an empty list if none.
     *
     * @param channelId the 32-byte channel ID
     * @return unmodifiable list of known endpoints
     */
    @NonNull
    public List<ClprEndpoint> getKnownEndpoints(@NonNull final Bytes channelId) {
        final var endpoints = peerEndpointCache.get(channelId);
        return endpoints != null ? Collections.unmodifiableList(endpoints) : List.of();
    }

    /**
     * Returns the peer CA certificates this node currently trusts for inbound mTLS sync, indexed by each
     * CA's subject DN — the {@code tls_certificate} of every {@link ClprEndpoint} known across all
     * channels, parsed as an X.509 certificate. Empty or unparseable certificates are skipped. The
     * dedicated mTLS sync listener looks up a connecting client's leaf by its issuer DN (which equals the
     * signing CA's subject DN) and verifies against the matching CA(s).
     *
     * @return the current trusted peer CA certificates keyed by subject DN (possibly empty)
     */
    @NonNull
    public Map<X500Principal, List<X509Certificate>> knownPeerCaCertificatesByIssuer() {
        return cachedPeerCaCertificatesByIssuer;
    }

    /**
     * Parses the {@code tls_certificate} of every known endpoint into an immutable trust index keyed by
     * the CA's subject DN. Distinct CAs sharing a subject DN are grouped in the same bucket (deduplicated).
     *
     * <p>Skipped entirely when this node has mTLS disabled.
     */
    private Map<X500Principal, List<X509Certificate>> buildPeerCaCertificates() {
        if (!leafCertManager.isMtlsEnabled()) {
            return Map.of();
        }
        final var result = new HashMap<X500Principal, List<X509Certificate>>();
        for (final var endpoints : peerEndpointCache.values()) {
            for (final var endpoint : endpoints) {
                final var certBytes = endpoint.tlsCertificate();
                if (certBytes == null || certBytes.length() == 0) {
                    continue;
                }
                final var cert = parsePeerCaCert(certBytes);
                if (cert != null) {
                    final var bucket = result.computeIfAbsent(cert.getSubjectX500Principal(), k -> new ArrayList<>());
                    if (!bucket.contains(cert)) {
                        bucket.add(cert);
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    /** Parses a DER/PEM {@code tls_certificate} into an X.509 cert, or {@code null} (logged) if unparseable. */
    private static X509Certificate parsePeerCaCert(@NonNull final Bytes certBytes) {
        try (final var in = new ByteArrayInputStream(certBytes.toByteArray())) {
            return (X509Certificate) x509Factory().generateCertificate(in);
        } catch (final CertificateException | IOException e) {
            logger.error("Skipping unparseable peer CA certificate", e);
            return null;
        }
    }

    private static CertificateFactory x509Factory() {
        try {
            return CertificateFactory.getInstance("X.509");
        } catch (final CertificateException e) {
            // X.509 is guaranteed to be available on every conformant JRE.
            throw new IllegalStateException("X.509 CertificateFactory unavailable", e);
        }
    }

    /**
     * Merges discovered endpoints into the local cache for a channel.
     *
     * @param channelId the 32-byte channel ID
     * @param endpoints the discovered endpoints to merge
     */
    @VisibleForTesting
    protected void mergeDiscoveredEndpoints(
            @NonNull final Bytes channelId, @NonNull final List<ClprEndpoint> endpoints) {
        requireNonNull(channelId);
        requireNonNull(endpoints);
        if (endpoints.isEmpty()) {
            return;
        }
        final var changed = new AtomicBoolean(false);
        peerEndpointCache.compute(channelId, (ignored, existing) -> {
            if (existing == null) {
                changed.set(true);
                return new ArrayList<>(endpoints);
            }
            final var merged = new ArrayList<>(existing);
            // Merge by service endpoint address — avoid duplicates.
            final var existingAddresses = new LinkedHashSet<String>();
            for (final var ep : merged) {
                if (ep.serviceEndpoint() != null) {
                    existingAddresses.add(ep.serviceEndpoint().ipAddress() + ":"
                            + ep.serviceEndpoint().port());
                }
            }
            for (final var ep : endpoints) {
                if (ep.serviceEndpoint() != null) {
                    final var addr = ep.serviceEndpoint().ipAddress() + ":"
                            + ep.serviceEndpoint().port();
                    if (existingAddresses.add(addr)) {
                        merged.add(ep);
                    }
                }
            }
            // This merge only ever appends (deduped by address) and never removes, so an unchanged
            // size means nothing new was added — the set is identical. Keep the existing (immutable,
            // already-published) list so we neither publish a redundant copy nor flag the file dirty
            // for a no-op rewrite.
            if (merged.size() == existing.size()) {
                return existing;
            }
            changed.set(true);
            return merged;
        });
        if (changed.get()) {
            rebuildPeerCaCache();
            markPeerEndpointsDirty();
        }
    }

    /**
     * Checks if the channel manager is currently started.
     *
     * @return true if the channel manager is started, false otherwise
     */
    public boolean started() {
        return started && !scheduler.isShutdown();
    }

    /**
     * Checks whether the peer endpoint discovery is enabled.
     *
     * @return true if peer endpoint discovery is enabled, false otherwise
     */
    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    @VisibleForTesting
    ScheduledExecutorService scheduler() {
        return this.scheduler;
    }

    @VisibleForTesting
    ScheduledFuture<?> syncTickFuture(@NonNull final Bytes channelId) {
        return channelSyncFutures.get(channelId);
    }

    private NodeIdentity getNodeIdentity() {
        final var configuration = configProvider.getConfiguration();
        return new NodeIdentity(
                configuration.getConfigData(GrpcConfig.class),
                configuration.getConfigData(ClprConfig.class).mtlsPort(),
                networkInfo.selfNodeInfo());
    }
}
