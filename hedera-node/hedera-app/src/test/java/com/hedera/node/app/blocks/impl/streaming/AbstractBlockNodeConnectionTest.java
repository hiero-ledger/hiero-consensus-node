// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.blocks.impl.streaming.AbstractBlockNodeConnection.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractBlockNodeConnectionTest extends BlockNodeCommunicationTestBase {

    private ConfigProvider configProvider;

    @BeforeEach
    void beforeEach() {
        configProvider = mock(ConfigProvider.class);
    }

    @Test
    void testBasics() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost.io", 8080, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.SERVER_STATUS, config);

        assertThat(connection.connectionId()).startsWith("SVC.");
        assertThat(connection.configuration()).isEqualTo(config);
        assertThat(connection.configProvider()).isEqualTo(configProvider);
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection.isActive()).isFalse();

        // create a new instance with the same type and config
        final AbstractBlockNodeConnection connection2 = newInstance(ConnectionType.SERVER_STATUS, config);
        assertThat(connection2).isNotEqualTo(connection); // different connection IDs mean different from #equals

        // toString should be the current state of the connection
        final String expectedToString = "[" + connection.connectionId() + "/localhost.io:8080/UNINITIALIZED]";
        assertThat(connection).hasToString(expectedToString);
    }

    @Test
    void testUpdateConnectionState_noExpectedState() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.SERVER_STATUS, config);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection).hasToString("[" + connection.connectionId() + "/localhost:8080/UNINITIALIZED]");

        connection.updateConnectionState(ConnectionState.READY);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);
        assertThat(connection).hasToString("[" + connection.connectionId() + "/localhost:8080/READY]");
    }

    @Test
    void testUpdateConnectionState_withExpectedState_downgrade() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.SERVER_STATUS, config);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection).hasToString("[" + connection.connectionId() + "/localhost:8080/UNINITIALIZED]");

        connection.updateConnectionState(ConnectionState.READY);

        assertThatThrownBy(() -> connection.updateConnectionState(ConnectionState.READY, ConnectionState.UNINITIALIZED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Attempted to downgrade state from READY to UNINITIALIZED");

        assertThat(connection.currentState()).isEqualTo(ConnectionState.READY);
    }

    @Test
    void testUpdateConnectionState_withExpectedState_invalidCurrentState() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.SERVER_STATUS, config);

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        // connection is UNINITIALIZED, so when we perform the update and expect the state to tbe READY, it will fail
        assertThat(connection.updateConnectionState(ConnectionState.READY, ConnectionState.ACTIVE))
                .isFalse();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
    }

    @Test
    void testUpdateConnectionState_withExpectedState_onActive() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        final AbstractBlockNodeConnection connection = spy(newInstance(ConnectionType.SERVER_STATUS, config));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        assertThat(connection.updateConnectionState(ConnectionState.UNINITIALIZED, ConnectionState.ACTIVE))
                .isTrue();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);

        // since the connection transitioned to ACTIVE, the on active transition handler should be called
        verify(connection).onActiveStateTransition();
    }

    @Test
    void testUpdateConnectionState_withExpectedState_onTerminal() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        final AbstractBlockNodeConnection connection = spy(newInstance(ConnectionType.SERVER_STATUS, config));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);

        assertThat(connection.updateConnectionState(ConnectionState.UNINITIALIZED, ConnectionState.CLOSING))
                .isTrue();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSING);

        // since the connection transitioned to CLOSING, the on active transition handler should be called
        verify(connection).onTerminalStateTransition();
    }

    private AbstractBlockNodeConnection newInstance(final ConnectionType type, final BlockNodeConfiguration config) {
        return new AbstractBlockNodeConnection(type, config, configProvider) {
            @Override
            void initialize() {
                // do nothing
            }

            @Override
            public void close() {
                // do nothing
            }
        };
    }
}
