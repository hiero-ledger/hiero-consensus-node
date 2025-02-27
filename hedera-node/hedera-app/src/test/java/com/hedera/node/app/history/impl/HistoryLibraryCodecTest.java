// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.impl.HistoryLibraryCodecImpl.HISTORY_LIBRARY_CODEC;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.history.History;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HistoryLibraryCodecTest {
    @Test
    void nothingSupportedYet() {
        assertThrows(UnsupportedOperationException.class, () -> HISTORY_LIBRARY_CODEC.encodeHistory(History.DEFAULT));
        assertThrows(
                UnsupportedOperationException.class, () -> HISTORY_LIBRARY_CODEC.encodeAddressBook(Map.of(), Map.of()));
    }
}
