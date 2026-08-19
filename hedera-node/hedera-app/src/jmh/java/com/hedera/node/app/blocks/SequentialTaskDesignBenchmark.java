// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;

import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.hiero.base.concurrent.AbstractTask;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares two ways of splitting work between {@code BlockStreamManagerImpl}'s {@code ParallelTask}
 * (fires immediately, runs concurrently per item) and {@code SequentialTask} (strictly chained, one
 * item at a time) stages:
 *
 * <ul>
 *   <li><b>currentDesign</b> mirrors production as of this writing: {@code SequentialTask.onExecute()}
 *       does both the leaf hash ({@code IncrementalStreamingHasher.addLeaf}, an O(item size) SHA-384
 *       digest) <i>and</i> the writer append, so the expensive hashing work is serialized across the
 *       whole block even though it has no data dependency on other items.
 *   <li><b>proposedDesign</b> moves the SHA-384 leaf digest into the parallel stage (computed
 *       concurrently per item, same as serialization already is) and leaves only the O(1) Merkle
 *       fold-up ({@code IncrementalStreamingHasher.addNodeByHash}) plus the writer append in the
 *       sequential stage — the only two things that actually require item order.
 * </ul>
 *
 * Both variants reuse the identical dependency-counting scheme {@code BlockStreamManagerTask} uses
 * (an initial {@code send()}, a hand-off from the parallel stage, and a chain-link from the next
 * task), and both pay for a simulated writer append (a plain {@code arraycopy} into a preallocated
 * buffer, no real disk I/O — consistent with {@code NoOpBlockItemWriter} used elsewhere in this
 * benchmark suite) so the only difference measured is where the SHA-384 work happens.
 *
 * The benchmark method itself accumulates {@code itemCount} items (mirroring items arriving
 * throughout a block period via non-blocking {@code addItem} calls) and then joins the chain
 * (mirroring the {@code worker.sync()} calls in {@code endRoundInternal} that block the handle
 * thread at block close) — so the measured time already includes the "wait for all serialization
 * at the end" cost the two designs are trying to shrink.
 *
 * {@code itemCount * itemSizeBytes} sets the total raw block size. {@code sizing} is {@code
 * "<itemCount>,<itemSizeBytes>"} rather than two independent {@code @Param} fields so the matrix below can be
 * curated (varying item count and item size independently at matched total block sizes) instead of exploding
 * into every combination.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SequentialTaskDesignBenchmark {

    @Param({
        "2000,200", // 400KB - small block
        "20000,200", // 4MB - baseline block
        "100000,200", // 20MB - many small items (stresses per-item task-scheduling overhead)
        "25000,2000", // 50MB - medium items
        "2500,20000", // 50MB - same total bytes as above, fewer/larger items
        "500,100000" // 50MB - same total bytes again, very few/very large items
    })
    private String sizing;

    private int itemCount;
    private int itemSizeBytes;

    private ForkJoinPool pool;
    private byte[][] items;

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"SequentialTaskDesignBenchmark", "-v", "EXTRA"});
    }

    @Setup(Level.Trial)
    public void setup() {
        final var parts = sizing.split(",");
        itemCount = Integer.parseInt(parts[0]);
        itemSizeBytes = Integer.parseInt(parts[1]);

        pool = new ForkJoinPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

        // Sanity-check the redesign before measuring anything: hashing leaves in the parallel stage
        // must produce the exact same Merkle root as hashing them in the sequential stage, since this
        // root is what's used to request/verify the block proof.
        verifyIdenticalRootHash();

        final var random = new Random(42);
        items = new byte[itemCount][];
        for (int i = 0; i < itemCount; i++) {
            items[i] = new byte[itemSizeBytes];
            random.nextBytes(items[i]);
        }

        System.out.printf(
                ">>> itemCount=%d itemSizeBytes=%d totalBlockBytes=%.1fMB cores=%d%n",
                itemCount,
                itemSizeBytes,
                (itemCount * (long) itemSizeBytes) / (1024.0 * 1024.0),
                pool.getParallelism());
    }

    @TearDown(Level.Trial)
    public void teardown() {
        pool.shutdown();
    }

    /**
     * Current production split: SequentialTask does the SHA-384 leaf hash AND the writer append.
     * Disabled by default (no {@code @Benchmark}) so a plain {@code :app:jmh} run doesn't execute it —
     * uncomment {@code @Benchmark} to re-enable.
     */
    // @Benchmark
    public byte[] currentDesign_hashInSequentialStage() {
        return runChain(false);
    }

    /**
     * Proposed split: ParallelTask does the SHA-384 leaf hash; SequentialTask only folds + writes.
     * Disabled by default (no {@code @Benchmark}) so a plain {@code :app:jmh} run doesn't execute it —
     * uncomment {@code @Benchmark} to re-enable.
     */
    // @Benchmark
    public byte[] proposedDesign_hashInParallelStage() {
        return runChain(true);
    }

    private byte[] runChain(final boolean hashInParallelStage) {
        final var writeBuffer = new byte[itemCount * itemSizeBytes];
        final var chain = new TaskChain(pool, sha384DigestOrThrow(), writeBuffer, hashInParallelStage);
        // ACCUMULATE: items arrive non-blocking, exactly like writeItem()/addItem() during a round.
        for (final var item : items) {
            chain.addItem(item);
        }
        // WAIT: exactly like endRoundInternal's worker.sync() at block close.
        chain.sync();
        return chain.rootHash();
    }

    private void verifyIdenticalRootHash() {
        final var random = new Random(7);
        final var sample = new byte[64][];
        for (int i = 0; i < sample.length; i++) {
            sample[i] = new byte[37];
            random.nextBytes(sample[i]);
        }
        final var pool = new ForkJoinPool(4);
        try {
            final var sequentialHash = new TaskChain(pool, sha384DigestOrThrow(), new byte[64 * 37], false);
            final var parallelHash = new TaskChain(pool, sha384DigestOrThrow(), new byte[64 * 37], true);
            for (final var item : sample) {
                sequentialHash.addItem(item);
                parallelHash.addItem(item);
            }
            sequentialHash.sync();
            parallelHash.sync();
            final var expected = java.util.Arrays.toString(sequentialHash.rootHash());
            final var actual = java.util.Arrays.toString(parallelHash.rootHash());
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Proposed design's root hash diverged from current design's root hash");
            }
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Faithful reimplementation of {@code BlockStreamManagerImpl.BlockStreamManagerTask} /
     * {@code ParallelTask} / {@code SequentialTask}, parametrized so the SHA-384 leaf hash can be
     * computed in either stage.
     */
    private static final class TaskChain {
        private final ForkJoinPool pool;
        private final IncrementalStreamingHasher hasher;
        private final byte[] writeBuffer;
        private final boolean hashInParallelStage;
        private int writeOffset;
        private SequentialTask currentTask;
        private SequentialTask prevTask;

        TaskChain(
                final ForkJoinPool pool,
                final MessageDigest digest,
                final byte[] writeBuffer,
                final boolean hashInParallelStage) {
            this.pool = pool;
            this.hasher = new IncrementalStreamingHasher(digest, new ArrayList<>(), 0);
            this.writeBuffer = writeBuffer;
            this.hashInParallelStage = hashInParallelStage;
            this.currentTask = new SequentialTask();
            this.currentTask.send();
        }

        void addItem(final byte[] item) {
            new ParallelTask(item, currentTask).send();
            final var next = new SequentialTask();
            currentTask.send(next);
            prevTask = currentTask;
            currentTask = next;
        }

        void sync() {
            if (prevTask != null) {
                prevTask.join();
            }
        }

        byte[] rootHash() {
            return hasher.computeRootHash();
        }

        private final class ParallelTask extends AbstractTask {
            private final byte[] item;
            private final SequentialTask out;

            ParallelTask(final byte[] item, final SequentialTask out) {
                super(pool, 1);
                this.item = item;
                this.out = out;
            }

            @Override
            protected boolean onExecute() {
                final byte[] precomputedHash = hashInParallelStage ? BlockImplUtils.hashLeaf(item) : null;
                out.deliver(item, precomputedHash);
                return true;
            }
        }

        private final class SequentialTask extends AbstractTask {
            private SequentialTask next;
            private byte[] item;
            private byte[] precomputedHash;

            SequentialTask() {
                super(pool, 3);
            }

            void deliver(final byte[] item, final byte[] precomputedHash) {
                this.item = item;
                this.precomputedHash = precomputedHash;
                send();
            }

            void send(final SequentialTask next) {
                this.next = next;
                send();
            }

            @Override
            protected boolean onExecute() {
                final byte[] hash = hashInParallelStage ? precomputedHash : BlockImplUtils.hashLeaf(item);
                hasher.addNodeByHash(hash);

                // Simulate the writer's buffered append (no real disk I/O, matching NoOpBlockItemWriter).
                System.arraycopy(item, 0, writeBuffer, writeOffset, item.length);
                writeOffset += item.length;

                if (next != null) {
                    next.send();
                }
                return true;
            }
        }
    }
}
