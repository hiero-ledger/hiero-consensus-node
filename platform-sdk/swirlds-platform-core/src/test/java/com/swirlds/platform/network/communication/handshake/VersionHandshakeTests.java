// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.handshake;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.utility.Pair;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.test.fixtures.sync.ConnectionFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VersionCompareHandshake}
 */
class VersionHandshakeTests {
    private Connection theirConnection;
    private Connection myConnection;

    private ProtocolRunnable protocolToleratingMismatch;
    private ProtocolRunnable protocolThrowingOnMismatch;

    private static void clearWriteFlush(@NonNull final Connection connection, @Nullable final SemanticVersion version)
            throws IOException {
        if (connection.getDis().available() > 0) {
            connection.getDis().readPbjRecord(SemanticVersion.PROTOBUF);
        }
        connection.getDos().writePbjRecord(version, SemanticVersion.PROTOBUF);
        connection.getDos().flush();
    }

    @BeforeEach
    void setup() throws IOException {
        final Pair<Connection, Connection> connections =
                ConnectionFactory.createLocalConnections(NodeId.of(0L), NodeId.of(1));
        myConnection = connections.left();
        theirConnection = connections.right();

        final SemanticVersion ourVersion = SemanticVersion.newBuilder().major(5).build();
        protocolToleratingMismatch = new VersionCompareHandshake(ourVersion, false);
        protocolThrowingOnMismatch = new VersionCompareHandshake(ourVersion, true);
    }

    @Test
    @DisplayName("They have the same software version as us")
    void sameVersion() throws IOException {
        SemanticVersion version = SemanticVersion.newBuilder().major(5).build();
        clearWriteFlush(theirConnection, version);
        assertDoesNotThrow(() -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, version);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("They have a different software version than us")
    void differentVersion() throws IOException {
        SemanticVersion version = SemanticVersion.newBuilder().major(6).build();
        clearWriteFlush(theirConnection, version);
        assertThrows(HandshakeException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, version);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }
}
