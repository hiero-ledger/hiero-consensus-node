// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

/**
 * A utility to find a specific payload in the logs. This interface extends {@link AutoCloseable} to allow
 * for proper resource management. It is recommended to always close a {@code LogPayloadFinder} after usage.
 */
public interface LogPayloadFinder extends AutoCloseable {

    /**
     * Returns {@code true} if the payload was found.
     *
     * @return {@code true} if the payload was found, {@code false} otherwise
     */
    boolean found();

    /**
     * {@inheritDoc}
     */
    @Override
    void close();
}
