// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

/**
 * Overrides the {@link AutoCloseable#close()} method but removes the throws declaration
 */
public interface AutoCloseableNonThrowing extends AutoCloseable {
    @Override
    void close();
}
