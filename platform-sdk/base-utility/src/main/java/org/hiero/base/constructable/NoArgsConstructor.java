// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.constructable;

import java.util.function.Supplier;

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
