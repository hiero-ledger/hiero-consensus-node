// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Checks which passes of a {@link PipelineRecorder} are stored and which are discarded, that it counts the allocation of
 * every thread, and that it refuses calls out of order.
 */
class PipelineRecorderTest {

    /** Much more than the passes allocate otherwise, small enough not to trigger a garbage collection. */
    private static final int ALLOCATION = 1 << 20;

    @Test
    void passesThatEndAreStored() {
        final PipelineRecorder recorder = new PipelineRecorder();
        pass(recorder, 1_000, 3_000);
        pass(recorder, 5_000);
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isEqualTo(3);
        assertThat(recorder.histogram().mean()).isEqualTo(3_000);
        assertThat(recorder.gcDiscardedInvocations()).isZero();
        assertThat(recorder.saturationDiscardedInvocations()).isZero();
    }

    @Test
    void aSaturatedPassIsDroppedAndCounted() {
        final PipelineRecorder recorder = new PipelineRecorder();
        pass(recorder, 1_000);
        pass(recorder, 9_000);
        recorder.discardSaturatedPass();
        pass(recorder, 3_000);
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isEqualTo(2);
        assertThat(recorder.histogram().max()).isEqualTo(3_000);
        assertThat(recorder.saturationDiscardedInvocations()).isEqualTo(1);
    }

    @Test
    void aSaturatedPassWithAGarbageCollectionCountsInBoth() {
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.preparePass();
        recorder.startPass();
        recorder.record(1_000);
        System.gc();
        recorder.endPass();
        recorder.discardSaturatedPass();
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isZero();
        assertThat(recorder.gcDiscardedInvocations()).isEqualTo(1);
        assertThat(recorder.saturationDiscardedInvocations()).isEqualTo(1);
    }

    @Test
    void allocationOnAnotherThreadIsCounted() throws Exception {
        final ExecutorService pipelineThread = Executors.newSingleThreadExecutor();
        try {
            System.gc();
            final PipelineRecorder recorder = new PipelineRecorder();
            recorder.preparePass();
            recorder.startPass();
            // A PipelineRecorder tracks allocations on all threads
            pipelineThread.submit(() -> SmallArrays.allocate(ALLOCATION)).get();
            recorder.record(1_000);
            recorder.endPass();
            recorder.endIteration();

            assertThat(recorder.gcDiscardedInvocations()).isZero();
            assertThat(recorder.allocatedBytes()).isGreaterThanOrEqualTo(ALLOCATION);
        } finally {
            pipelineThread.shutdown();
        }
    }

    @Test
    void theAllocationOfADiscardedPassIsNotStored() {
        System.gc();
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.preparePass();
        recorder.startPass();
        SmallArrays.allocate(ALLOCATION);
        recorder.endPass();
        recorder.discardSaturatedPass();
        recorder.endIteration();

        assertThat(recorder.gcDiscardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isZero();
    }

    @Test
    void aPassStillRunningAtTheEndOfTheIterationIsNotStored() {
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.preparePass();
        recorder.startPass();
        recorder.record(1_000);
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isZero();
    }

    @Test
    void callsOutOfOrderThrow() {
        final PipelineRecorder recorder = new PipelineRecorder();
        assertThatThrownBy(() -> recorder.record(1_000)).hasMessageContaining("No pass is running");
        assertThatThrownBy(recorder::endPass).hasMessageContaining("No pass is running");
        assertThatThrownBy(recorder::discardSaturatedPass).hasMessageContaining("No pass to discard");
        assertThatThrownBy(recorder::startPass).hasMessageContaining("No pass is prepared");

        recorder.preparePass();
        assertThatThrownBy(() -> recorder.record(1_000)).hasMessageContaining("No pass is running");
        recorder.startPass();
        assertThatThrownBy(recorder::startPass).hasMessageContaining("No pass is prepared");
        assertThatThrownBy(recorder::preparePass).hasMessageContaining("A pass is running");
        assertThatThrownBy(recorder::discardSaturatedPass).hasMessageContaining("No pass to discard");

        recorder.endPass();
        assertThatThrownBy(() -> recorder.record(1_000)).hasMessageContaining("No pass is running");
        assertThatThrownBy(recorder::startPass).hasMessageContaining("No pass is prepared");
        recorder.discardSaturatedPass();
        assertThatThrownBy(recorder::discardSaturatedPass).hasMessageContaining("No pass to discard");
    }

    @Test
    void preparingThePassStoresThePreviousOne() {
        final PipelineRecorder recorder = new PipelineRecorder();
        pass(recorder, 1_000);
        assertThat(recorder.histogram().totalCount()).isZero();

        recorder.preparePass();

        assertThat(recorder.histogram().totalCount()).isEqualTo(1);
    }

    /** Runs a pass that records the given latencies. */
    private static void pass(final PipelineRecorder recorder, final long... latencies) {
        recorder.preparePass();
        recorder.startPass();
        for (final long latency : latencies) {
            recorder.record(latency);
        }
        recorder.endPass();
    }
}
