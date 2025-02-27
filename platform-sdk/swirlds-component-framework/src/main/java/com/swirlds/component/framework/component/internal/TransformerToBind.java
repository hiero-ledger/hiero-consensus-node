// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component.internal;

import com.swirlds.component.framework.transformers.WireTransformer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiFunction;

/**
 * A transformer and the transformation to bind to a component.
 *
 * @param transformer      the transformer we eventually want to bind
 * @param transformation   the transformation method
 * @param <COMPONENT_TYPE> the type of the component
 * @param <INPUT_TYPE>     the input type of the transformer (equal to the output type of the base output wire)
 * @param <OUTPUT_TYPE>    the output type of the transformer
 */
public record TransformerToBind<COMPONENT_TYPE, INPUT_TYPE, OUTPUT_TYPE>(
        @NonNull WireTransformer<INPUT_TYPE, OUTPUT_TYPE> transformer,
        @NonNull BiFunction<COMPONENT_TYPE, INPUT_TYPE, OUTPUT_TYPE> transformation) {}
