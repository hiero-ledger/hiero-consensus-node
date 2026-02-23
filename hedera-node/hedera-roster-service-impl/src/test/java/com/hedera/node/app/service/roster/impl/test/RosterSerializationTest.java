// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl.test;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RosterSerializationTest {

    @Test
    void testDeserializeOldRosterStillValid() {

        final String TEST_RESOURCES_PATH = Paths.get("src", "test", "resources").toString();
        RosterEntry rosterEntry1 = new RosterEntry(1L, 100L, null, null);
        RosterEntry rosterEntry2 = new RosterEntry(2L, 50L, null, null);

        final byte[] gossipCaCertificate = "gossipCaCertificate".getBytes();

        // Create a new RosterEntry object without the tssEncryptionKey field
        RosterEntry rosterEntry = RosterEntry.newBuilder()
                .nodeId(1)
                .weight(10)
                .gossipCaCertificate(Bytes.wrap(gossipCaCertificate))
                .gossipEndpoint(ServiceEndpoint.newBuilder()
                        .domainName("domain.com")
                        .port(666)
                        .build())
                .build();

        Roster roster = new Roster(List.of(rosterEntry1, rosterEntry2, rosterEntry));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            try (FileInputStream fis = new FileInputStream(TEST_RESOURCES_PATH + "/old_roster.dat")) {
                ReadableSequentialData in2 = new ReadableStreamingData(fis);
                var deserializedRoster = Roster.PROTOBUF.parse(in2);
                Assertions.assertEquals(deserializedRoster, roster);

                // Verify the deserialized roster
                Assertions.assertEquals(
                        roster.rosterEntries().size(),
                        deserializedRoster.rosterEntries().size());

                // Verify the contents of the deserialized Roster - each field of the Roster and RosterEntry objects.
                for (int i = 0; i < roster.rosterEntries().size(); i++) {
                    RosterEntry expectedEntry = roster.rosterEntries().get(i);
                    RosterEntry actualEntry = deserializedRoster.rosterEntries().get(i);

                    Assertions.assertEquals(expectedEntry.nodeId(), actualEntry.nodeId());
                    Assertions.assertEquals(expectedEntry.weight(), actualEntry.weight());
                    Assertions.assertEquals(expectedEntry.gossipCaCertificate(), actualEntry.gossipCaCertificate());
                    Assertions.assertEquals(
                            expectedEntry.gossipEndpoint().size(),
                            actualEntry.gossipEndpoint().size());

                    for (int j = 0; j < expectedEntry.gossipEndpoint().size(); j++) {
                        ServiceEndpoint expectedEndpoint =
                                expectedEntry.gossipEndpoint().get(j);
                        ServiceEndpoint actualEndpoint =
                                actualEntry.gossipEndpoint().get(j);
                        Assertions.assertEquals(expectedEndpoint.domainName(), actualEndpoint.domainName());
                        Assertions.assertEquals(expectedEndpoint.port(), actualEndpoint.port());
                    }
                }
            }
        });
    }
}
