// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Counts the garbage collections of the JVM, so that a recorder can discard a pass during which one ran. */
final class GarbageCollections {

    /** The MXBeans that count the JVM's garbage collections. */
    private static final List<GarbageCollectorMXBean> MX_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private GarbageCollections() {}

    /**
     * Returns the number of garbage collections the JVM has run so far.
     *
     * @return the number of garbage collections
     */
    static long count() {
        long count = 0;
        for (final GarbageCollectorMXBean bean : MX_BEANS) {
            count += Math.max(0, bean.getCollectionCount());
        }
        return count;
    }
}
