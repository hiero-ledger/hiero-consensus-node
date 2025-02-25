// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static com.hedera.node.app.info.UnavailableNetworkInfo.UNAVAILABLE_NETWORK_INFO;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UnavailableNetworkInfoTest {
    @Test
    void noMethodIsSupported() {
        assertThrows(UnsupportedOperationException.class, UNAVAILABLE_NETWORK_INFO::ledgerId);
        assertThrows(UnsupportedOperationException.class, UNAVAILABLE_NETWORK_INFO::selfNodeInfo);
        assertThrows(UnsupportedOperationException.class, UNAVAILABLE_NETWORK_INFO::addressBook);
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_NETWORK_INFO.nodeInfo(0));
        assertThrows(UnsupportedOperationException.class, () -> UNAVAILABLE_NETWORK_INFO.containsNode(0));
    }
}
