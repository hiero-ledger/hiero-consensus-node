// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.utils;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema;
import com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.binary.MerkleProof;
import com.swirlds.state.binary.QueueState;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;

/**
 * Wrapper around BlockStreamManagerImpl that provides a simpler API for benchmarking.
 * Handles creation of minimal Round and State objects internally.
 */
@SuppressWarnings("unchecked")
public class BlockStreamManagerWrapper {
    private final BlockStreamManagerImpl manager;
    private long currentRoundNumber = 1;
    private final BenchmarkState state;
    private final AtomicReference<BlockStreamInfo> blockStreamInfoRef;
    private final AtomicReference<Long> itemsWrittenRef;

    public BlockStreamManagerWrapper(Supplier<BlockItemWriter> writerSupplier) {
        this.itemsWrittenRef = new AtomicReference<>(0L);
        this.blockStreamInfoRef = new AtomicReference<>(BlockStreamInfo.newBuilder()
                .blockNumber(0)
                .trailingBlockHashes(Bytes.EMPTY)
                .intermediatePreviousBlockRootHashes(Collections.emptyList())
                .intermediateBlockRootsLeafCount(0L)
                .build());

        this.state = new BenchmarkState(blockStreamInfoRef);

        // Wrap writer to track items written
        Supplier<BlockItemWriter> trackingWriterSupplier = () -> {
            BlockItemWriter original = writerSupplier.get();
            return new BlockItemWriter() {
                @Override
                public void openBlock(long blockNumber) {
                    original.openBlock(blockNumber);
                }

                @Override
                public void writePbjItemAndBytes(@NonNull BlockItem item, @NonNull Bytes bytes) {
                    itemsWrittenRef.updateAndGet(v -> v + 1);
                    original.writePbjItemAndBytes(item, bytes);
                }

                @Override
                public void writePbjItem(@NonNull BlockItem item) {
                    itemsWrittenRef.updateAndGet(v -> v + 1);
                    original.writePbjItem(item);
                }

                @Override
                public void closeCompleteBlock() {
                    original.closeCompleteBlock();
                }

                @Override
                public void flushPendingBlock(@NonNull PendingProof pendingProof) {
                    original.flushPendingBlock(pendingProof);
                }

                @Override
                public void jumpToBlockAfterFreeze(long blockNumber) {
                    original.jumpToBlockAfterFreeze(blockNumber);
                }
            };
        };

        ConfigProvider configProvider = NoOpDependencies.createBenchmarkConfigProvider();
        QuiescenceController quiescenceController =
                NoOpDependencies.createBenchmarkQuiescenceController(configProvider);

        this.manager = new BlockStreamManagerImpl(
                new NoOpDependencies.RealisticBlockHashSigner(),
                trackingWriterSupplier,
                ForkJoinPool.commonPool(),
                configProvider,
                NoOpDependencies.createBenchmarkBoundaryStateChangeListener(configProvider),
                new NoOpDependencies.NoOpPlatform(),
                quiescenceController,
                NoOpDependencies.createNoOpInitialStateHash(),
                SemanticVersion.DEFAULT,
                new NoOpDependencies.NoOpLifecycle(),
                NoOpDependencies.createBenchmarkQuiescedHeartbeat(quiescenceController),
                new NoOpDependencies.NoOpMetrics());

        manager.init(state, BlockStreamManager.HASH_OF_ZERO);
    }

    public void startBlock(long blockNumber, BlockItem header) {
        state.updateBlockNumber(blockNumber);
        BenchmarkRound round = new BenchmarkRound(currentRoundNumber++, Instant.now());
        manager.startRound(round, state);
        manager.writeItem(header);
    }

    public void writeItem(@NonNull BlockItem item) {
        manager.writeItem(item);
    }

    public void sealBlock(BlockItem proof) {
        manager.writeItem(proof);
        long roundNum = currentRoundNumber - 1;

        // Complete the state hash future for this round BEFORE endRound()
        // (endRound() for the NEXT block will wait for this future)
        // In production, this is done by the platform's state hashing notification system
        // For benchmark, we simulate it with a dummy hash
        Hash dummyStateHash = new Hash(new byte[48]); // 48 bytes = SHA-384 hash size
        StateHashedNotification notification = new StateHashedNotification(roundNum, dummyStateHash);
        manager.notify(notification);

        // Now endRound() can proceed (it waits for the previous round's future, which we completed above)
        manager.endRound(state, roundNum);
    }

    public long getTotalItemsWritten() {
        return itemsWrittenRef.get();
    }

    public Bytes getLastComputedBlockHash() {
        return manager.blockHashByBlockNumber(manager.blockNo() - 1);
    }

    // Minimal Round implementation
    private static class BenchmarkRound implements Round {
        private final long roundNum;
        private final Instant consensusTimestamp;

        BenchmarkRound(long roundNum, Instant consensusTimestamp) {
            this.roundNum = roundNum;
            this.consensusTimestamp = consensusTimestamp;
        }

        @Override
        public @NonNull Iterator<ConsensusEvent> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public long getRoundNum() {
            return roundNum;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public int getEventCount() {
            return 0;
        }

        @Override
        public @NonNull Roster getConsensusRoster() {
            return Roster.DEFAULT;
        }

        @Override
        public @NonNull Instant getConsensusTimestamp() {
            return consensusTimestamp;
        }
    }

    // Minimal VirtualMapState implementation
    private static class BenchmarkState implements VirtualMapState {
        private Hash hash;
        private final AtomicReference<BlockStreamInfo> blockStreamInfoRef;
        private long blockNumber = 0;

        BenchmarkState(AtomicReference<BlockStreamInfo> blockStreamInfoRef) {
            this.blockStreamInfoRef = blockStreamInfoRef;
        }

        @Override
        public @NonNull VirtualMapState copy() {
            return this; // No-op for benchmark
        }

        @Override
        public VirtualMap getRoot() {
            return null; // Not needed for benchmark
        }

        @Override
        public void setHash(@NonNull Hash hash) {
            this.hash = hash;
        }

        @Override
        public @NonNull Hash getHash() {
            return hash != null ? hash : new Hash(new byte[48]);
        }

        void updateBlockNumber(long blockNumber) {
            this.blockNumber = blockNumber;
            blockStreamInfoRef.set(BlockStreamInfo.newBuilder()
                    .blockNumber(blockNumber)
                    .trailingBlockHashes(Bytes.EMPTY)
                    .intermediatePreviousBlockRootHashes(Collections.emptyList())
                    .intermediateBlockRootsLeafCount(0L)
                    .build());
        }

        // VirtualMapState required methods - minimal stub implementations for benchmarking
        @Override
        public void commitSingletons() {
            // No-op for benchmark
        }

        @Override
        public void initializeState(@NonNull StateMetadata<?, ?> md) {
            // No-op for benchmark
        }

        @Override
        public void removeServiceState(@NonNull String serviceName, int stateId) {
            // No-op for benchmark
        }

        @Override
        public Hash getHashForPath(long path) {
            return null;
        }

        @Override
        public MerkleProof getMerkleProof(long path) {
            return null;
        }

        @Override
        public long getSingletonPath(int stateId) {
            return -1;
        }

        @Override
        public long getQueueElementPath(int stateId, @NonNull Bytes expectedValue) {
            return -1;
        }

        @Override
        public long getKvPath(int stateId, @NonNull Bytes key) {
            return -1;
        }

        @Override
        public Bytes getKv(int stateId, @NonNull Bytes key) {
            return null;
        }

        @Override
        public Bytes getSingleton(int singletonId) {
            return null;
        }

        @Override
        public QueueState getQueueState(int stateId) {
            return null;
        }

        @Override
        public Bytes peekQueueHead(int stateId) {
            return null;
        }

        @Override
        public Bytes peekQueueTail(int stateId) {
            return null;
        }

        @Override
        public Bytes peekQueue(int stateId, int index) {
            return null;
        }

        @Override
        public List<Bytes> getQueueAsList(int stateId) {
            return Collections.emptyList();
        }

        @Override
        public void updateSingleton(int stateId, @NonNull Bytes value) {
            // No-op for benchmark
        }

        @Override
        public void removeSingleton(int stateId) {
            // No-op for benchmark
        }

        @Override
        public void updateKv(int stateId, @NonNull Bytes key, Bytes value) {
            // No-op for benchmark
        }

        @Override
        public void removeKv(int stateId, @NonNull Bytes key) {
            // No-op for benchmark
        }

        @Override
        public void pushQueue(int stateId, @NonNull Bytes value) {
            // No-op for benchmark
        }

        @Override
        public Bytes popQueue(int stateId) {
            return null;
        }

        @Override
        public void removeQueue(int stateId) {
            // No-op for benchmark
        }

        @Override
        public @NonNull ReadableStates getReadableStates(@NonNull String serviceName) {
            return switch (serviceName) {
                case "BlockStreamService" ->
                    new ReadableStates() {
                        @Override
                        public @NonNull <T> ReadableSingletonState<T> getSingleton(int stateId) {
                            return new ReadableSingletonState<>() {
                                @Override
                                public @NonNull T get() {
                                    return (T) blockStreamInfoRef.get();
                                }

                                @Override
                                public int getStateId() {
                                    return stateId;
                                }

                                @Override
                                public boolean isRead() {
                                    return true;
                                }
                            };
                        }

                        @Override
                        public @NonNull <K, V> ReadableKVState<K, V> get(int stateId) {
                            throw new UnsupportedOperationException("KV states not supported");
                        }

                        @Override
                        public @NonNull <T> ReadableQueueState<T> getQueue(int stateId) {
                            throw new UnsupportedOperationException("Queue states not supported");
                        }

                        @Override
                        public boolean contains(int stateId) {
                            return stateId == V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
                        }

                        @Override
                        public @NonNull Set<Integer> stateIds() {
                            return Set.of(V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID);
                        }
                    };
                case "PlatformStateService" ->
                    new ReadableStates() {
                        @Override
                        public @NonNull <T> ReadableSingletonState<T> getSingleton(int stateId) {
                            return new ReadableSingletonState<>() {
                                @Override
                                public @NonNull T get() {
                                    return (T) PlatformState.DEFAULT;
                                }

                                @Override
                                public int getStateId() {
                                    return stateId;
                                }

                                @Override
                                public boolean isRead() {
                                    return true;
                                }
                            };
                        }

                        @Override
                        public @NonNull <K, V> ReadableKVState<K, V> get(int stateId) {
                            throw new UnsupportedOperationException("KV states not supported");
                        }

                        @Override
                        public @NonNull <T> ReadableQueueState<T> getQueue(int stateId) {
                            throw new UnsupportedOperationException("Queue states not supported");
                        }

                        @Override
                        public boolean contains(int stateId) {
                            return true;
                        }

                        @Override
                        public @NonNull Set<Integer> stateIds() {
                            return Set.of(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID);
                        }
                    };
                case EntityIdService.NAME ->
                    new ReadableStates() {
                        @Override
                        @SuppressWarnings("deprecation")
                        public @NonNull <T> ReadableSingletonState<T> getSingleton(int stateId) {
                            return new ReadableSingletonState<>() {
                                @Override
                                @SuppressWarnings("deprecation")
                                public @NonNull T get() {
                                    T result;
                                    if (stateId == V0490EntityIdSchema.ENTITY_ID_STATE_ID) {
                                        @SuppressWarnings("deprecation")
                                        EntityNumber entityNumber = EntityNumber.DEFAULT;
                                        result = (T) entityNumber;
                                    } else if (stateId == V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID) {
                                        result = (T) EntityCounts.DEFAULT;
                                    } else {
                                        throw new UnsupportedOperationException(
                                                "Unknown EntityIdService state ID: " + stateId);
                                    }
                                    return result;
                                }

                                @Override
                                public int getStateId() {
                                    return stateId;
                                }

                                @Override
                                public boolean isRead() {
                                    return true;
                                }
                            };
                        }

                        @Override
                        public @NonNull <K, V> ReadableKVState<K, V> get(int stateId) {
                            throw new UnsupportedOperationException("KV states not supported");
                        }

                        @Override
                        public @NonNull <T> ReadableQueueState<T> getQueue(int stateId) {
                            throw new UnsupportedOperationException("Queue states not supported");
                        }

                        @Override
                        public boolean contains(int stateId) {
                            return stateId == V0490EntityIdSchema.ENTITY_ID_STATE_ID
                                    || stateId == V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
                        }

                        @Override
                        public @NonNull Set<Integer> stateIds() {
                            return Set.of(
                                    V0490EntityIdSchema.ENTITY_ID_STATE_ID, V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID);
                        }
                    };
                default ->
                    // Empty ReadableStates for other services
                    new ReadableStates() {
                        @Override
                        public @NonNull <T> ReadableSingletonState<T> getSingleton(int stateId) {
                            throw new UnsupportedOperationException("Service not supported: " + serviceName);
                        }

                        @Override
                        public @NonNull <K, V> ReadableKVState<K, V> get(int stateId) {
                            throw new UnsupportedOperationException("KV states not supported");
                        }

                        @Override
                        public @NonNull <T> ReadableQueueState<T> getQueue(int stateId) {
                            throw new UnsupportedOperationException("Queue states not supported");
                        }

                        @Override
                        public boolean contains(int stateId) {
                            return false;
                        }

                        @Override
                        public @NonNull Set<Integer> stateIds() {
                            return Collections.emptySet();
                        }
                    };
            };
        }

        @Override
        public @NonNull WritableStates getWritableStates(@NonNull String serviceName) {
            if ("BlockStreamService".equals(serviceName)) {
                return new CommittableBlockStreamWritableStates(blockStreamInfoRef);
            }
            throw new UnsupportedOperationException("WritableStates not supported for: " + serviceName);
        }
    }

    /** WritableStates implementation that also implements CommittableWritableStates */
    private static class CommittableBlockStreamWritableStates implements WritableStates, CommittableWritableStates {
        private final AtomicReference<BlockStreamInfo> blockStreamInfoRef;

        @SuppressWarnings({"FieldCanBeLocal", "unused"})
        private Bytes lastStateReadHash = Bytes.EMPTY; // Prevent Dead Code Elimination of state read simulation

        @SuppressWarnings({"FieldCanBeLocal", "unused"})
        private Bytes lastStateCommitHash = Bytes.EMPTY; // Prevent Dead Code Elimination of state commit simulation

        CommittableBlockStreamWritableStates(AtomicReference<BlockStreamInfo> blockStreamInfoRef) {
            this.blockStreamInfoRef = blockStreamInfoRef;
        }

        /**
         * SIMULATED STATE READ: Mimics the cost of VirtualMap.get() to read BlockStreamInfo.
         *
         * In production, reading BlockStreamInfo from VirtualMap at startRound() involves
         * VirtualMap.get() which traverses the merkle path from root to leaf. This involves
         * ~log(N) hash verifications where N is the total number of leaves.
         *
         * For efficiency, we simulate a smaller read cost (~15 hashes) since reads are
         * typically cached and don't require full path rehashing like writes do.
         */
        private void simulateStateRead() {
            final int READ_HASH_COUNT = 15;
            final byte[] NULL_HASH = new byte[48]; // SHA-384 = 48 bytes

            try {
                final var digest = java.security.MessageDigest.getInstance("SHA-384");
                byte[] hash = blockStreamInfoRef.get() != null
                        ? digest.digest(blockStreamInfoRef.get().toString().getBytes())
                        : digest.digest(new byte[48]);

                // Simulate read cost (path traversal with cached hashes)
                for (int i = 0; i < READ_HASH_COUNT; i++) {
                    digest.reset();
                    digest.update(hash);
                    digest.update(NULL_HASH);
                    hash = digest.digest();
                }

                // Prevent Dead Code Elimination: Store result
                lastStateReadHash = Bytes.wrap(hash);
            } catch (Exception e) {
                // Ignore - simulation failure shouldn't break benchmark
            }
        }

        /**
         * SIMULATED STATE COMMIT: Mimics the cost of VirtualMap.put() + commit().
         *
         * In production, committing BlockStreamInfo to VirtualMap triggers VirtualHasher
         * to rehash the merkle path from the modified leaf to the root. This involves
         * ~log(N) SHA-384 hash operations where N is the total number of leaves in the
         * VirtualMap.
         *
         * For a typical VirtualMap with millions of entries, the tree depth is ~25-30,
         * so we simulate 28 SHA-384 hashes (path from leaf to root).
         *
         * This mimics the REAL CPU cost without requiring actual VirtualMap infrastructure.
         */
        private void simulateStateCommit() {
            final int VIRTUAL_MAP_TREE_DEPTH = 28; // Typical depth for large VirtualMap
            final byte[] NULL_HASH = new byte[48]; // SHA-384 = 48 bytes

            try {
                final var digest = java.security.MessageDigest.getInstance("SHA-384");
                BlockStreamInfo info = blockStreamInfoRef.get();
                byte[] hash = info != null
                        ? digest.digest(info.toString().getBytes()) // Leaf hash
                        : digest.digest(new byte[48]);

                // REAL: Rehash from leaf to root (log N hashes)
                // This is what VirtualHasher does when we commit a write
                for (int depth = 0; depth < VIRTUAL_MAP_TREE_DEPTH; depth++) {
                    digest.reset();
                    digest.update(hash);
                    digest.update(NULL_HASH); // Sibling hash (simplified - we use null)
                    hash = digest.digest();
                }

                // Prevent JIT from optimizing away the work
                lastStateCommitHash = Bytes.wrap(hash);
            } catch (Exception e) {
                // Ignore - simulation failure shouldn't break benchmark
            }
        }

        @Override
        public @NonNull <T> WritableSingletonState<T> getSingleton(int stateId) {
            return new WritableSingletonState<>() {
                @Override
                public @NonNull T get() {
                    // SIMULATE state read cost (VirtualMap.get() path traversal)
                    simulateStateRead();
                    return (T) blockStreamInfoRef.get();
                }

                @Override
                public void put(T value) {
                    BlockStreamInfo info = (BlockStreamInfo) value;
                    blockStreamInfoRef.set(info);
                    // Actual commit cost is simulated in commit() method
                }

                @Override
                public int getStateId() {
                    return stateId;
                }

                @Override
                public boolean isRead() {
                    return true;
                }

                @Override
                public boolean isModified() {
                    return false;
                }
            };
        }

        @Override
        public @NonNull <K, V> WritableKVState<K, V> get(int stateId) {
            throw new UnsupportedOperationException("KV states not supported");
        }

        @Override
        public @NonNull <T> WritableQueueState<T> getQueue(int stateId) {
            throw new UnsupportedOperationException("Queue states not supported");
        }

        @Override
        public boolean contains(int stateId) {
            return stateId == V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
        }

        @Override
        public @NonNull Set<Integer> stateIds() {
            return Set.of(V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID);
        }

        @Override
        public void commit() {
            // SIMULATE state commit cost (VirtualMap.put() + commit() path rehashing)
            simulateStateCommit();
        }
    }
}
