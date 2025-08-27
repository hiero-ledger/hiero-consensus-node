// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.transformers;

import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Executes a transformation for an advanced transformer as created by
 * {@link OutputWire#buildAdvancedTransformer(AdvancedTransformation)}.
 *
 * @param <A> the original wire output type
 * @param <B> the output type of the transformer
 */
public interface AdvancedTransformation<A, B> {

    /**
     * Given data that comes off of the original output wire, this method transforms it before it is passed to each
     * input wire that is connected to this transformer. Called once per data element per listener.
     *
     * @param a a data element from the original output wire
     * @return the transformed data element, or null if the data should not be forwarded
     */
    @Nullable
    B transform(@NonNull A a);

    /**
     * Called on the original data element after it has been forwarded to all listeners. This method can do cleanup if
     * necessary. Doing nothing is perfectly ok if the use case does not require cleanup.
     *
     * @param a the original data element
     */
    void inputCleanup(@NonNull A a);

    /**
     * Called on the transformed data element if it is rejected by a listener. This is possible if offer soldering is
     * used and the destination declines to take the data.
     *
     * @param b the transformed data element
     */
    void outputCleanup(@NonNull B b);

    /**
     * Return the name of this transformer.
     *
     * @return the name of this transformer
     */
    @NonNull
    String getTransformerName();

    /**
     * Return the name of the input wire that feeds data into this transformer.
     *
     * @return the name of the input wire
     */
    @NonNull
    String getTransformerInputName();
}
