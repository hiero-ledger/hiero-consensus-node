// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with CLPR channels in state.
 */
public interface ReadableChannelStore {

    /**
     * Gets a channel by its channel identifier.
     *
     * @param channelId the 32-byte channel identifier
     * @return the channel, or null if not found
     */
    @Nullable
    ClprChannel getChannel(@NonNull Bytes channelId);

    /**
     * Gets the number of channels in the state.
     *
     * @return the number of channels
     */
    long sizeOfState();
}
