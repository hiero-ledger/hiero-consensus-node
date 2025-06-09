// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.output.internal;

import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;

/**
 * An output wire that will take data and forward it to its outputs.
 *
 * @param <IN>  the type of data passed to the forwarding method
 * @param <OUT> the type of data forwarded to things soldered to this wire
 */
public abstract class ForwardingOutputWire<IN, OUT> extends OutputWire<OUT> {

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this output wire
     * @param name                     the name of the output wire
     * @param uncaughtExceptionHandler handler for uncaught exceptions that occur while processing data on this output
     *                                 wire
     */
    protected ForwardingOutputWire(
            @NonNull final TraceableWiringModel model,
            final @NonNull String name,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler) {
        super(model, name, uncaughtExceptionHandler);
    }

    /**
     * Forward output data to any wires/consumers that are listening for it.
     * <p>
     * Although it will technically work, it is a violation of convention to directly put data into this output wire
     * except from within code being executed by the task scheduler that owns this output wire. Don't do it.
     *
     * @param data the output data to forward
     */
    public abstract void forward(@NonNull final IN data);
}
