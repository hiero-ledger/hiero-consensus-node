// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * An input that a {@link PipelineTimer} replays: something that happened at a time, such as a step of a recorded node
 * or an {@link Item}.
 */
public interface TimedInput {

    /**
     * Returns the time the input is due. Only the differences between the inputs' times matter: the timer replays the
     * first input right away and each later one as much later as its time is.
     *
     * @return the time the input is due
     */
    @NonNull
    Instant time();

    /**
     * Returns an instance of this input that no pass has touched. An input the pipeline can change returns a new copy
     * in its original state on every call; an immutable input returns itself. The timer calls it on every input before
     * every pass, outside the measured time.
     *
     * @return an untouched instance of this input
     */
    @NonNull
    TimedInput pristine();
}
