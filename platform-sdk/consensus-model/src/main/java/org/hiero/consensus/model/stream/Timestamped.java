// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.stream;

import java.time.Instant;

/** interface for any class that has a timestamp on each instance that should be serialized with it */
public interface Timestamped {
    /** @return the timestamp to serialize along with this object */
    Instant getTimestamp();
}
