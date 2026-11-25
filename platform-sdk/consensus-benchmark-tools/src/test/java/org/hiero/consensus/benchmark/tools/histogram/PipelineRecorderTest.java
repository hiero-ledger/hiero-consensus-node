// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Checks which invocations of a {@link PipelineRecorder} are kept and which are discarded, that it counts the
 * allocation of every thread, and that it refuses calls out of order.
 */
class PipelineRecorderTest {

    /** Much more than the measured parts allocate otherwise, small enough not to trigger a garbage collection. */
    private static final int ALLOCATION = 1 << 20;

    /** Keeps the allocation in a measured part from being optimized away. */
    @SuppressWarnings("unused")
    private static byte[] sink;

    @Test
    void invocationsThatEndAreKept() {
        final PipelineRecorder recorder = new PipelineRecorder();
        measure(recorder, 1_000, 3_000);
        measure(recorder, 5_000);
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isEqualTo(3);
        assertThat(recorder.histogram().mean()).isEqualTo(3_000);
        assertThat(recorder.gcDiscardedInvocations()).isZero();
        assertThat(recorder.saturationDiscardedInvocations()).isZero();
    }

    @Test
    void aSaturatedInvocationIsDroppedAndCounted() {
        final PipelineRecorder recorder = new PipelineRecorder();
        measure(recorder, 1_000);
        measure(recorder, 9_000);
        recorder.discardSaturatedInvocation();
        measure(recorder, 3_000);
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isEqualTo(2);
        assertThat(recorder.histogram().max()).isEqualTo(3_000);
        assertThat(recorder.saturationDiscardedInvocations()).isEqualTo(1);
    }

    @Test
    void aSaturatedInvocationWithAGarbageCollectionCountsInBoth() {
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.startMeasurement();
        recorder.record(1_000);
        System.gc();
        recorder.stopMeasurement();
        recorder.discardSaturatedInvocation();
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isZero();
        assertThat(recorder.gcDiscardedInvocations()).isEqualTo(1);
        assertThat(recorder.saturationDiscardedInvocations()).isEqualTo(1);
    }

    @Test
    void allocationOnAnotherThreadIsCounted() throws InterruptedException {
        System.gc();
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.startMeasurement();
        final Thread pipelineThread = new Thread(() -> sink = new byte[ALLOCATION]);
        pipelineThread.start();
        pipelineThread.join();
        recorder.record(1_000);
        recorder.stopMeasurement();
        recorder.endIteration();

        assertThat(recorder.gcDiscardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isGreaterThanOrEqualTo(ALLOCATION);
    }

    @Test
    void theAllocationOfADiscardedInvocationIsNotCounted() {
        System.gc();
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.startMeasurement();
        sink = new byte[ALLOCATION];
        recorder.stopMeasurement();
        recorder.discardSaturatedInvocation();
        recorder.endIteration();

        assertThat(recorder.gcDiscardedInvocations()).isZero();
        assertThat(recorder.allocatedBytes()).isZero();
    }

    @Test
    void anInvocationStillRunningAtTheEndOfTheIterationIsNotKept() {
        final PipelineRecorder recorder = new PipelineRecorder();
        recorder.startMeasurement();
        recorder.record(1_000);
        recorder.endIteration();

        assertThat(recorder.histogram().totalCount()).isZero();
    }

    @Test
    void callsOutOfOrderThrow() {
        final PipelineRecorder recorder = new PipelineRecorder();
        assertThatThrownBy(() -> recorder.record(1_000)).hasMessageContaining("No measured part is running");
        assertThatThrownBy(recorder::stopMeasurement).hasMessageContaining("No measured part is running");
        assertThatThrownBy(recorder::discardSaturatedInvocation).hasMessageContaining("No invocation to discard");

        recorder.startMeasurement();
        assertThatThrownBy(recorder::startMeasurement).hasMessageContaining("A measured part is running");
        assertThatThrownBy(recorder::discardSaturatedInvocation).hasMessageContaining("No invocation to discard");

        recorder.stopMeasurement();
        assertThatThrownBy(() -> recorder.record(1_000)).hasMessageContaining("No measured part is running");
        recorder.discardSaturatedInvocation();
        assertThatThrownBy(recorder::discardSaturatedInvocation).hasMessageContaining("No invocation to discard");
    }

    /** Runs the measured part of an invocation that records the given latencies. */
    private static void measure(final PipelineRecorder recorder, final long... latencies) {
        recorder.startMeasurement();
        for (final long latency : latencies) {
            recorder.record(latency);
        }
        recorder.stopMeasurement();
    }
}
