// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.handshake;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.test.fixtures.sync.ConnectionFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.hiero.consensus.model.io.SelfSerializable;
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

    private static void clearWriteFlush(
            @NonNull final Connection connection, @Nullable final SelfSerializable serializable) throws IOException {
        if (connection.getDis().available() > 0) {
            connection.getDis().readSerializable();
        }
        connection.getDos().writeSerializable(serializable, true);
        connection.getDos().flush();
    }

    @BeforeEach
    void setup() throws ConstructableRegistryException, IOException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(BasicSoftwareVersion.class, BasicSoftwareVersion::new));
        registry.registerConstructable(new ClassConstructorPair(SerializableLong.class, SerializableLong::new));

        final Pair<Connection, Connection> connections =
                ConnectionFactory.createLocalConnections(NodeId.of(0L), NodeId.of(1));
        myConnection = connections.left();
        theirConnection = connections.right();

        final SoftwareVersion ourVersion = new BasicSoftwareVersion(5);
        protocolToleratingMismatch = new VersionCompareHandshake(ourVersion, false);
        protocolThrowingOnMismatch = new VersionCompareHandshake(ourVersion, true);
    }

    @Test
    @DisplayName("They have the same software version as us")
    void sameVersion() throws IOException {
        clearWriteFlush(theirConnection, new BasicSoftwareVersion(5));
        assertDoesNotThrow(() -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, new BasicSoftwareVersion(5));
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("They have a different software version than us")
    void differentVersion() throws IOException {
        clearWriteFlush(theirConnection, new BasicSoftwareVersion(6));
        assertThrows(HandshakeException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, new BasicSoftwareVersion(6));
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("Their software version is null")
    void nullVersion() throws IOException {
        clearWriteFlush(theirConnection, null);
        assertThrows(IOException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("Their software version is a different class")
    void differentClass() throws IOException {
        clearWriteFlush(theirConnection, new SerializableLong(5));
        assertThrows(IOException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));
    }
}
