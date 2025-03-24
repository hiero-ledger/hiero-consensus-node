// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.Test;

public class PbjRecordHasherTest {
    @Test
    void testHash() {
        final PbjRecordHasher hasher = new PbjRecordHasher();

        final Hash hash = hasher.hash(Roster.DEFAULT, Roster.PROTOBUF);
        assertEquals(
                "38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b",
                hash.toString());

        final Hash anotherHash = hasher.hash(
                Roster.DEFAULT.copyBuilder().rosterEntries(RosterEntry.DEFAULT).build(), Roster.PROTOBUF);
        assertEquals(
                "5d693ce2c5d445194faee6054b4d8fe4a4adc1225cf0afc2ecd7866ea895a0093ea3037951b75ab7340b75699aa1db1d",
                anotherHash.toString());
    }
}
