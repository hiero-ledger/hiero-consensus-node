// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication.handshake;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.test.fixtures.sync.ConnectionFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HashCompareHandshake}
 */
class HashHandshakeTests {
    private Connection theirConnection;
    private Connection myConnection;

    private static void clearWriteFlush(
            @NonNull final Connection connection, @Nullable final SelfSerializable serializable) throws IOException {
        if (connection.getDis().available() > 0) {
            connection.getDis().readSerializable(false, Hash::new);
        }
        connection.getDos().writeSerializable(serializable, false);
        connection.getDos().flush();
    }

    @BeforeEach
    void setup() throws ConstructableRegistryException, IOException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(Hash.class, Hash::new));
        registry.registerConstructable(new ClassConstructorPair(SerializableLong.class, SerializableLong::new));

        final Pair<Connection, Connection> connections =
                ConnectionFactory.createLocalConnections(NodeId.of(0L), NodeId.of(1));
        myConnection = connections.left();
        theirConnection = connections.right();
    }

    @Test
    @DisplayName("They have the same hash as us")
    void sameHash() throws IOException {
        final Hash commonHash = RandomUtils.randomHash();

        final ProtocolRunnable protocolToleratingMismatch = new HashCompareHandshake(commonHash, false);
        final ProtocolRunnable protocolThrowingOnMismatch = new HashCompareHandshake(commonHash, true);

        clearWriteFlush(theirConnection, commonHash);
        assertDoesNotThrow(() -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, commonHash);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("They have a different hash than us")
    void differentHash() throws IOException {
        final Hash ourHash = RandomUtils.randomHash();
        final Hash theirHash = RandomUtils.randomHash();

        final ProtocolRunnable protocolToleratingMismatch = new HashCompareHandshake(ourHash, false);
        final ProtocolRunnable protocolThrowingOnMismatch = new HashCompareHandshake(ourHash, true);

        clearWriteFlush(theirConnection, theirHash);
        assertThrows(HandshakeException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, theirHash);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("Their hash is null, and ours isn't")
    void theirHashIsNull() throws IOException {
        final Hash ourHash = RandomUtils.randomHash();

        final ProtocolRunnable protocolToleratingMismatch = new HashCompareHandshake(ourHash, false);
        final ProtocolRunnable protocolThrowingOnMismatch = new HashCompareHandshake(ourHash, true);

        clearWriteFlush(theirConnection, null);
        assertThrows(HandshakeException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, null);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("Our hash is null, and theirs isn't")
    void ourHashIsNull() throws IOException {
        final Hash theirHash = RandomUtils.randomHash();

        final ProtocolRunnable protocolToleratingMismatch = new HashCompareHandshake(null, false);
        final ProtocolRunnable protocolThrowingOnMismatch = new HashCompareHandshake(null, true);

        clearWriteFlush(theirConnection, theirHash);
        assertThrows(HandshakeException.class, () -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, theirHash);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }

    @Test
    @DisplayName("Both hashes are null")
    void bothHashesAreNull() throws IOException {
        final ProtocolRunnable protocolToleratingMismatch = new HashCompareHandshake(null, false);
        final ProtocolRunnable protocolThrowingOnMismatch = new HashCompareHandshake(null, true);

        clearWriteFlush(theirConnection, null);
        assertDoesNotThrow(() -> protocolThrowingOnMismatch.runProtocol(myConnection));

        clearWriteFlush(theirConnection, null);
        assertDoesNotThrow(() -> protocolToleratingMismatch.runProtocol(myConnection));
    }
}
