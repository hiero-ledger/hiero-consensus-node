// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.constructable;

import java.util.function.Supplier;
import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * A no arguments constructor for {@link RuntimeConstructable}, this is a replacement for the previous default
 * {@link Supplier}
 */
@FunctionalInterface
public interface NoArgsConstructor extends Supplier<RuntimeConstructable> {
    /**
     * @return a new instance of the {@link RuntimeConstructable}
     */
    @Override
    RuntimeConstructable get();
}
