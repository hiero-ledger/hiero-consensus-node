// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.hedera.node.app.service.clpr.ClprChannelLifecycle;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClprChannelLifecycleTest {
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);

    @Test
    void noOpLifecycleIgnoresEveryNotification() {
        final var lifecycle = ClprChannelLifecycle.NO_OP;

        assertDoesNotThrow(() -> {
            lifecycle.onChannelActivated(CHANNEL_ID);
            lifecycle.onChannelObserved(CHANNEL_ID);
            lifecycle.onChannelClosed(CHANNEL_ID);
            lifecycle.seedPeerEndpoints(CHANNEL_ID, List.of());
            lifecycle.recordPeerObservedManifestVersion(CHANNEL_ID, 1L);
        });
    }
}
