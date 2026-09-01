// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.history.HistoryService;
import com.hedera.node.app.history.impl.ReadableHistoryStoreImpl;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.state.notifications.StateHashedListener;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.ReadablePlatformStateStore;

/**
 * Provides access to immutable state snapshots tied to signed blocks.
 *
 * <p>Implements {@link StateHashedListener} to observe when states are hashed.
 * At that moment, {@code stateLifecycleManager.getLatestImmutableState()} returns
 * the just-hashed state. Block metadata is registered separately via
 * {@link #registerBlockMetadata} when a block is signed.
 *
 * <p>Cached states are explicitly reserved (via {@code getRoot().tryReserve()}) on insert and
 * released on eviction, since {@link StateLifecycleManager#getLatestImmutableState()} drops its
 * own reservation as soon as the next round's copy is made. Snapshots handed out by
 * {@link #latestSnapshot()} carry an additional reservation that the caller releases by
 * closing the snapshot.
 *
 * <p>Routine eviction is piggybacked on {@link #notify(StateHashedNotification) notify()} and
 * {@link #registerBlockMetadata}. When {@code notify()} fires for the freeze round (detected by
 * comparing the notification's round to the just-cached state's {@code latestFreezeRound}), the
 * whole cache is drained — round production halts for a freeze so the freeze block's own cached
 * reservation has no later notify() to release it, and {@code StateSnapshotManager}'s
 * freeze-state write would block on that surplus reservation forever.
 */
@Singleton
public final class BlockProvenStateAccessor implements BlockProvenSnapshotProvider, StateHashedListener {
    private static final Logger log = LogManager.getLogger(BlockProvenStateAccessor.class);

    private static final long TTL_SECONDS = 30L;

    private final StateLifecycleManager<? extends State, ?> stateLifecycleManager;

    /** Block metadata keyed by the state hash it applies to. */
    private final Map<Bytes, BlockMetadata> blockMetasByStateHash = new HashMap<>();

    /** Immutable state snapshots keyed by their hash; each entry holds its own reservation. */
    private final Map<Bytes, CachedState> immutableStatesByHash = new HashMap<>();

    /**
     * Most recent state hash for which we have both state + metadata.
     */
    private Bytes latestCompletableStateHash = null;

    public BlockProvenStateAccessor(@NonNull final StateLifecycleManager<? extends State, ?> stateLifecycleManager) {
        this.stateLifecycleManager = requireNonNull(stateLifecycleManager);
    }

    /**
     * Called via the {@link StateHashedListener} notification when a state has been hashed.
     * At this point, {@link StateLifecycleManager#getLatestImmutableState()} returns the hashed state.
     */
    @Override
    public synchronized void notify(@NonNull final StateHashedNotification notification) {
        requireNonNull(notification);
        final var immutableState = stateLifecycleManager.getLatestImmutableState();
        if (immutableState == null) {
            return;
        }
        final var hash = immutableState.getHash();
        if (hash == null) {
            // The latest immutable state has already advanced past the notified round and is not
            // yet hashed; the notified round's state is no longer reachable, so this round is a gap.
            log.debug(
                    "BlockProvenStateAccessor.notify: latest immutable state not yet hashed; "
                            + "missed state for round {}",
                    notification.round());
            return;
        }
        final var stateHash = hash.getBytes();
        if (!stateHash.equals(notification.hash().getBytes())) {
            // The listener ran late and the latest immutable state is for a newer round than the
            // notification's. Caching it under its own hash below is still valid; just record the
            // gap, since the notified round's state can no longer be captured.
            log.debug(
                    "BlockProvenStateAccessor.notify: latest immutable state is newer than the "
                            + "notified round {}; that round's state was missed",
                    notification.round());
        }
        if (!immutableStatesByHash.containsKey(stateHash)) {
            // Take our own reservation; getLatestImmutableState() gives no durable guarantee and
            // the lifecycle manager releases its reference on the next copyMutableState(). Use
            // tryReserve to tolerate the (unlikely) case the state was already destroyed.
            if (!(immutableState instanceof VirtualMapState merkleState)
                    || !merkleState.getRoot().tryReserve()) {
                log.warn(
                        "BlockProvenStateAccessor.notify: could not reserve immutable state for round {}; skipping",
                        notification.round());
                return;
            }
            immutableStatesByHash.put(stateHash, new CachedState(immutableState, nowSeconds()));
            log.debug(
                    "BlockProvenStateAccessor.notify: stored immutable state hash={}",
                    stateHash.toHex().substring(0, Math.min(16, stateHash.toByteArray().length * 2)));
        }

        final var meta = blockMetasByStateHash.get(stateHash);
        if (meta != null) {
            latestCompletableStateHash = stateHash;
            log.debug("BlockProvenStateAccessor.notify: latestCompletableStateHash updated (had matching meta)");
        }

        purgeExpiredEntries();

        // If this notification is for the freeze round, drain everything: round production stops
        // here, so the freeze block's own cached reservation has no later notify() to release it
        // via TTL — and StateSnapshotManager's FREEZE_STATE write would block on that reservation.
        final long freezeRound = readLatestFreezeRound(immutableState);
        if (freezeRound > 0 && notification.round() == freezeRound) {
            log.info(
                    "BlockProvenStateAccessor.notify: freeze round {} cached; draining to release reservations",
                    freezeRound);
            releaseAllReservations();
        }
    }

    private static long readLatestFreezeRound(@NonNull final State state) {
        try {
            return new ReadablePlatformStateStore(state.getReadableStates(PlatformStateService.NAME))
                    .getLatestFreezeRound();
        } catch (final Exception e) {
            // Early bring-up: PlatformStateService may not be registered yet. Treat as "no freeze".
            return 0L;
        }
    }

    /**
     * Registers block metadata required to create state proofs for a particular immutable state snapshot.
     *
     * @param stateHash the hash of the immutable state snapshot at the relevant point in time
     * @param blockHash the hash of the block
     * @param tssSignature the TSS signature for the block
     * @param blockTimestamp the timestamp of the block
     * @param path the partial Merkle path from the state's subroot to the block root
     */
    public synchronized void registerBlockMetadata(
            @NonNull final Bytes stateHash,
            @NonNull final Bytes blockHash,
            @NonNull final Bytes tssSignature,
            @NonNull final Timestamp blockTimestamp,
            @NonNull final MerklePath path) {
        requireNonNull(stateHash);
        requireNonNull(blockHash);
        requireNonNull(tssSignature);
        requireNonNull(blockTimestamp);
        requireNonNull(path);

        blockMetasByStateHash.put(
                stateHash, new BlockMetadata(blockHash, tssSignature, blockTimestamp, path, nowSeconds()));
        log.debug(
                "BlockProvenStateAccessor.registerBlockMetadata: stateHash={} hasImmutableState={}",
                stateHash.toHex().substring(0, Math.min(16, stateHash.toByteArray().length * 2)),
                immutableStatesByHash.containsKey(stateHash));

        if (immutableStatesByHash.containsKey(stateHash)) {
            latestCompletableStateHash = stateHash;
            log.debug("BlockProvenStateAccessor.registerBlockMetadata: latestCompletableStateHash updated");
        } else {
            log.info(
                    "Registered block metadata for state hash {} before immutable state was observed",
                    stateHash.toHex().substring(0, Math.min(16, stateHash.toByteArray().length * 2)));
        }

        purgeExpiredEntries();
    }

    /**
     * Returns the most recent snapshot for which we have both an immutable state and block metadata.
     *
     * <p>The returned snapshot holds its own reservation on the underlying state; the caller must
     * {@link BlockProvenSnapshot#close()} it (ideally via try-with-resources) when done.
     */
    @Override
    @NonNull
    public synchronized Optional<BlockProvenSnapshot> latestSnapshot() {
        if (latestCompletableStateHash == null) {
            return Optional.empty();
        }
        final var cachedState = immutableStatesByHash.get(latestCompletableStateHash);
        final var meta = blockMetasByStateHash.get(latestCompletableStateHash);
        if (cachedState == null || meta == null) {
            return Optional.empty();
        }
        final var state = cachedState.state();
        // Reserve on behalf of the caller; released when the snapshot is closed. With cache
        // entries holding their own reservations this should never fail, but if it somehow
        // does, drop the unusable entry rather than handing out a destroyed state.
        if (!(state instanceof VirtualMapState merkleState)
                || !merkleState.getRoot().tryReserve()) {
            log.warn("BlockProvenStateAccessor.latestSnapshot: cached state was unexpectedly destroyed; dropping");
            immutableStatesByHash.remove(latestCompletableStateHash);
            latestCompletableStateHash = null;
            return Optional.empty();
        }
        return Optional.of(new BlockSignedSnapshot(
                state, meta.tssSignature(), meta.blockTimestamp(), meta.path(), readLedgerIdFrom(state)));
    }

    /**
     * Looks up the ledger id from {@link com.hedera.node.app.history.ReadableHistoryStore} for the
     * given immutable state. Returns {@link Bytes#EMPTY} if the history state isn't initialized
     * yet or the lookup throws (early bring-up).
     */
    @NonNull
    private static Bytes readLedgerIdFrom(@NonNull final State state) {
        try {
            final var historyStore = new ReadableHistoryStoreImpl(state.getReadableStates(HistoryService.NAME));
            final var ledgerId = historyStore.getLedgerId();
            return ledgerId != null ? ledgerId : Bytes.EMPTY;
        } catch (final Exception e) {
            log.debug("Could not read ledger id from history store: {}", e.getMessage());
            return Bytes.EMPTY;
        }
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * Drops every cached state reservation and clears all metadata. In-flight snapshots handed
     * out by {@link #latestSnapshot()} hold their own independent reservations and are
     * unaffected; only the cache's own reservations are released here.
     */
    synchronized void releaseAllReservations() {
        if (immutableStatesByHash.isEmpty() && blockMetasByStateHash.isEmpty()) {
            return;
        }
        log.info(
                "BlockProvenStateAccessor.releaseAllReservations: draining {} cached state(s), {} metadata entry(s)",
                immutableStatesByHash.size(),
                blockMetasByStateHash.size());
        for (final var cached : immutableStatesByHash.values()) {
            cached.state().release();
        }
        immutableStatesByHash.clear();
        blockMetasByStateHash.clear();
        latestCompletableStateHash = null;
    }

    private synchronized void purgeExpiredEntries() {
        if (blockMetasByStateHash.isEmpty() && immutableStatesByHash.isEmpty()) {
            return;
        }
        final long now = nowSeconds();
        purgeExpiredMetas(now);
        purgeExpiredStates(now);

        if (latestCompletableStateHash != null) {
            final var cachedState = immutableStatesByHash.get(latestCompletableStateHash);
            final var meta = blockMetasByStateHash.get(latestCompletableStateHash);
            if (cachedState == null || meta == null) {
                latestCompletableStateHash = null;
            }
        }
    }

    private void purgeExpiredMetas(final long now) {
        // Compare wall-clock seconds to wall-clock seconds. Using blockTimestamp().seconds()
        // (consensus time) for this would mis-purge during replay/catchup when consensus time
        // lags wall clock by more than TTL — every freshly-registered entry would look expired
        // and latestSnapshot() would perpetually return Optional.empty().
        final Iterator<Map.Entry<Bytes, BlockMetadata>> it =
                blockMetasByStateHash.entrySet().iterator();
        while (it.hasNext()) {
            final var e = it.next();
            final var meta = e.getValue();
            if (now - meta.insertedAtSeconds() > TTL_SECONDS) {
                it.remove();
            }
        }
    }

    private void purgeExpiredStates(final long now) {
        final Iterator<Map.Entry<Bytes, CachedState>> it =
                immutableStatesByHash.entrySet().iterator();
        while (it.hasNext()) {
            final var e = it.next();
            final var cached = e.getValue();
            if (now - cached.observedAtSeconds() > TTL_SECONDS) {
                // Release the reservation taken when the entry was cached. Snapshots already
                // handed out remain safe — each holds its own reservation until closed.
                cached.state().release();
                it.remove();
            }
        }
    }

    private record BlockMetadata(
            @NonNull Bytes blockHash,
            @NonNull Bytes tssSignature,
            @NonNull Timestamp blockTimestamp,
            @NonNull MerklePath path,
            long insertedAtSeconds) {}

    private record CachedState(@NonNull State state, long observedAtSeconds) {}

    /**
     * A {@link BlockProvenSnapshot} holding a reservation on its state that is released exactly
     * once when the snapshot is closed.
     */
    public static final class BlockSignedSnapshot implements BlockProvenSnapshot {
        private final State state;
        private final Bytes tssSignature;
        private final Timestamp blockTimestamp;
        private final MerklePath path;
        private final Bytes ledgerId;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public BlockSignedSnapshot(
                @NonNull final State state,
                @NonNull final Bytes tssSignature,
                @NonNull final Timestamp blockTimestamp,
                @NonNull final MerklePath path,
                @NonNull final Bytes ledgerId) {
            this.state = requireNonNull(state);
            this.tssSignature = requireNonNull(tssSignature);
            this.blockTimestamp = requireNonNull(blockTimestamp);
            this.path = requireNonNull(path);
            this.ledgerId = requireNonNull(ledgerId);
        }

        @Override
        @NonNull
        public State state() {
            return state;
        }

        @Override
        @NonNull
        public Bytes tssSignature() {
            return tssSignature;
        }

        @Override
        @NonNull
        public Bytes ledgerId() {
            return ledgerId;
        }

        @Override
        @NonNull
        public Timestamp blockTimestamp() {
            return blockTimestamp;
        }

        @Override
        @NonNull
        public MerklePath path() {
            return path;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.release();
            }
        }
    }
}
