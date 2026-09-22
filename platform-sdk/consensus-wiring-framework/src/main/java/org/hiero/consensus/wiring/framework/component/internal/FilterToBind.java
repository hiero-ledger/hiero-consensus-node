// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework.component.internal;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.BiFunction;
import org.hiero.consensus.wiring.framework.transformers.WireFilter;

/**
 * A filter and the predicate to bind.
 *
 * @param filter           the filter we eventually want to bind
 * @param predicate        the predicate method
 * @param <COMPONENT_TYPE> the type of the component
 * @param <OUTPUT_TYPE>    the input type
 */
public record FilterToBind<COMPONENT_TYPE, OUTPUT_TYPE>(
        @NonNull WireFilter<OUTPUT_TYPE> filter, @NonNull BiFunction<COMPONENT_TYPE, OUTPUT_TYPE, Boolean> predicate) {}
