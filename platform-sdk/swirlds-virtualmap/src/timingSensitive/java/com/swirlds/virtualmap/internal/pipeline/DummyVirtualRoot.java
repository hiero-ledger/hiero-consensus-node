// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.pipeline;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

class DummyVirtualRoot<K extends VirtualKey, V extends VirtualValue> extends PartialMerkleLeaf
        implements VirtualRoot<K, V>, MerkleLeaf {

    private static final long CLASS_ID = 0x37cc269627e18eb6L;

    private boolean shouldBeFlushed;
    private boolean merged;
    private boolean flushed;
    private volatile boolean blocked; // while true, flushing or merging doesn't happen for that DummyVirtualRoot.
    private final CountDownLatch flushLatch;
    private final CountDownLatch mergeLatch;
    private boolean hashed;

    private DummyVirtualRoot<K, V> previous;
    private DummyVirtualRoot<K, V> next;

    private int copyIndex;

    private volatile long estimatedSize = 0;

    /**
     * If set, automatically cause a copy to be flushable based on copy index. Only applies to copies made
     * after this value is set.
     */
    private Predicate<Integer /* copy index */> shouldFlushPredicate;

    private final VirtualPipeline<K, V> pipeline;

    private boolean detached = false;

    private boolean crashOnFlush = false;
    private boolean shutdownHandlerCalled;

    /**
     * Used to provoke a race condition in the hashFlushMerge() method when a copy
     * is destroyed part of the way through the method's execution.
     */
    private volatile boolean releaseInIsDetached;

    private final VirtualMapStatistics statistics;

    public DummyVirtualRoot(final String label, VirtualMapConfig virtualMapConfig) {
        pipeline = new VirtualPipeline<>(virtualMapConfig, label);
        flushLatch = new CountDownLatch(1);
        mergeLatch = new CountDownLatch(1);
        statistics = new VirtualMapStatistics(label);

        releaseInIsDetached = false;

        // class is final, everything is initialized at this point in time
        pipeline.registerCopy(this);
    }

    /**
     * If set, automatically cause a copy to be flushable based on copy index. Only applies to copies made
     * after this value is set.
     */
    public void setShouldFlushPredicate(final Predicate<Integer /* copy index */> shouldFlushPredicate) {
        this.shouldFlushPredicate = shouldFlushPredicate;
    }

    public void setCrashOnFlush(final boolean b) {
        this.crashOnFlush = b;
    }

    protected DummyVirtualRoot(final DummyVirtualRoot<K, V> that) {
        this.pipeline = that.pipeline;
        flushLatch = new CountDownLatch(1);
        mergeLatch = new CountDownLatch(1);
        previous = that;
        that.next = this;
        copyIndex = that.copyIndex + 1;
        shouldFlushPredicate = that.shouldFlushPredicate;
        statistics = that.statistics;
        estimatedSize = that.estimatedSize;

        if (shouldFlushPredicate != null) {
            shouldBeFlushed = shouldFlushPredicate.test(copyIndex);
        }
    }

    /**
     * Get a reference to the pipeline.
     */
    public VirtualPipeline<K, V> getPipeline() {
        return pipeline;
    }

    /**
     * Pass all statistics to the registry.
     *
     * @param metrics
     * 		reference to the metrics system
     */
    public void registerMetrics(final Metrics metrics) {
        statistics.registerMetrics(metrics);
        pipeline.registerMetrics(metrics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return 1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DummyVirtualRoot<K, V> copy() {
        setImmutable(true);
        final DummyVirtualRoot<K, V> copy = new DummyVirtualRoot<>(this);
        pipeline.registerCopy(copy);
        return copy;
    }

    @Override
    public long getFastCopyVersion() {
        return copyIndex;
    }

    /**
     * Set the flush behavior of this node.
     */
    public void setShouldBeFlushed(final boolean shouldBeFlushed) {
        this.shouldBeFlushed = shouldBeFlushed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldBeFlushed() {
        if (shouldBeFlushed) {
            return true;
        }
        final long flushThreshold = VIRTUAL_MAP_CONFIG.copyFlushThreshold();
        return (flushThreshold > 0) && (estimatedSize() >= flushThreshold);
    }

    /**
     * Specify the immutability status of the node.  Since AbstractMerkleNode.setImmutable is a final method,
     * we must give this method a different name.
     *
     * @param immutable
     * 		if this node should be immutable
     */
    public void overrideImmutable(final boolean immutable) {
        super.setImmutable(immutable);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        if (flushed) {
            throw new IllegalStateException("copy is already flushed");
        }
        if (!shouldBeFlushed && (estimatedSize < VIRTUAL_MAP_CONFIG.copyFlushThreshold())) {
            throw new IllegalStateException("copy should not be flushed");
        }
        if (!hashed) {
            throw new IllegalStateException("should be hashed before a flush");
        }

        DummyVirtualRoot<K, V> target = this.previous;
        while (target != null) {
            if (!(target.isDestroyed() || target.isDetached())) {
                throw new IllegalStateException("all older copies should have been destroyed or detached");
            }
            if (!target.isHashed()) {
                throw new IllegalStateException("all older copies should have been hashed");
            }
            if (shouldBeFlushed(target)) {
                if (!target.flushed) {
                    throw new IllegalStateException("older copy should have been flushed");
                }
            } else {
                if (!target.merged) {
                    throw new IllegalStateException("older copy should have been merged");
                }
            }
            target = target.previous;
        }

        if (crashOnFlush) {
            throw new RuntimeException("Crashing on Flush (this is intentional)");
        }

        while (blocked) {
            try {
                MILLISECONDS.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }

        blocked = false;
        flushed = true;
        flushLatch.countDown();

        statistics.recordFlush(copyIndex); // Use copyIndex as flush duration
    }

    private static boolean shouldBeFlushed(DummyVirtualRoot<?, ?> copy) {
        final long copyFlushThreshold = VIRTUAL_MAP_CONFIG.copyFlushThreshold();
        return (copy.shouldBeFlushed()) || ((copyFlushThreshold > 0) && (copy.estimatedSize() >= copyFlushThreshold));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFlushed() {
        return flushed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilFlushed() throws InterruptedException {
        if (!shouldBeFlushed) {
            throw new IllegalStateException("this will block forever");
        }
        flushLatch.await();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void merge() {
        if (merged) {
            throw new IllegalStateException("this copy has already been merged");
        }
        if (shouldBeFlushed) {
            throw new IllegalStateException("this copy should never be merged");
        }
        if (!(isDestroyed() || isDetached())) {
            throw new IllegalStateException("only destroyed or detached copies should be merged");
        }
        if (!isImmutable()) {
            throw new IllegalStateException("only immutable copies should be merged");
        }
        if (!hashed) {
            throw new IllegalStateException("should be hashed before a merge");
        }
        if (next == null || !next.isImmutable() || !next.isHashed()) {
            throw new IllegalStateException("can only merge when the next copy is immutable and hashed");
        }

        while (blocked) {
            try {
                MILLISECONDS.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }

        next.estimatedSize += estimatedSize;

        blocked = false;
        merged = true;
        mergeLatch.countDown();

        statistics.recordMerge(copyIndex * 2); // Use copyIndex*2 as merge duration
    }

    /**
     * Wait until merged.
     */
    public void waitUntilMerged() throws InterruptedException {
        mergeLatch.await();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onShutdown(final boolean immediately) {
        shutdownHandlerCalled = true;
    }

    /** Gets whether the shutdown handler was called on this copy */
    public boolean isShutdownHandlerCalled() {
        return shutdownHandlerCalled;
    }

    /**
     * Set the blocking behavior of this VirtualNode.
     */
    public void setBlocked(final boolean blocked) {
        this.blocked = blocked;
    }

    /**
     * Check if the copy is (or will be) blocked (from either flushing or merging)
     */
    public boolean isBlocked() {
        return blocked;
    }

    /**
     * Check if the copy is merged.
     */
    public boolean isMerged() {
        return merged;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHashed() {
        return hashed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void computeHash() {
        if (hashed) {
            throw new IllegalStateException("this copy has already been hashed");
        }
        if (previous != null && !previous.hashed) {
            throw new IllegalStateException("previous should already be hashed");
        }
        hashed = true;
        statistics.recordHash(copyIndex + 1); // Use copyIndex+1 as hash duration
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RecordAccessor<K, V> detach() {
        this.detached = true;
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void snapshot(final Path destination) {
        this.detached = true;
    }

    /**
     * If true, this copy will release itself when isDetached() is called.
     */
    public void setReleaseInIsDetached(final boolean releaseInIsDetached) {
        this.releaseInIsDetached = releaseInIsDetached;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDetached() {
        if (releaseInIsDetached) {
            releaseInIsDetached = false;
            release();
        }

        return detached;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRegisteredToPipeline(final VirtualPipeline pipeline) {
        return pipeline == this.pipeline;
    }

    @Override
    protected void destroyNode() {
        pipeline.destroyCopy(this);
    }

    /**
     * Get the unique ID for this copy.
     */
    public int getCopyIndex() {
        return copyIndex;
    }

    @Override
    public String toString() {
        return "copy " + copyIndex;
    }

    @Override
    public Hash getHash() {
        // Ensure we are properly hashed
        pipeline.hashCopy(this);

        return super.getHash();
    }

    @Override
    public long estimatedSize() {
        return estimatedSize;
    }

    public void setEstimatedSize(long value) {
        estimatedSize = value;
    }
}
