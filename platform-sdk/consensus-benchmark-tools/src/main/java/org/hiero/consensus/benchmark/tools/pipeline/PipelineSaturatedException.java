package org.hiero.consensus.benchmark.tools.pipeline;

/**
 * Thrown when a pipeline was saturated during a test measured with {@link PipelineTimer}.
 */
public class PipelineSaturatedException extends RuntimeException {

    /**
     * Constructs a new {@link PipelineSaturatedException} with the specified detail message.
     *
     * @param message the detail message
     */
    public PipelineSaturatedException(String message) {
        super(message);
    }
}
