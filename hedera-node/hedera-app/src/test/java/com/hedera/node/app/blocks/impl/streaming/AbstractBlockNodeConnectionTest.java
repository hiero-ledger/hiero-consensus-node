// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import java.io.IOException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AbstractBlockNodeConnectionTest extends BlockNodeCommunicationTestBase {

    private ConfigProvider configProvider;

    private static LogCaptor logCaptor;

    @BeforeEach
    void beforeEach() {
        configProvider = mock(ConfigProvider.class);
        logCaptor = new LogCaptor(LogManager.getLogger(AbstractBlockNodeConnection.class));
    }

    @AfterEach
    void afterEach() {
        logCaptor.stopCapture();
    }

    @Test
    void testBasics() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost.io", 8080, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.SERVER_STATUS, config);
        final ConnectionId connectionId = connection.connectionId();

        assertThat(connectionId).isNotNull();
        assertThat(connectionId.type()).isEqualTo(ConnectionType.SERVER_STATUS);
        assertThat(connection.connectionId().toString()).startsWith("N0-SVC");
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

        assertThat(connection.createTimestamp()).isNotNull();
        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection.activeTimestamp()).isNull();

        // update the connection to active
        assertThat(connection.updateConnectionState(ConnectionState.UNINITIALIZED, ConnectionState.ACTIVE))
                .isTrue();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(connection.activeTimestamp()).isNotNull();

        // since the connection transitioned to ACTIVE, the on active transition handler should be called
        verify(connection).onActiveStateTransition();
    }

    @Test
    void testUpdateConnectionState_withExpectedState_onTerminal() {
        final BlockNodeConfiguration config = newBlockNodeConfig("localhost", 8080, 1);
        final AbstractBlockNodeConnection connection = spy(newInstance(ConnectionType.SERVER_STATUS, config));

        assertThat(connection.currentState()).isEqualTo(ConnectionState.UNINITIALIZED);
        assertThat(connection.closeTimestamp()).isNull();
        assertThat(connection.closeReason()).isNull();

        connection.setCloseReason(CloseReason.CONNECTION_ERROR);
        assertThat(connection.updateConnectionState(ConnectionState.UNINITIALIZED, ConnectionState.CLOSED))
                .isTrue();

        assertThat(connection.currentState()).isEqualTo(ConnectionState.CLOSED);
        assertThat(connection.closeTimestamp()).isNotNull();
        assertThat(connection.closeReason()).isEqualTo(CloseReason.CONNECTION_ERROR);

        // since the connection transitioned to CLOSED, the on active transition handler should be called
        verify(connection).onTerminalStateTransition();
    }

    @Test
    void testIpV4AddressAsInt_failure() {
        final BlockNodeConfiguration config = newBlockNodeConfig("192.168a.1b.1c", 80, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.BLOCK_STREAMING, config);

        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        List<String> logs = logCaptor.warnLogs();

        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst()).contains("Failed to resolve block node host");

        // subsequent attempts should still fail, but they should not log anything
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);

        logs = logCaptor.warnLogs();
        assertThat(logs).hasSize(1); // there should be just the 1 WARN from the initial attempt
    }

    @Test
    void testIpV4AddressAsInt() {
        final BlockNodeConfiguration config = newBlockNodeConfig("192.168.1.1", 80, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.BLOCK_STREAMING, config);

        final long expectedIpAsInt = 3232235777L;
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(expectedIpAsInt);

        List<String> logs = logCaptor.infoLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst()).contains("resolved to IP");

        // subsequent attempts should immediately return the cached value without re-resolving
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(expectedIpAsInt);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(expectedIpAsInt);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(expectedIpAsInt);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(expectedIpAsInt);

        logs = logCaptor.infoLogs();
        // there should only be the log from the first resolution and not later attempts
        assertThat(logs).hasSize(1);
    }

    @Test
    void testIpV4AddressAsInt_nonIpV4() {
        final BlockNodeConfiguration config = newBlockNodeConfig("[2606:4700:4700::1111]", 80, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.BLOCK_STREAMING, config);

        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);

        List<String> logs = logCaptor.warnLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst()).contains("Only IPv4 addresses are supported");

        // subsequent attempts should still fail, but they should not log anything
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);
        assertThat(connection.ipV4AddressAsInt()).isEqualTo(-1L);

        logs = logCaptor.warnLogs();
        assertThat(logs).hasSize(1); // there should be just the 1 WARN from the initial attempt
    }

    @Test
    void testFindGrpcException() {
        final GrpcException grpcException = new GrpcException(GrpcStatus.CANCELLED);
        final IOException ioException = new IOException("Foo", grpcException);
        final RuntimeException runtimeException = new RuntimeException("Bar", ioException);
        final BlockNodeConfiguration config = newBlockNodeConfig("192.168.1.1", 80, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.BLOCK_STREAMING, config);

        final GrpcException foundGrpcException = connection.findGrpcException(runtimeException);
        assertThat(foundGrpcException).isNotNull().isEqualTo(grpcException);
    }

    @Test
    void testFindGrpcException_notFound() {
        final BlockNodeConfiguration config = newBlockNodeConfig("192.168.1.1", 80, 1);
        final AbstractBlockNodeConnection connection = newInstance(ConnectionType.BLOCK_STREAMING, config);

        assertThat(connection.findGrpcException(new IOException("kaboom!"))).isNull();
    }

    private AbstractBlockNodeConnection newInstance(final ConnectionType type, final BlockNodeConfiguration config) {
        return new AbstractBlockNodeConnection(type, config, configProvider, 0L) {
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
