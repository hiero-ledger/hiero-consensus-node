// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.cache;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.function.CheckedFunction;
import com.swirlds.base.state.MutabilityException;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafChunk;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.FastCopyable;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;

/**
 * A cache for virtual merkel trees.
 * <p>
 * At genesis, a virtual merkel tree has an empty {@link VirtualNodeCache} and no data on disk. As values
 * are added to the tree, corresponding {@link VirtualLeafBytes}s are added to the cache. When the round
 * completes, a fast-copy of the tree is made, along with a fast-copy of the cache. Any new changes to the
 * modifiable tree are done through the corresponding copy of the cache. The original tree and original
 * cache have <strong>IMMUTABLE</strong> leaf data. The original tree is then submitted to multiple hashing
 * threads. All hashed internal and leaf nodes are grouped into {@link VirtualHashChunk}s and put to the
 * node cache.
 * <p>
 * Eventually, there are multiple copies of the cache in memory. It may become necessary to merge two
 * caches together. The {@link #merge()} method is provided to merge a cache with the one-prior cache.
 * These merges are non-destructive, meaning it is OK to continue to query against a cache that has been merged.
 * <p>
 * At some point, the cache should be flushed to disk. This is done by calling the {@link #dirtyLeavesForFlush(long,
 * long)} and {@link #dirtyHashesForFlush(long)} methods and sending them to the code responsible for flushing. The
 * cache itself knows nothing about the data source or how to save data, it simply maintains a record of mutations
 * so that some other code can perform the flushing.
 * <p>
 * A cache is {@link FastCopyable}, so that each copy of the {@link VirtualMap} has a corresponding copy of the
 * {@link VirtualNodeCache}, at the same version. It keeps track of immutability of leaf data and internal
 * data separately, since the set of dirty leaves in a copy is added during {@code handleTransaction}
 * (which uses the most current copy), but the set of dirty internals during the {@code hashing} phase,
 * which is always one-copy removed from the current copy.
 * <p>
 * Caches have pointers to the next and previous caches in the chain of copies. This is necessary to support
 * merging of two caches (we could have added API to explicitly merge two caches together, but our use case
 * only supports merging a cache and its next-of-kin (the next copy in the chain), so we made this the only
 * supported case in the API).
 * <p>
 * The {@link VirtualNodeCache} was designed with our specific performance requirements in mind. We need to
 * maintain a chain of mutations over versions (the so-called map-of-lists approach to a fast-copy data
 * structure), yet we need to be able to get all mutations for a given version quickly, and we need to release
 * memory back to the garbage collector quickly. We also need to maintain the mutations for leaf data, for
 * which leaves occupied a given path, and for the internal node hashes at a given path.
 * <p>
 * To fulfill these design requirements, each "chain" of caches share three different indexes:
 * {@link #idToLeafChunkIndex}, {@link #keyToPathIndex}, and {@link #idToDirtyHashChunkIndex}.
 * Each of these is a map from either the leaf key or a path (long) to a custom linked list data structure. Each element
 * in the list is a {@link Mutation} with a reference to the data item (either a {@link VirtualHashChunk}
 * or a {@link VirtualLeafBytes}, depending on the list), and a reference to the next {@link Mutation}
 * in the list. In this way, given a leaf key or path (based on the index), you can get the linked list and
 * walk the links from mutation to mutation. The most recent mutation is first in the list, the oldest mutation
 * is last. There is at most one mutation per cache per entry in one of these indexes. If a leaf value is modified
 * twice in a single cache, only a single mutation exists recording the most recent change. There is no need to
 * keep track of multiple mutations per cache instance for the same leaf or internal node.
 * <p>
 * If there is one non-obvious gotcha that you *MUST* be aware of to use this class, it is that a record
 * (leaf or hash chunk) *MUST NOT BE REUSED ACROSS CACHE INSTANCES*. If I create a leaf record, and put it
 * into {@code cache0}, and then create a copy of {@code cache0} called {@code cache1}, I *MUST NOT* put
 * the same leaf record into {@code cache1} or modify the old leaf record, otherwise I will pollute
 * {@code cache0} with a leaf modified outside of the lifecycle for that cache. Instead, I must make a
 * fast copy of the leaf record and put *that* copy into {@code cache1}.
 */
@SuppressWarnings("rawtypes")
public final class VirtualNodeCache implements FastCopyable {

    private static final Logger logger = LogManager.getLogger(VirtualNodeCache.class);

    /**
     * A special {@link VirtualLeafBytes} that represents a deleted leaf. At times, the {@link VirtualMap}
     * will ask the cache for a leaf either by key or path. At such times, if we determine by looking at
     * the mutation that the leaf has been deleted, we will return this singleton instance.
     */
    public static final VirtualLeafBytes<?> DELETED_LEAF_RECORD = new VirtualLeafBytes<>(-1, Bytes.EMPTY, null, null);

    /**
     * Thread pool used to asynchronously clean up the indexes on cache release.
     */
    private final Executor cleaningPool;

    /**
     * The fast-copyable version of the cache. This version number is auto-incrementing and set
     * at construction time and cannot be changed, unless the cache is created through deserialization,
     * in which case it is set during deserialization and not changed thereafter.
     */
    private final AtomicLong fastCopyVersion = new AtomicLong(0L);

    // Pointers to the next and previous versions of VirtualNodeCache. When released,
    // the pointers are fixed so that next and prev point to each other instead of this
    // instance. The only time that these can be changed is during merge or release.
    // We only use these references during merging, otherwise we wouldn't even need them...

    /**
     * A reference to the next (older) version in the chain of copies. The reference is null
     * if this is the last copy in the chain.
     */
    private final AtomicReference<VirtualNodeCache> next = new AtomicReference<>();

    /**
     * A reference to the previous (newer) version in the chain of copies. The reference is
     * null if this is the first copy in the chain. This is needed to support merging.
     */
    private final AtomicReference<VirtualNodeCache> prev = new AtomicReference<>();

    private final int leafChunkSize;

    private final CheckedFunction<Long, VirtualLeafChunk, IOException> leafChunkLoader;

    /**
     * Height (number of ranks) in hash chunks. It must match chunk height at the data source level.
     */
    private final int hashChunkHeight;

    /**
     * Hash chunk loader, used to load chunks by chunk IDs. Usually, the current data source.
     */
    private final CheckedFunction<Long, VirtualHashChunk, IOException> hashChunkLoader;

    /**
     * A shared index of keys (K) to the linked lists that contain the values for that key
     * across different versions. The value is a reference to the
     * first {@link Mutation} in the list.
     * <p>
     * For example, the key "APPLE" might point to a {@link Mutation} that refers to the 3rd
     * copy, where "APPLE" was first modified. We simply follow the {@link Mutation} to that
     * {@link Mutation} and return the associated leaf value.
     * <p>
     * <strong>ONE PER CHAIN OF CACHES</strong>.
     */
    private final Map<Bytes, Mutation<Bytes, Long>> keyToPathIndex;

    /**
     * A shared index of paths to leaves, via {@link Mutation}s. Works the same as {@link #keyToPathIndex}.
     * <p>
     * <strong>ONE PER CHAIN OF CACHES</strong>.
     */
    private final Map<Long, Mutation<Long, VirtualLeafChunk>> idToLeafChunkIndex;

    /**
     * A shared index of chunk IDs to hash chunks, via {@link Mutation}s. Works the same as {@link #keyToPathIndex}.
     *
     * <p><strong>ONE PER CHAIN OF CACHES</strong>.
     */
    private final Map<Long, Mutation<Long, VirtualHashChunk>> idToDirtyHashChunkIndex;

    /**
     * Whether this instance is released. A released cache is often the last in the
     * chain, but may be any in the middle of the chain. However, you cannot
     * call {@link #release()} on any cache except <strong>the last</strong> one
     * in the chain. To release an intermediate instance, call {@link #merge()}.
     */
    private final AtomicBoolean released = new AtomicBoolean(false);

    /**
     * Whether the <strong>leaf</strong> indexes in this cache are immutable. We track
     * immutability of leaves and internal nodes separately, because leaves are only
     * modified on the head of the chain (the most recent version).
     */
    private final AtomicBoolean leafIndexesAreImmutable = new AtomicBoolean(false);

    /**
     * Whether the <strong>internal node</strong> indexes in this cache are immutable.
     * It turns out that we do not have an easy way to know when a cache is no longer
     * involved in hashing, unless the {@link VirtualMap} tells it. So we add a method,
     * {@link #seal()}, to the virtual map which is called at the end of hashing to
     * let us know that the cache should be immutable from this point forward. Note that
     * an immutable cache can still be merged and released.
     * <p>
     * Since during the {@code handleTransaction} phase there should be no hashing going on,
     * we start off with this being set to true, just to catch bugs or false assumptions.
     */
    private final AtomicBoolean hashesAreImmutable = new AtomicBoolean(true);

    /**
     * A set of all modifications to leaves that occurred in this version of the cache.
     * Note that this isn't actually a set, we have to sort and filter duplicates later.
     * <p>
     * <strong>ONE PER CACHE INSTANCE</strong>.
     */
    private volatile ConcurrentArray<Mutation<Long, VirtualLeafChunk>> dirtyLeaves = new ConcurrentArray<>();

    /**
     * A set of leaf path changes that occurred in this version of the cache. This is separate
     * from dirtyLeaves because dirtyLeaves captures the history of changes to leaves, while
     * this captures the history of which leaves lived at a given path.
     * Note that this isn't actually a set, we have to sort and filter duplicates later.
     * <p>
     * <strong>ONE PER CACHE INSTANCE</strong>.
     */
    private volatile ConcurrentArray<Mutation<Bytes, Long>> dirtyKeyPaths = new ConcurrentArray<>();

    /**
     * A set of all modifications to hash chunks that occurred in this version of the cache.
     * We use a list as an optimization, but it requires us to filter out mutations for the
     * same key or path from multiple versions.
     * Note that this isn't actually a set, we have to sort and filter duplicates later.
     *
     * <p><strong>ONE PER CACHE INSTANCE</strong>.
     */
    private volatile ConcurrentArray<Mutation<Long, VirtualHashChunk>> dirtyHashChunks = new ConcurrentArray<>();

    /**
     * Estimated size of all leaf records in dirtyLeaves. This size is calculated lazily during
     * the first call to {@link #getEstimatedSize()}. This method may only be called after
     * {@link #leafIndexesAreImmutable} is updated to true.
     */
    private final AtomicLong estimatedLeavesSizeInBytes = new AtomicLong(0);

    /**
     * Estimated size of all hashes in dirtyHashes. This size is updated on every hash operation
     * (put, delete).
     */
    private final AtomicLong estimatedHashesSizeInBytes = new AtomicLong(0);

    /**
     * Indicates if this virtual cache instance contains mutations from older cache versions
     * as a result of cache merge operation.
     */
    private final AtomicBoolean mergedCopy = new AtomicBoolean(false);

    /**
     * A shared lock that prevents two copies from being merged/released at the same time. For example,
     * one thread might be merging two caches while another thread is releasing the oldest copy. These
     * all happen completely in parallel, but we have some bookkeeping which should be done inside
     * a critical section.
     */
    private final ReentrantLock releaseLock;

    /**
     * lastReleased serves as a lock shared by a VirtualNodeCache family.
     * It provides needed synchronization between purge() and snapshot() (see #5838).
     * It didn't have to be atomic, just a reference to mutable long.
     * Purge() may be blocked by snapshot() until it finishes.
     * Snapshot(), however, should not be blocked for long by purge().
     * lastReleased ensures that snapshot() is aware of which version should not be included in
     * the snapshot.
     */
    private final AtomicLong lastReleased;

    /** Platform configuration for VirtualMap */
    @NonNull
    private final VirtualMapConfig virtualMapConfig;

    /**
     * Create a new VirtualNodeCache. The cache will be the first in the chain. It will get a
     * fastCopyVersion of zero, and create the shared data structures.
     *
     * @param virtualMapConfig platform configuration for VirtualMap
     * @param hashChunkHeight virtual hash chunk height
     * @param hashChunkLoader virtual hash chunk loader, must not be null
     */
    public VirtualNodeCache(
            final @NonNull VirtualMapConfig virtualMapConfig,
            final int hashChunkHeight,
            final @NonNull CheckedFunction<Long, VirtualHashChunk, IOException> hashChunkLoader,
            final int leafChunkSize,
            final @NonNull CheckedFunction<Long, VirtualLeafChunk, IOException> leafChunkLoader) {
        this(virtualMapConfig, hashChunkHeight, hashChunkLoader, leafChunkSize, leafChunkLoader, 0);
    }

    /**
     * Create a new VirtualNodeCache. The cache will be the first in the chain. It will get the
     * specified fastCopyVersion, and create the shared data structures.
     *
     * @param virtualMapConfig platform configuration for VirtualMap
     * @param hashChunkHeight virtual hash chunk height
     * @param hashChunkLoader virtual hash chunk loader, must not be null
     * @param fastCopyVersion  the version of this cache
     */
    public VirtualNodeCache(
            final @NonNull VirtualMapConfig virtualMapConfig,
            final int hashChunkHeight,
            final @NonNull CheckedFunction<Long, VirtualHashChunk, IOException> hashChunkLoader,
            final int leafChunkSize,
            final @NonNull CheckedFunction<Long, VirtualLeafChunk, IOException> leafChunkLoader,
            final long fastCopyVersion) {
        this.hashChunkHeight = hashChunkHeight;
        this.hashChunkLoader = requireNonNull(hashChunkLoader);
        this.leafChunkSize = leafChunkSize;
        this.leafChunkLoader = requireNonNull(leafChunkLoader);
        this.idToLeafChunkIndex = new ConcurrentHashMap<>();
        this.keyToPathIndex = new ConcurrentHashMap<>();
        this.idToDirtyHashChunkIndex = new ConcurrentHashMap<>();
        this.releaseLock = new ReentrantLock();
        this.lastReleased = new AtomicLong(-1L);
        this.fastCopyVersion.set(fastCopyVersion);
        this.virtualMapConfig = requireNonNull(virtualMapConfig);

        if (Boolean.getBoolean("syncCleaningPool")) {
            cleaningPool = Runnable::run;
        } else {
            final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    virtualMapConfig.getNumCleanerThreads(),
                    virtualMapConfig.getNumCleanerThreads(),
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadConfiguration(getStaticThreadManager())
                            .setThreadGroup(new ThreadGroup("virtual-cache-cleaners"))
                            .setComponent("virtual-map")
                            .setThreadName("cache-cleaner")
                            .setExceptionHandler((_, ex) -> logger.error(
                                    EXCEPTION.getMarker(), "Failed to purge unneeded key/mutationList pairs", ex))
                            .buildFactory());
            pool.allowCoreThreadTimeOut(true);
            cleaningPool = pool;
        }
    }

    /**
     * Create a copy of the cache. The resulting copy will have a reference to the previous cache,
     * and the previous cache will have a reference to the copy. The copy will have a fastCopyVersion
     * that is one greater than the one it copied from. It will share some data structures. Critically,
     * it will modify the {@link #hashesAreImmutable} to be false so that the older copy
     * can be hashed.
     *
     * @param source
     * 		Cannot be null and must be the most recent version!
     */
    @SuppressWarnings("CopyConstructorMissesField")
    private VirtualNodeCache(final VirtualNodeCache source) {
        // Make sure this version is exactly 1 greater than source
        this.fastCopyVersion.set(source.fastCopyVersion.get() + 1);

        this.hashChunkHeight = source.hashChunkHeight;
        this.hashChunkLoader = source.hashChunkLoader;
        this.leafChunkSize = source.leafChunkSize;
        this.leafChunkLoader = source.leafChunkLoader;
        // Get a reference to the shared data structures
        this.idToLeafChunkIndex = source.idToLeafChunkIndex;
        this.keyToPathIndex = source.keyToPathIndex;
        this.idToDirtyHashChunkIndex = source.idToDirtyHashChunkIndex;
        this.releaseLock = source.releaseLock;
        this.lastReleased = source.lastReleased;
        this.virtualMapConfig = source.virtualMapConfig;
        this.cleaningPool = source.cleaningPool;

        // The source now has immutable leaves and mutable internals
        source.prepareForHashing();

        // Wire up the next & prev references
        this.next.set(source);
        source.prev.set(this);
    }

    /**
     * Create a new VirtualNodeCache with a provided cleaning pool.
     * Used by {@link #snapshot()} to create snapshot caches that inherit
     * the parent's pool instead of creating a new one.
     */
    private VirtualNodeCache(
            final @NonNull VirtualMapConfig virtualMapConfig,
            final int hashChunkHeight,
            final @NonNull CheckedFunction<Long, VirtualHashChunk, IOException> hashChunkLoader,
            final int leafChunkSize,
            final @NonNull CheckedFunction<Long, VirtualLeafChunk, IOException> leafChunkLoader,
            final long fastCopyVersion,
            final @NonNull Executor cleaningPool) {
        this.hashChunkHeight = hashChunkHeight;
        this.hashChunkLoader = requireNonNull(hashChunkLoader);
        this.leafChunkSize = leafChunkSize;
        this.leafChunkLoader = requireNonNull(leafChunkLoader);
        this.idToLeafChunkIndex = new ConcurrentHashMap<>();
        this.keyToPathIndex = new ConcurrentHashMap<>();
        this.idToDirtyHashChunkIndex = new ConcurrentHashMap<>();
        this.releaseLock = new ReentrantLock();
        this.lastReleased = new AtomicLong(-1L);
        this.fastCopyVersion.set(fastCopyVersion);
        this.virtualMapConfig = requireNonNull(virtualMapConfig);
        this.cleaningPool = requireNonNull(cleaningPool);
    }

    /**
     * {@inheritDoc}
     *
     * Only a single thread should call copy at a time, but other threads may {@link #release()} and {@link#merge()}
     * concurrent to this call on other nodes in the chain.
     */
    @SuppressWarnings("unchecked")
    @Override
    public VirtualNodeCache copy() {
        return new VirtualNodeCache(this);
    }

    /**
     * Makes the cache immutable for leaf changes, but mutable for internal node changes.
     * This method call is idempotent.
     */
    public void prepareForHashing() {
        this.leafIndexesAreImmutable.set(true);
        this.hashesAreImmutable.set(false);
        this.dirtyLeaves.seal();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        // We use this as the stand-in as it obeys the normal semantics. Technically there is no advantage to
        // having this class implement FastCopyable, other than declaration of intent.
        return this.leafIndexesAreImmutable.get();
    }

    /**
     * {@inheritDoc}
     *
     * May be called on one cache in the chain while another copy is being made. Do not call
     * release on two caches in the chain concurrently. Must only release the very oldest cache in the chain. See
     * {@link #merge()}.
     *
     * @throws IllegalStateException
     * 		if this is not the oldest cache in the chain
     */
    @Override
    public boolean release() {
        throwIfDestroyed();

        // Under normal conditions "seal()" would have been called already, but it is at least possible to
        // release something that hasn't been sealed. So we call "seal()", just to tidy things up.
        seal();

        synchronized (lastReleased) {
            lastReleased.set(fastCopyVersion.get());
        }

        // We lock across all merges and releases across all copies (releaseLock is shared with all copies)
        // to prevent issues with one thread releasing while another thread is merging (as might happen if
        // the archive thread wants to release and another thread wants to merge).
        releaseLock.lock();
        try {
            // We are very strict about this, or the semantics around the cache will break.
            if (next.get() != null) {
                throw new IllegalStateException("Cannot release an intermediate version, must release the oldest");
            }

            this.released.set(true);

            // Fix the next/prev pointer so this entire cache will get dropped. We don't have to clear
            // any of our per-instance stuff, because nobody will be holding a strong reference to the
            // cache anymore. Still, in the off chance that somebody does hold a reference, we might
            // as well be proactive about it.
            wirePrevAndNext();
        } finally {
            releaseLock.unlock();
        }

        // Fire off the cleaning threads to go and clear out data in the indexes that doesn't need
        // to be there anymore.
        purge(dirtyLeaves, idToLeafChunkIndex);
        purge(dirtyKeyPaths, keyToPathIndex);
        purge(dirtyHashChunks, idToDirtyHashChunkIndex);

        estimatedLeavesSizeInBytes.set(0);
        estimatedHashesSizeInBytes.set(0);

        dirtyLeaves = null;
        dirtyKeyPaths = null;
        dirtyHashChunks = null;

        if (logger.isTraceEnabled()) {
            logger.trace(VIRTUAL_MERKLE_STATS.getMarker(), "Released {}", fastCopyVersion);
        }

        return true;
    }

    @Override
    public boolean isDestroyed() {
        return this.released.get();
    }

    /**
     * Shutdown the cleaning pool.
     */
    public void shutdown() {
        if (cleaningPool instanceof ExecutorService service) {
            service.shutdown();
        }
    }

    /**
     * Merges this cache with the one that is just-newer.
     * This cache will be removed from the chain and become available for garbage collection. Both this
     * cache and the one it is being merged into <strong>must</strong> be sealed (full immutable).
     *
     * @throws IllegalStateException
     * 		if there is nothing to merge into, or if both this cache and the one
     * 		it is merging into are not sealed.
     */
    public void merge() {
        releaseLock.lock();
        try {
            // We only permit you to merge a cache if it is no longer being used for hashing.
            final VirtualNodeCache p = prev.get();
            if (p == null) {
                throw new IllegalStateException("Cannot merge with a null cache");
            } else if (!p.hashesAreImmutable.get() || !hashesAreImmutable.get()) {
                throw new IllegalStateException("You can only merge caches that are sealed");
            }

            // Merge my mutations into the previous (newer) cache's arrays.
            // This operation has a high probability of producing override mutations. That is, two mutations
            // for the same key/path but with different versions. Before returning to a caller a stream of
            // dirty leaves or dirty hashes, the stream must be sorted (which we had to do anyway) and
            // deduplicated. But it makes for a _VERY FAST_ merge operation.
            p.dirtyLeaves.merge(dirtyLeaves);
            p.dirtyKeyPaths.merge(dirtyKeyPaths);
            p.dirtyHashChunks.merge(dirtyHashChunks);
            // Estimated sizes include both mutations and concurrent array overheads
            p.estimatedLeavesSizeInBytes.addAndGet(estimatedLeavesSizeInBytes.get());
            p.estimatedHashesSizeInBytes.addAndGet(estimatedHashesSizeInBytes.get());
            p.mergedCopy.set(true);

            // Remove this cache from the chain and wire the prev and next caches together.
            // This will allow this cache to be garbage collected.
            wirePrevAndNext();
        } finally {
            releaseLock.unlock();

            if (logger.isTraceEnabled()) {
                logger.trace(
                        VIRTUAL_MERKLE_STATS.getMarker(),
                        "Merged version {}, {} dirty leaves, {} dirty hash chunks",
                        fastCopyVersion,
                        dirtyLeaves.size(),
                        dirtyHashChunks.size());
            }
        }
    }

    /**
     * Seals this cache, making it immutable. A sealed cache can still be merged with another sealed
     * cache.
     */
    public void seal() {
        leafIndexesAreImmutable.set(true);
        hashesAreImmutable.set(true);
        dirtyLeaves.seal();
        dirtyHashChunks.seal();
        dirtyKeyPaths.seal();
        // Update estimated size to include concurrent arrays storage overhead
        estimatedHashesSizeInBytes.addAndGet(dirtyHashChunks.estimatedStorageMemoryOverhead());
        estimatedLeavesSizeInBytes.addAndGet(
                dirtyLeaves.estimatedStorageMemoryOverhead() + dirtyKeyPaths.estimatedStorageMemoryOverhead());
//        logger.info(VIRTUAL_MERKLE_STATS.getMarker(), "Cache seal v{} leaves={} size={}", getFastCopyVersion(), dirtyLeaves.size(), estimatedLeavesSizeInBytes.get());
        if (total != 0) {
            logger.info(VIRTUAL_MERKLE_STATS.getMarker(), "Cache seal v{} hits/total: {}", getFastCopyVersion(),
                    (double) hits / total);
        }
    }

    // --------------------------------------------------------------------------------------------
    // API for caching leaves.
    //
    // The mutation APIs can **ONLY** be called on the handleTransaction thread and only on the
    // most recent copy. The query APIs can be called from any thread.
    // --------------------------------------------------------------------------------------------

    // UNKNOWN_PATH, if not in the cache
    // INVALID_PATH, if in the cache and deleted
    // key path otherwise
    public long findKeyPath(@NonNull final Bytes key) {
        if (released.get()) {
            return Path.UNKNOWN_PATH;
        }
        final Mutation<Bytes, Long> mutation = lookup(keyToPathIndex.get(key));
        return mutation != null ? mutation.value : Path.UNKNOWN_PATH;
    }

    public void updateKeyPath(@NonNull final Bytes key, final long path) {
        throwIfLeafImmutable();
        requireNonNull(key);

        final long version = fastCopyVersion.get();
        keyToPathIndex.compute(key, (_, mutation) -> {
            if ((mutation == null) || (mutation.version != version)) {
                mutation = new Mutation<>(mutation, key, path, version);
                dirtyKeyPaths.add(mutation);
            } else {
                mutation.value = path;
            }
            return mutation;
        });
    }

    public void clearKeyPath(@NonNull final Bytes key) {
        updateKeyPath(key, Path.INVALID_PATH);
    }

    /**
     * Looks for a leaf record in this cache instance, and all older ones, based on the given {@code path}.
     * If the leaf record exists, it is returned. If the leaf was deleted, {@link #DELETED_LEAF_RECORD}
     * is returned. If there is no mutation record at all, null is returned, indicating a cache miss,
     * and that the caller should consult on-disk storage.
     * <p>
     * This method may be called concurrently from multiple threads, but <strong>MUST NOT</strong>
     * be called concurrently for the same path! It is NOT fully threadsafe!
     *
     * @param path
     * 		The path to use to lookup.
     * @return A {@link VirtualLeafBytes} if there is one in the cache (this instance or a previous
     * 		copy in the chain), or null if there is not one.
     * @throws ReferenceCountException
     * 		if the cache has already been released
     */
    public <V> VirtualLeafBytes<V> lookupLeaf(final long path) {
        assertValidLeafPath(path);

        // The only way to be released is to be in a condition where the data source has
        // the data that was once in this cache but was merged and is therefore now released.
        // So we can return null and know the caller can find the data in the data source.
        if (released.get()) {
            return null;
        }

        // Get the newest mutation that equals this fastCopyVersion. Note that the mutations
        // in pathToDirtyKeyIndex contain the *path* as the key, and a leaf record *key* as
        // the value. Thus, we look up a mutation first in the pathToDirtyKeyIndex, get the
        // leaf key, and then lookup based on that key
        final long leafChunkId = VirtualLeafChunk.pathToChunkId(path, leafChunkSize);
        final Mutation<Long, VirtualLeafChunk> mutation = lookup(idToLeafChunkIndex.get(leafChunkId));
        // If the mutation is null, the path is unknown, so return null
        if (mutation == null) {
            return null;
        }

        final VirtualLeafBytes<V> leaf = mutation.value.getLeaf(path);
        // TODO
//        assert leaf != null : "Leaf should not be deleted";
        return leaf;
    }

    private VirtualLeafChunk loadLeafChunk(final long leafChunkId) {
        try {
            return leafChunkLoader.apply(leafChunkId);
        } catch (final IOException e) {
            logger.error(VIRTUAL_MERKLE_STATS.getMarker(), "Failed to load leaf chunk id=" + leafChunkId, e);
            throw new UncheckedIOException(e);
        }
    }

    private VirtualLeafChunk ensureLeafChunk(final long leafChunkId) {
        assert leafChunkId >= 0;

        return idToLeafChunkIndex.compute(leafChunkId, (_, mutation) -> {
            final long version = fastCopyVersion.get();
            if (mutation == null) {
                VirtualLeafChunk leafChunk = loadLeafChunk(leafChunkId);
                if (leafChunk == null) {
                    leafChunk = new VirtualLeafChunk(leafChunkId, leafChunkSize);
                }
                mutation = new Mutation<>(mutation, leafChunkId, leafChunk, version);
//                logger.info(VIRTUAL_MERKLE_STATS.getMarker(), "Added new v={} id={}", getFastCopyVersion(), leafChunkId);
                dirtyLeaves.add(mutation);
                estimatedLeavesSizeInBytes.addAndGet(leafChunk.getEstimatedSizeInBytes());
            } else if (mutation.version != version) {
                final VirtualLeafChunk leafChunk = mutation.value.copy();
                mutation = new Mutation<>(mutation, leafChunkId, leafChunk, version);
//                logger.info(VIRTUAL_MERKLE_STATS.getMarker(), "Added copy v={} id={}", getFastCopyVersion(), leafChunkId);
                dirtyLeaves.add(mutation);
                estimatedLeavesSizeInBytes.addAndGet(leafChunk.getEstimatedSizeInBytes());
            }
            return mutation;
        }).value;
    }

    /**
     * Puts a leaf into the cache. Called whenever there is a <strong>new</strong> leaf or
     * whenever the <strong>value</strong> of the leaf has changed. Note that the caller is
     * responsible for ensuring that this leaf instance does not exist in any older copies
     * of caches. This is done by making fast-copies of the leaf as needed. This is the caller's
     * responsibility!
     *
     * <p>The caller must <strong>also</strong> call this each time the path of the node changes,
     * since we maintain a path-to-leaf mapping and need to be aware of the new path, even though
     * the value has not necessarily changed. This is necessary so that we record the leaf record
     * as dirty, since we need to include this leaf is the set that are involved in hashing, and
     * since we need to include this leaf in the set that are written to disk (since paths are
     * also written to disk).
     *
     * <p>This method should only be called from the <strong>HANDLE TRANSACTION THREAD</strong>.
     * It is NOT threadsafe!
     *
     * @param leaf
     * 		The leaf to put. Must not be null. Must have the correct key and path.
     * @throws NullPointerException
     * 		if the leaf is null
     * @throws MutabilityException
     * 		if the cache is immutable for leaf changes
     */
    public VirtualLeafBytes<?> putLeaf(@NonNull final VirtualLeafBytes leaf) {
        throwIfLeafImmutable();
        requireNonNull(leaf);

        final Bytes key = leaf.keyBytes();
        assert key != Bytes.EMPTY : "Keys cannot be empty";
        assert key.length() > 0 : "Keys cannot be empty";

        // Update the leaf
        final long leafChunkId = VirtualLeafChunk.pathToChunkId(leaf.path(), leafChunkSize);
        final VirtualLeafChunk leafChunk = ensureLeafChunk(leafChunkId);
        final VirtualLeafBytes<?> oldLeaf = leafChunk.setLeaf(leaf);
        estimatedLeavesSizeInBytes.addAndGet(leaf.getSizeInBytes());
        if (oldLeaf != null) {
            estimatedLeavesSizeInBytes.addAndGet(-oldLeaf.getSizeInBytes());
        }

        // Update the key path
        updateKeyPath(key, leaf.path());

        return oldLeaf;
    }

    int total = 0;
    int hits = 0;

    public VirtualLeafBytes<?> getLeafAndClearPath(final long path) {
        throwIfLeafImmutable();
        assertValidLeafPath(path);

        final long leafChunkId = VirtualLeafChunk.pathToChunkId(path, leafChunkSize);
        total++;
        Mutation<Long, VirtualLeafChunk> mutation = idToLeafChunkIndex.get(leafChunkId);
        if ((mutation != null) && (mutation.version == fastCopyVersion.get())) {
            hits++;
        }
        final VirtualLeafChunk leafChunk = ensureLeafChunk(leafChunkId);
        final VirtualLeafBytes oldLeaf = leafChunk.clearLeaf(path);
        assert oldLeaf != null;
        estimatedLeavesSizeInBytes.addAndGet(-oldLeaf.getSizeInBytes());
        return oldLeaf;
    }

    /**
     * Records that the given leaf was deleted in the cache. This creates a "delete" {@link Mutation} in the
     * linked list for this leaf. The leaf must have a correct leaf path and key. This call will
     * clear the leaf path for you.
     *
     * <p>This method should only be called from the <strong>HANDLE TRANSACTION THREAD</strong>.
     * It is NOT threadsafe!
     *
     * @param path
     * 		the leaf path to delete
     * @throws MutabilityException
     * 		if the cache is immutable for leaf changes
     */
    public VirtualLeafBytes deleteLeaf(final long path) {
        throwIfLeafImmutable();
        assertValidLeafPath(path);

        // Update the leaf
        final long leafChunkId = VirtualLeafChunk.pathToChunkId(path, leafChunkSize);
        final VirtualLeafChunk leafChunk = ensureLeafChunk(leafChunkId);
        final VirtualLeafBytes oldLeaf = leafChunk.clearLeaf(path);
        if (oldLeaf != null) {
            // Clear the key path
            clearKeyPath(oldLeaf.keyBytes());
            estimatedLeavesSizeInBytes.addAndGet(-oldLeaf.getSizeInBytes());
        }
        return oldLeaf;
    }

    /**
     * Returns a stream of dirty leaves from this cache instance to hash this virtual map copy. The stream
     * is sorted by paths.
     *
     * @param firstLeafPath
     * 		The first leaf path to include to the stream
     * @param lastLeafPath
     *      The last leaf path to include to the stream
     * @return
     *      A stream of dirty leaves for hashing
     */
    public Stream<VirtualLeafBytes> dirtyLeavesForHash(final long firstLeafPath, final long lastLeafPath) {
        if (mergedCopy.get()) {
            throw new IllegalStateException("Cannot get dirty leaves for hashing on a merged cache copy");
        }
        if (!dirtyLeaves.isImmutable()) {
            throw new MutabilityException("Cannot call on a cache that is still mutable for dirty leaves");
        }
        // This method is called on a cache copy, which is not a result of merging older
        // copies. There is no need to filter mutations here
        return dirtyLeaves.stream()
                .filter(mutation -> {
                    final long chunkFirstPath = mutation.value.getFirstPath();
                    final long chunkLastPath = mutation.value.getLastPath();
                    return chunkLastPath >= firstLeafPath && chunkFirstPath <= lastLeafPath;
                })
                .map(mutation -> mutation.value)
                .sorted(Comparator.comparingLong(VirtualLeafChunk::id))
                .flatMap(chunk -> {
                    final long chunkFirstPath = chunk.getFirstPath();
                    return Stream
                            .iterate(chunkFirstPath, i -> i < chunkFirstPath + leafChunkSize, p -> p + 1)
                            .filter(chunk::isDirtyLeaf)
                            .map(chunk::getLeaf);
                });
    }

    /**
     * Returns a stream of dirty leaves from this cache instance to flush this virtual map copy and all
     * previous copies merged into this one to disk.
     *
     * @param firstLeafPath
     * 		The first leaf path to include to the stream
     * @param lastLeafPath
     *      The last leaf path to include to the stream
     * @return
     *      A stream of dirty leaves for flushes
     */
    public Stream<VirtualLeafChunk> dirtyLeavesForFlush(final long firstLeafPath, final long lastLeafPath) {
        if (!dirtyLeaves.isImmutable()) {
            throw new MutabilityException("Cannot call on a cache that is still mutable for dirty leaves");
        }
        // Mark obsolete mutations to filter later
        filterMutations(dirtyLeaves);
        return dirtyLeaves.stream()
                .filter(mutation -> mutation.value.inRange(firstLeafPath, lastLeafPath))
                .filter(Mutation::notFiltered)
//                .filter(mutation -> !mutation.isDeleted())
                .map(mutation -> mutation.value)
                .sorted(Comparator.comparingLong(VirtualLeafChunk::id));
    }

    /**
     * Gets a stream of deleted leaves <strong>from this cache instance</strong>.
     * <p>
     * This method may be called concurrently from multiple threads (although in practice, this should never happen).
     *
     * @return A non-null stream of deleted leaves. May be empty. Will not contain duplicate records.
     * @throws MutabilityException
     * 		if called on a cache that still allows dirty leaves to be added
     */
    public Stream<VirtualLeafBytes> deletedLeaves() {
        if (!dirtyLeaves.isImmutable()) {
            throw new MutabilityException("Cannot call on a cache that is still mutable for dirty leaves");
        }
        // Mark obsolete mutations to filter later
        filterMutations(dirtyKeyPaths);
        return dirtyKeyPaths.stream()
                .filter(Mutation::notFiltered)
                .filter(mutation -> mutation.value == Path.INVALID_PATH)
                .map(mutation -> new VirtualLeafBytes(Path.INVALID_PATH, mutation.key, null));
    }

    // --------------------------------------------------------------------------------------------
    // API for caching node hashes.
    //
    // The mutation APIs should **ONLY** be called on the hashing threads, and can only be called
    // until the cache is sealed. The query APIs can be called from any thread.
    // --------------------------------------------------------------------------------------------

    /**
     * Put a hash chunk to the cache. This method is usually called by virtual hasher, when
     * a chunk is completely hashed. This method may be called from multiple threads even for
     * a single chunk.
     *
     * @param chunk the virtual hash chunk
     */
    public void putHashChunk(@NonNull final VirtualHashChunk chunk) {
        requireNonNull(chunk);
        throwIfInternalsImmutable();

        final long hashChunkId = chunk.getChunkId();
        idToDirtyHashChunkIndex.compute(hashChunkId, (_, mutation) -> {
            if ((mutation == null) || (mutation.version != fastCopyVersion.get())) {
                mutation = new Mutation<>(mutation, hashChunkId, chunk, fastCopyVersion.get());
                dirtyHashChunks.add(mutation);
                estimatedHashesSizeInBytes.addAndGet(
                        (long) chunk.getChunkSize() * Cryptography.DEFAULT_DIGEST_TYPE.digestLength());
            } else {
                assert mutation.notFiltered();
                // All hash chunks are of the same size, no need to update estimatedHashesSizeInBytes
                mutation.value = chunk;
            }
            return mutation;
        });
    }

    public VirtualHashChunk lookupHashChunkById(final long chunkId) {
        // The only way to be released is to be in a condition where the data source has
        // the data that was once in this cache but was merged and is therefore now released.
        // So we can return null and know the caller can find the data in the data source.
        if (released.get()) {
            return null;
        }

        final Mutation<Long, VirtualHashChunk> mutation = lookup(idToDirtyHashChunkIndex.get(chunkId));
        if (mutation == null) {
            return null;
        }

        return mutation.value;
    }

    /**
     * Gets a stream of dirty hashes <strong>from this cache instance</strong>. Deleted hashes are
     * not included in this stream. Must be called <strong>after</strong> the cache has been sealed.
     *
     * <p>This method may be called concurrently from multiple threads (although in practice, this should
     * never happen).
     *
     * @param lastLeafPath
     * 		The last leaf path at and above which no node results should be returned. It is possible,
     * 		through merging of multiple rounds, for the data to have data that is outside the expected range
     * 		for the {@link VirtualMap} of this cache. We need to provide the leaf boundaries to compensate for this.
     * @return A non-null stream of dirty hashes. May be empty. Will not contain duplicate records.
     * @throws MutabilityException if called on a non-sealed cache instance.
     */
    public Stream<VirtualHashChunk> dirtyHashesForFlush(final long lastLeafPath) {
        if (!dirtyHashChunks.isImmutable()) {
            throw new MutabilityException("Cannot get the dirty internal records for a non-sealed cache.");
        }
        // Mark obsolete mutations to filter later
        filterMutations(dirtyHashChunks);
        return dirtyHashChunks.stream()
                .filter(mutation -> {
                    final long hashChunkPath = mutation.value.path();
                    return Path.getLeftChildPath(hashChunkPath) <= lastLeafPath;
                })
                .filter(Mutation::notFiltered)
                .map(mutation -> mutation.value);
    }

    /**
     * Get fast copy version of the cache.
     *
     * @return Fast copy version
     */
    public long getFastCopyVersion() {
        return fastCopyVersion.get();
    }

    /**
     * Creates a new immutable snapshot of this cache.
     *
     * @return snapshot of the current {@link VirtualNodeCache}
     */
    public VirtualNodeCache snapshot() {
        synchronized (lastReleased) {
            final VirtualNodeCache newSnapshot = new VirtualNodeCache(
                    virtualMapConfig, hashChunkHeight, hashChunkLoader, leafChunkSize, leafChunkLoader, fastCopyVersion.get(), cleaningPool);
            setMapSnapshotAndArray(
                    this.idToDirtyHashChunkIndex, newSnapshot.idToDirtyHashChunkIndex, newSnapshot.dirtyHashChunks);
            setMapSnapshotAndArray(
                    this.idToLeafChunkIndex, newSnapshot.idToLeafChunkIndex, newSnapshot.dirtyLeaves);
            setMapSnapshotAndArray(this.keyToPathIndex, newSnapshot.keyToPathIndex, newSnapshot.dirtyKeyPaths);
            newSnapshot.fastCopyVersion.set(this.fastCopyVersion.get());
            newSnapshot.seal();
            return newSnapshot;
        }
    }

    // --------------------------------------------------------------------------------------------
    // Private helper methods.
    //
    // There is a lot of commonality between leaf and internal node caching logic. We try to
    // capture that (and more) here, to keep the rest of the code simple and sane.
    // --------------------------------------------------------------------------------------------

    /**
     * Wires together the {@code prev} and {@code next} caches.
     * <p>
     * Given a chain of caches:
     * <pre>
     *     +----------------+         +----------------+         +----------------+
     *     |    Cache v3    | #-----# |    Cache v2    | #-----# |    Cache v1    |
     *     +----------------+         +----------------+         +----------------+
     * </pre>
     * We should be able to remove any of the above caches. If "Cache v3" is removed, we
     * should see:
     * <pre>
     *     +----------------+         +----------------+
     *     |    Cache v2    | #-----# |    Cache v1    |
     *     +----------------+         +----------------+
     * </pre>
     * If "Cache v2" is removed instead, we should see:
     * <pre>
     *     +----------------+         +----------------+
     *     |    Cache v3    | #-----# |    Cache v1    |
     *     +----------------+         +----------------+
     * </pre>
     * And if "Cache v1" is removed, we should see:
     * <pre>
     *     +----------------+         +----------------+
     *     |    Cache v3    | #-----# |    Cache v2    |
     *     +----------------+         +----------------+
     * </pre>
     * <p>
     * This method IS NOT threadsafe! Control access via locks. It would be bad if a merge
     * and a release were to happen concurrently, or two merges happened concurrently,
     * among neighbors in the chain.
     */
    private void wirePrevAndNext() {
        final VirtualNodeCache n = this.next.get();
        final VirtualNodeCache p = this.prev.get();

        // If "p" is null, this is OK, we just set the "p" of next to null too.
        if (n != null) {
            n.prev.set(p);
        }

        // If "n" is null, that is OK, we just set the "n" of prev to null too.
        if (p != null) {
            p.next.set(n);
        }

        // Clear both of my references. I'm no longer part of the chain.
        this.next.set(null);
        this.prev.set(null);
    }

    private VirtualHashChunk loadHashChunk(final long chunkId) {
        try {
            return hashChunkLoader.apply(chunkId);
        } catch (final IOException e) {
            logger.error(VIRTUAL_MERKLE_STATS.getMarker(), "Failed to load hash chunk id=" + chunkId, e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Preload a hash chunk for the given path. This method is used by virtual hasher to
     * load partially clean/dirty chunks before updating any hashes in them. Completely
     * dirty chunks are not preloaded.
     *
     * <p>Important: this method must be thread-safe as it can be called for the same
     * chunk from multiple hashing tasks. Also, it must return the same VirtualHashChunk
     * object for all paths in the same chunk.
     *
     * <p>Implementation is pretty straightforward. If there is a recent mutation of the
     * given chunk in the cache, a copy of this mutation is returned, otherwise the chunk
     * preloader (which is usually the current data source) is used. In some cases the
     * preloader can't find the chunk either, this may happen when new leaves are added
     * to the virtual map. In this case, a new empty hash chunk is created and returned.
     */
    public VirtualHashChunk preloadHashChunk(final long path) {
        final long hashChunkId = VirtualHashChunk.chunkPathToChunkId(path, hashChunkHeight);
        return idToDirtyHashChunkIndex.compute(hashChunkId, (_, mutation) -> {
                    Mutation<Long, VirtualHashChunk> nextMutation = mutation;
                    while (nextMutation != null && nextMutation.version > fastCopyVersion.get()) {
                        nextMutation = nextMutation.next;
                    }
                    long sizeDelta = 0;
                    if (nextMutation == null) {
                        VirtualHashChunk hashChunk = loadHashChunk(hashChunkId);
                        if (hashChunk == null) {
                            final long hashChunkPath =
                                    VirtualHashChunk.chunkIdToChunkPath(hashChunkId, hashChunkHeight);
                            hashChunk = new VirtualHashChunk(hashChunkPath, hashChunkHeight);
                        }
                        nextMutation = new Mutation<>(null, hashChunkId, hashChunk, fastCopyVersion.get());
                        dirtyHashChunks.add(nextMutation);
                        sizeDelta += (long) hashChunk.getChunkSize() * Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
                    } else if (nextMutation.version != fastCopyVersion.get()) {
                        final VirtualHashChunk hashChunk = nextMutation.value.copy();
                        nextMutation = new Mutation<>(nextMutation, hashChunkId, hashChunk, fastCopyVersion.get());
                        dirtyHashChunks.add(nextMutation);
                        sizeDelta += (long) hashChunk.getChunkSize() * Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
                    } else {
                        assert nextMutation.notFiltered();
                    }
                    estimatedHashesSizeInBytes.addAndGet(sizeDelta);
                    return nextMutation;
                })
                .value;
    }

    /**
     * Given a mutation list, look up the most recent mutation to this version, but no newer than this
     * cache's version. This method is very fast. Newer mutations are closer to the head of the mutation list,
     * making lookup very fast for the most recent version (O(n)).
     *
     * @param mutation
     * 		The mutation list, can be null.
     * @param <K> The key type held by the mutation. Either a Key or a path.
     * @param <V>>
     * 		The value type held by the mutation. It will be either a Key, leaf record, or a hash.
     * @return null if the mutation could be found, or the mutation.
     */
    private <K, V> Mutation<K, V> lookup(Mutation<K, V> mutation) {
        // Walk the list of values until we find the best match for our version
        for (; ; ) {
            // If mutation is null, then there is nothing else to look for. We're done.
            if (mutation == null) {
                return null;
            }
            // We have found the best match
            if (mutation.version <= fastCopyVersion.get()) {
                return mutation;
            }
            // Look up the next mutation
            mutation = mutation.next;
        }
    }

    /**
     * Called by one of the purge threads to purge entries from the index that no longer have a referent
     * for the mutation list. This can be called concurrently.
     *
     * <p>BE AWARE: this method is called from the other NON-static method with providing the configuration.
     *
     * @param index
     * 		The index to look through for entries to purge
     * @param <K>
     * 		The key type used in the index
     * @param <V>
     * 		The value type referenced by the mutation list
     */
    private <K, V> void purge(final ConcurrentArray<Mutation<K, V>> array, final Map<K, Mutation<K, V>> index) {
        array.parallelTraverse(cleaningPool, (_, element) -> {
            // If a cache copy is released after flush, some mutations may be already marked as
            // filtered in dirtyLeavesForFlush() and dirtyHashesForFlush(). When a mutation is
            // filtered, it means there is a newer mutation for the same key in the same cache
            // copy. When this newer mutation is purged, it also takes care of the filtered
            // mutation, so there is no need to handle filtered mutations explicitly
            if (element.notFiltered()) {
                index.compute(element.key, (_, mutation) -> {
                    if ((mutation == null) || element.equals(mutation)) {
                        // Already removed for a more recent mutation
                        return null;
                    }
                    for (Mutation<K, V> m = mutation; m.next != null; m = m.next) {
                        if (element == m.next) {
                            m.next = null;
                            break;
                        }
                    }
                    return mutation;
                });
            }
        });
    }

    /**
     * Node cache contains lists of hash and leaf mutations for every cache version. When caches
     * are merged, the lists are merged, too. To make merges very fast, duplicates aren't removed
     * from the lists on merge. On flush / hash, no duplicates are allowed, so duplicated entries
     * need to be removed.
     *
     * <p>This method iterates over the given list of mutations and marks all obsolete mutations
     * as filtered. Later all marked mutations can be easily removed. A mutation is considered
     * obsolete, if there is a newer mutation for the same key.
     *
     * <p>BE AWARE: this method is called from the other NON-static method with providing the configuration.
     *
     * @param array the list of mutations to process
     * @param <K>
     * 		The key type used in the index
     * @param <V>
     * 		The value type referenced by the mutation list
     */
    private <K, V> void filterMutations(final ConcurrentArray<Mutation<K, V>> array) {
        final BiConsumer<Integer, Mutation<K, V>> action = (_, mutation) -> {
            // local variable is required because mutation.next can be changed by another thread to null
            // see https://github.com/hashgraph/hedera-services/issues/7046 for the context
            final Mutation<K, V> nextMutation = mutation.next;
            if (nextMutation != null) {
                nextMutation.setFiltered();
            }
        };
        try {
            array.parallelTraverse(cleaningPool, action).getAndRethrow();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Copies the mutations from {@code src} into {@code dst}
     * with the following constraints:
     * <ul>
     *     <li>Only one mutation per key is copied</li>
     *     <li>Only the latest mutation with version less than or equal to the
     *     {@code fastCopyVersion} is added</li>
     *     <li>Null mutations are not copied</li>
     * </ul>
     *
     * @param src Map that contains the original mutations
     * @param dst Map that acts as the destination of mutations
     * @param <K> Key type
     * @param <L> Value type
     */
    private <K, L> void setMapSnapshotAndArray(
            final Map<K, Mutation<K, L>> src,
            final Map<K, Mutation<K, L>> dst,
            final ConcurrentArray<Mutation<K, L>> array) {
        final long accepted = fastCopyVersion.get();
        final long rejected = lastReleased.get();
        for (final Map.Entry<K, Mutation<K, L>> entry : src.entrySet()) {
            Mutation<K, L> mutation = entry.getValue();

            while (mutation != null && mutation.version > accepted) {
                mutation = mutation.next;
            }

            if (mutation == null || mutation.version <= rejected) {
                continue;
            }

            dst.put(entry.getKey(), mutation);
            array.add(mutation);
            // Estimated size is not updated, which is hopefully fine
        }
    }

    /**
     * Get estimated size of this cache copy. The size includes all leaf records in dirtyLeaves,
     * all keys in dirtyLeafPaths, and all hashes in dirtyHashes.
     */
    public long getEstimatedSize() {
        return estimatedLeavesSizeInBytes.get() + estimatedHashesSizeInBytes.get();
    }

    /**
     * Helper method that throws a MutabilityException if the leaf is immutable.
     */
    private void throwIfLeafImmutable() {
        if (leafIndexesAreImmutable.get()) {
            throw new MutabilityException("This operation is not permitted on immutable leaves");
        }
    }

    /**
     * Helper method that throws MutabilityException if the internal is immutable
     */
    private void throwIfInternalsImmutable() {
        if (hashesAreImmutable.get()) {
            throw new MutabilityException("This operation is not permitted on immutable internals");
        }
    }

    private void assertValidLeafPath(final long path) {
        assert path != Path.UNKNOWN_PATH;
        assert path != Path.INVALID_PATH;
        assert path != Path.ROOT_PATH;
    }

    /**
     * A mutation. Mutations are linked together within the mutation list. Each mutation
     * has a pointer to the next oldest mutation in the list.
     * @param <K> The key type of data held by the mutation.
     * @param <V> The type of data held by the mutation.
     */
    private static final class Mutation<K, V> {
        private volatile Mutation<K, V> next;
        private final long version; // The version of the cache that owns this mutation
        private final K key;
        private volatile V value;
        private volatile byte flags = 0;

        // A bit in the flags field, which indicates whether this mutation should not be included
        // into resulting stream of dirty hashes / leaves
        private static final int FLAG_BIT_FILTERED = 1;

        Mutation(Mutation<K, V> next, K key, V value, long version) {
            this.next = next;
            this.key = key;
            this.value = value;
            this.version = version;
        }

        static boolean getFlag(final byte flags, final int bit) {
            return ((0xFF & flags) & (1 << bit)) != 0;
        }

        @SuppressWarnings("NonAtomicOperationOnVolatileField")
        void setFlag(int bit, boolean value) {
            if (value) {
                flags |= (1 << bit);
            } else {
                flags &= ~(1 << bit);
            }
        }

        boolean notFiltered() {
            return !getFlag(flags, FLAG_BIT_FILTERED);
        }

        void setFiltered() {
            setFlag(FLAG_BIT_FILTERED, true);
        }
    }

    /**
     * Given some cache, print out the contents of all the data structures and mark specially the set of mutations
     * that apply to this cache.
     *
     * @return A string representation of all the data structures of this cache.
     */
    @SuppressWarnings("rawtypes")
    public String toDebugString() {
        //noinspection StringBufferReplaceableByString
        final StringBuilder builder = new StringBuilder();
        builder.append("VirtualNodeCache ").append(this).append("\n");
        builder.append("===================================\n");
        builder.append(toDebugStringChain()).append("\n");
        //noinspection unchecked
        builder.append(toDebugStringIndex("idToLeafChunkIndex", (Map<Object, Mutation>) (Object) idToLeafChunkIndex))
                .append("\n");
        //noinspection unchecked
        builder.append(toDebugStringIndex("keyToPathIndex", (Map<Object, Mutation>) (Object) keyToPathIndex))
                .append("\n");
        //noinspection unchecked
        builder.append(toDebugStringIndex(
                        "idToDirtyHashChunkIndex", (Map<Object, Mutation>) (Object) idToDirtyHashChunkIndex))
                .append("\n");
        //noinspection unchecked
        builder.append(toDebugStringArray("dirtyLeaves", (ConcurrentArray<Mutation>) (Object) dirtyLeaves));
        //noinspection unchecked
        builder.append(toDebugStringArray("dirtyKeyPaths", (ConcurrentArray<Mutation>) (Object) dirtyKeyPaths));
        //noinspection unchecked
        builder.append(toDebugStringArray("dirtyHashChunks", (ConcurrentArray<Mutation>) (Object) dirtyHashChunks));
        return builder.toString();
    }

    private String toDebugStringChain() {
        final StringBuilder builder = new StringBuilder();
        builder.append("Copies:\n");
        builder.append("\t");

        VirtualNodeCache firstCache = this;
        VirtualNodeCache prevCache;
        while ((prevCache = firstCache.prev.get()) != null) {
            firstCache = prevCache;
        }

        while (firstCache != null) {
            builder.append("[")
                    .append(firstCache.fastCopyVersion.get())
                    .append(firstCache == this ? "*" : "")
                    .append("]->");
            firstCache = firstCache.next.get();
        }

        return builder.toString();
    }

    private String toDebugStringIndex(
            final String indexName, @SuppressWarnings("rawtypes") final Map<Object, Mutation> index) {
        final StringBuilder builder = new StringBuilder();
        builder.append(indexName).append(":\n");

        index.forEach((key, mutation) -> {
            builder.append("\t").append(key).append(":==> ");
            while (mutation != null) {
                builder.append("[")
                        .append(mutation.key)
                        .append(",")
                        .append(mutation.value)
                        .append(",")
                        .append("V")
                        .append(mutation.version)
                        .append(mutation.version == this.fastCopyVersion.get() ? "*" : "")
                        .append("]->");
                mutation = mutation.next;
            }
            builder.append("\n");
        });

        return builder.toString();
    }

    private String toDebugStringArray(
            final String name, @SuppressWarnings("rawtypes") final ConcurrentArray<Mutation> arr) {
        final StringBuilder builder = new StringBuilder();
        builder.append(name).append(":\n");

        final int size = arr.size();
        for (int i = 0; i < size; i++) {
            final var mutation = arr.get(i);
            builder.append("\t")
                    .append(mutation.key)
                    .append(",")
                    .append(mutation.value)
                    .append(",")
                    .append("V")
                    .append(mutation.version)
                    .append(mutation.version == this.fastCopyVersion.get() ? "*" : "")
                    .append("]\n");
        }

        return builder.toString();
    }

    /**
     * Returns the cleaning pool executor. Package-private, intended for testing only.
     *
     * @return the cleaning pool executor
     */
    Executor getCleaningPool() {
        return cleaningPool;
    }
}
