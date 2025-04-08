// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.time;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

public interface TimeTickReceiver {

    void tick(@NonNull final Instant now);
}
