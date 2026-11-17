// SPDX-License-Identifier: Apache-2.0
/*
 * Derived from JCTools v4.0.6 (https://github.com/JCTools/JCTools),
 * artifact jctools-core, org.jctools.queues.IndexedQueueSizeUtil.
 * Modified: package relocated to org.hiero.base.concurrent.jctools.queues; removed IGNORE_PARITY_DIVISOR;
 * Javadoc converted to Markdown (///) with link targets pointing at the relocated classes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hiero.base.concurrent.jctools.queues;

import org.hiero.base.concurrent.jctools.util.InternalAPI;

/// A note to maintainers on index assumptions: in a single threaded world it would seem intuitive to assume:
/// ```
/// <code>producerIndex >= consumerIndex</code>
/// ```
/// As an invariant, but in a concurrent, long running settings all of the following need to be considered:
///
///     - `consumerIndex > producerIndex` : due to counter overflow (unlikey with longs, but easy to reason)
///     - `consumerIndex > producerIndex` : due to consumer FastFlow like implementation discovering the
///     element before the counter is updated.
///     - `producerIndex - consumerIndex < 0` : due to above.
///     - `producerIndex - consumerIndex > Integer.MAX_VALUE` : as linked buffers allow constructing queues
///     with more than `Integer.MAX_VALUE` elements.
@InternalAPI
public final class IndexedQueueSizeUtil {

    public static final int PLAIN_DIVISOR = 1;

    public static int size(IndexedQueue iq, int divisor) {
        /*
         * It is possible for a thread to be interrupted or reschedule between the reads of the producer and
         * consumer indices. It is also for the indices to be updated in a `weakly` visible way. It follows that
         * the size value needs to be sanitized to match a valid range.
         */
        long after = iq.lvConsumerIndex();
        long size;
        while (true) {
            final long before = after;
            // pIndex read is "sandwiched" between 2 cIndex reads
            final long currentProducerIndex = iq.lvProducerIndex();
            after = iq.lvConsumerIndex();
            if (before == after) {
                size = (currentProducerIndex - after) / divisor;
                break;
            }
        }
        return sanitizedSize(iq.capacity(), size);
    }

    public static int sanitizedSize(int capacity, long size) {
        // Concurrent updates to cIndex and pIndex may lag behind other progress enablers (e.g. FastFlow), so we need
        // to check bounds [0,capacity]
        if (size < 0) {
            return 0;
        }
        if (capacity != MessagePassingQueue.UNBOUNDED_CAPACITY && size > capacity) {
            return capacity;
        }
        // Integer overflow is possible for the unbounded indexed queues.
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) size;
    }

    public static boolean isEmpty(IndexedQueue iq) {
        // Order matters!
        // Loading consumer before producer allows for producer increments after consumer index is read.
        // This ensures this method is conservative in it's estimate. Note that as this is an MPMC there is
        // nothing we can do to make this an exact method.
        return (iq.lvConsumerIndex() >= iq.lvProducerIndex());
    }

    @InternalAPI
    public interface IndexedQueue {
        long lvConsumerIndex();

        long lvProducerIndex();

        int capacity();
    }
}
