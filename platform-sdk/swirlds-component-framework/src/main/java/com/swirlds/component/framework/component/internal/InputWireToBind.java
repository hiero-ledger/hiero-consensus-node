// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component.internal;

import com.swirlds.component.framework.wires.input.BindableInputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Contains information necessary to bind an input wire when we eventually get the implementation of the component.
 *
 * @param inputWire                               the input wire to bind
 * @param handlerWithReturn                       null if initially bound. If not initially bound, will be non-null
 *                                                if the method has a non-void return type
 * @param handlerWithoutReturn                    null if initially bound. If not initially bound, will be non-null
 *                                                if the method has a void return type
 * @param handlerWithoutParameter                 null if initially bound. If not initially bound, will be non-null
 *                                                if the method has no parameters
 * @param handlerWithoutReturnAndWithoutParameter null if initially bound. If not initially bound, will be non-null if
 *                                                the method has no parameters and a void return type
 * @param <COMPONENT_TYPE>                        the type of the component
 * @param <INPUT_TYPE>                            the input type of the input wire
 * @param <OUTPUT_TYPE>                           the output type of the component
 */
public record InputWireToBind<COMPONENT_TYPE, INPUT_TYPE, OUTPUT_TYPE>(
        @NonNull BindableInputWire<INPUT_TYPE, OUTPUT_TYPE> inputWire,
        @Nullable BiFunction<COMPONENT_TYPE, INPUT_TYPE, OUTPUT_TYPE> handlerWithReturn,
        @Nullable BiConsumer<COMPONENT_TYPE, INPUT_TYPE> handlerWithoutReturn,
        @Nullable Function<COMPONENT_TYPE, OUTPUT_TYPE> handlerWithoutParameter,
        @Nullable Consumer<COMPONENT_TYPE> handlerWithoutReturnAndWithoutParameter) {}
