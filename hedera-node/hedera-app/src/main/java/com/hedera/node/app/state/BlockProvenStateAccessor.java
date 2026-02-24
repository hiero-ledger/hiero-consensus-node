// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.state.BlockProvenSnapshot;
import com.hedera.node.app.spi.state.BlockProvenSnapshotProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides access to immutable state snapshots tied to signed blocks.
 *
 * <p>This class caches a small/TTL-bounded set of immutable snapshots keyed by state hash and joins them
 * with block metadata when available.
 */
@Singleton
public final class BlockProvenStateAccessor implements BlockProvenSnapshotProvider {
    private static final Logger log = LogManager.getLogger(BlockProvenStateAccessor.class);

    /**
     * Keep cached block metadata and cached immutable states only briefly.
     */
    private static final long TTL_SECONDS = 30L;

    /** Block metadata keyed by the state hash it applies to. */
    private final Map<Bytes, BlockMetadata> blockMetasByStateHash = new HashMap<>();

    /** Immutable state snapshots keyed by their hash. */
    private final Map<Bytes, CachedState> immutableStatesByHash = new HashMap<>();

    /**
     * Tracks the most recent state hash for which we can produce a snapshot (i.e., we have both state + metadata).
     * This avoids returning "latest immutable" which may be unrelated to any signed block.
     */
    private Bytes latestCompletableStateHash = null;

    private CompletableFuture<Void> purgeFuture = CompletableFuture.completedFuture(null);

    public BlockProvenStateAccessor(@NonNull final StateLifecycleManager<? extends State, ?> stateLifecycleManager) {
        stateLifecycleManager.addObserver(this::observeImmutableState);
    }

    /**
     * Called by the node when an immutable state snapshot is available.
     *
     * <p>This should be wired to the place where immutable (sealed) states are produced/observed.
     * The accessor keeps only a short-lived cache of these snapshots.
     *
     * @param immutableState an immutable, non-destroyed state snapshot
     */
    public synchronized void observeImmutableState(@NonNull final State immutableState) {
        requireNonNull(immutableState);
        if (immutableState.isDestroyed() || immutableState.isMutable()) {
            return;
        }
        final var hash = immutableState.getHash();
        if (hash == null) {
            return;
        }
        final var stateHash = hash.getBytes();
        immutableStatesByHash.put(stateHash, new CachedState(immutableState, nowSeconds()));

        final var meta = blockMetasByStateHash.get(stateHash);
        if (meta != null) {
            latestCompletableStateHash = stateHash;
        }

        purgeFuture = purgeFuture.thenRun(this::purgeExpiredEntries);
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

//        log.warn(
//                "TSS_DEBUG registerBlockMetadata: stateHash={} blockHash={} (len={}), tssSignatureLen={}, pathSiblings={}, pathHash={}",
//                stateHash.toHex(),
//                blockHash.toHex(),
//                blockHash.length(),
//                tssSignature.length(),
//                path.siblings().size(),
//                path.hasHash() ? path.hash().toHex() : "<none>");
        blockMetasByStateHash.put(stateHash, new BlockMetadata(blockHash, tssSignature, blockTimestamp, path));

        if (immutableStatesByHash.containsKey(stateHash)) {
            latestCompletableStateHash = stateHash;
        } else if (log.isDebugEnabled()) {
            log.debug("Registered block metadata for state hash {} before immutable state was observed", stateHash);
        }

        purgeFuture = purgeFuture.thenRun(this::purgeExpiredEntries);
    }

    /**
     * Returns the most recent snapshot for which we have both an immutable state and block metadata.
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
//        log.warn(
//                "TSS_DEBUG latestSnapshot: stateHash={} blockHash={} stateType={}",
//                latestCompletableStateHash.toHex(),
//                meta.blockHash().toHex(),
//                cachedState.state().getClass().getSimpleName());
        return Optional.of(
                new BlockSignedSnapshot(cachedState.state(), meta.tssSignature(), meta.blockTimestamp(), meta.path()));
    }

    /**
     * Returns the latest sealed state snapshot, if one has been observed.
     *
     * @return optional immutable state
     */
    public synchronized Optional<State> latestState() {
        return latestSnapshot().map(BlockProvenSnapshot::state);
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
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
        final Iterator<Map.Entry<Bytes, BlockMetadata>> it =
                blockMetasByStateHash.entrySet().iterator();
        while (it.hasNext()) {
            final var e = it.next();
            final var meta = e.getValue();
            final long inserted = meta.blockTimestamp().seconds();
            if (now - inserted > TTL_SECONDS) {
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
                it.remove();
            }
        }
    }

    private record BlockMetadata(
            @NonNull Bytes blockHash,
            @NonNull Bytes tssSignature,
            @NonNull Timestamp blockTimestamp,
            @NonNull MerklePath path) {}

    private record CachedState(@NonNull State state, long observedAtSeconds) {}

    public record BlockSignedSnapshot(
            @NonNull State state,
            @NonNull Bytes tssSignature,
            @NonNull Timestamp blockTimestamp,
            @NonNull MerklePath path)
            implements BlockProvenSnapshot {}
}
