// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.MAP_DELETE;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.MAP_UPDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KVStateChangeListenerTest {
    private static final int STATE_ID = 1;
    private static final AccountID KEY = AccountID.newBuilder().accountNum(1234).build();
    private static final Account VALUE = Account.newBuilder().accountId(KEY).build();
    private KVStateChangeListener listener;

    @BeforeEach
    void setUp() {
        listener = new KVStateChangeListener();
    }

    @Test
    void testGetStateChanges() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);

        List<StateChange> stateChanges = listener.getStateChanges();
        assertEquals(1, stateChanges.size());
    }

    @Test
    void testResetStateChanges() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);
        listener.reset();

        List<StateChange> stateChanges = listener.getStateChanges();
        assertTrue(stateChanges.isEmpty());
    }

    @Test
    void testMapUpdateChange() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(MAP_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(KEY, stateChange.mapUpdate().key().accountIdKey());
        assertEquals(VALUE, stateChange.mapUpdate().value().accountValue());
    }

    @Test
    void testMapDeleteChange() {
        listener.mapDeleteChange(STATE_ID, KEY);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(MAP_DELETE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(KEY, stateChange.mapDelete().key().accountIdKey());
    }
}
