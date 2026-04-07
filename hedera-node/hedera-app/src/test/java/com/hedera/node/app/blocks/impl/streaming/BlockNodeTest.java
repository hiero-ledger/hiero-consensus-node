// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNode.ConnectionHistory;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle configurationRefHandle;
    private static final VarHandle nodeCoolDownTimestampRefHandle;
    private static final VarHandle connectionHistoriesHandle;
    private static final VarHandle activeStreamingConnectionRefHandle;
    private static final VarHandle isTerminatedHandle;
    private static final VarHandle localActiveStreamingConnectionCountHandle;

    static {
        try {
            final Class<BlockNode> cls = BlockNode.class;
            final Lookup lookup = MethodHandles.privateLookupIn(cls, MethodHandles.lookup());

            configurationRefHandle = lookup.findVarHandle(cls, "configurationRef", AtomicReference.class);
            nodeCoolDownTimestampRefHandle =
                    lookup.findVarHandle(cls, "nodeCoolDownTimestampRef", AtomicReference.class);
            connectionHistoriesHandle = lookup.findVarHandle(cls, "connectionHistories", ConcurrentMap.class);
            activeStreamingConnectionRefHandle =
                    lookup.findVarHandle(cls, "activeStreamingConnectionRef", AtomicReference.class);
            isTerminatedHandle = lookup.findVarHandle(cls, "isTerminated", AtomicBoolean.class);
            localActiveStreamingConnectionCountHandle =
                    lookup.findVarHandle(cls, "localActiveStreamingConnectionCount", AtomicInteger.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long NODE_ID = 0L;
    private static final int COOL_DOWN_SECONDS = 15;

    private ConfigProvider configProvider;
    private BlockNodeConfiguration configuration;
    private AtomicInteger globalActiveStreamConnectionCount;
    private BlockNodeStats nodeStats;
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-02T18:00:00.000Z"), ZoneId.of("UTC"));

    private BlockNode node;

    @BeforeEach
    void beforeEach() {
        configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockNodeConnectionConfig bncConfig = mock(BlockNodeConnectionConfig.class);
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(BlockNodeConnectionConfig.class))
                .thenReturn(bncConfig);
        lenient().when(bncConfig.connectCoolDownSeconds()).thenReturn(COOL_DOWN_SECONDS);

        configuration = newBlockNodeConfig("localhost", 1234, 1);
        globalActiveStreamConnectionCount = new AtomicInteger();
        nodeStats = mock(BlockNodeStats.class);

        node = new BlockNode(configProvider, configuration, globalActiveStreamConnectionCount, nodeStats, clock);
    }

    @Test
    void testWriteInformation_nullStringBuilder() {
        assertThatThrownBy(() -> node.writeInformation(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("StringBuilder is required");
    }

    @Test
    void testWriteInformation_noHistory() {
        final StringBuilder sb = new StringBuilder();
        node.writeInformation(sb);

        final String expectedOutput = """
                Block Node (host: localhost, port: 1234, priority: 1, isStreamingCandidate: true, coolDownTimestamp: -, activeConnections: 0)
                  Connection History
                    <none>""";

        final String actual = sb.toString();
        assertThat(actual).isEqualTo(expectedOutput);
    }

    @Test
    void testWriteInformation_withHistory() {
        final ConnectionId conn1Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final ConnectionHistory conn1 = new ConnectionHistory(
                conn1Id,
                Instant.parse("2026-04-02T10:00:00.000Z"),
                Instant.parse("2026-04-02T10:00:01.000Z"),
                Instant.parse("2026-04-02T10:00:05.000Z"),
                CloseReason.CONFIG_UPDATE,
                10);
        final ConnectionId conn2Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2);
        final ConnectionHistory conn2 = new ConnectionHistory(
                conn2Id,
                Instant.parse("2026-04-02T11:00:00.000Z"),
                Instant.parse("2026-04-02T11:00:01.000Z"),
                Instant.parse("2026-04-02T11:00:05.000Z"),
                CloseReason.CONNECTION_STALLED,
                2);
        final ConnectionId conn3Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 3);
        final ConnectionHistory conn3 = new ConnectionHistory(
                conn3Id,
                Instant.parse("2026-04-02T12:00:00.000Z"),
                Instant.parse("2026-04-02T12:00:01.000Z"),
                null,
                null,
                null);

        connectionHistories().put(conn1Id, conn1);
        connectionHistories().put(conn2Id, conn2);
        connectionHistories().put(conn3Id, conn3);
        nodeCoolDownTimestampRef().set(Instant.parse("2026-04-02T18:00:15Z"));
        localActiveStreamingConnectionCount().set(1);

        final String expectedOutput = """
                Block Node (host: localhost, port: 1234, priority: 1, isStreamingCandidate: false, coolDownTimestamp: 2026-04-02T18:00:15Z, activeConnections: 1)
                  Connection History
                    N0-STR3 => created: 2026-04-02T12:00:00Z, activated: 2026-04-02T12:00:01Z, closed: -, duration: -, closeReason: -, blocksSent: -
                    N0-STR2 => created: 2026-04-02T11:00:00Z, activated: 2026-04-02T11:00:01Z, closed: 2026-04-02T11:00:05Z, duration: PT4S, closeReason: CONNECTION_STALLED, blocksSent: 2
                    N0-STR1 => created: 2026-04-02T10:00:00Z, activated: 2026-04-02T10:00:01Z, closed: 2026-04-02T10:00:05Z, duration: PT4S, closeReason: CONFIG_UPDATE, blocksSent: 10""";

        final StringBuilder sb = new StringBuilder();
        node.writeInformation(sb);
        final String actual = sb.toString();
        assertThat(actual).isEqualTo(expectedOutput);
    }

    @Test
    void testOnActive_nullConnection() {
        assertThatThrownBy(() -> node.onActive(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Connection is required");
    }

    @Test
    void testOnActive_openMultipleConnections() {
        final BlockNodeStreamingConnection existingConnection = mock(BlockNodeStreamingConnection.class);
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamConnectionCount.set(1);
        activeStreamingConnectionRef().set(existingConnection);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        when(newConnection.configuration()).thenReturn(configuration);
        when(newConnection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2));

        node.onActive(newConnection);

        assertThat(connectionHistories())
                .hasSize(1)
                .containsKey(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2));
        assertThat(globalActiveStreamConnectionCount).hasValue(2);
        assertThat(localActiveStreamingConnectionCount()).hasValue(2);
        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newConnection);

        verify(existingConnection).closeAtBlockBoundary(CloseReason.NEW_CONNECTION);
        verify(existingConnection).connectionId();
        verifyNoMoreInteractions(existingConnection);
    }

    @Test
    void testOnActive_maxHistoryEntries() {
        // add 5 entries to the history map so when the next onActive happens, the oldest will be removed
        final Instant now = Instant.now();
        final ConnectionId conn1Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final ConnectionHistory conn1History =
                new ConnectionHistory(conn1Id, null, null, now.minusSeconds(30), null, null);
        final ConnectionId conn2Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2);
        final ConnectionHistory conn2History =
                new ConnectionHistory(conn2Id, null, null, now.minusSeconds(25), null, null);
        final ConnectionId conn3Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 3);
        final ConnectionHistory conn3History =
                new ConnectionHistory(conn3Id, null, null, now.minusSeconds(20), null, null);
        final ConnectionId conn4Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 4);
        final ConnectionHistory conn4History =
                new ConnectionHistory(conn4Id, null, null, now.minusSeconds(15), null, null);
        final ConnectionId conn5Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 5);
        final ConnectionHistory conn5History =
                new ConnectionHistory(conn5Id, null, null, now.minusSeconds(10), null, null);

        connectionHistories().put(conn1Id, conn1History);
        connectionHistories().put(conn2Id, conn2History);
        connectionHistories().put(conn3Id, conn3History);
        connectionHistories().put(conn4Id, conn4History);
        connectionHistories().put(conn5Id, conn5History);

        final ConnectionId conn6Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 6);
        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        when(newConnection.createTimestamp()).thenReturn(now);
        when(newConnection.configuration()).thenReturn(configuration);
        when(newConnection.connectionId()).thenReturn(conn6Id);

        node.onActive(newConnection);

        assertThat(connectionHistories()).hasSize(5).containsOnlyKeys(conn2Id, conn3Id, conn4Id, conn5Id, conn6Id);
    }

    @Test
    void testOnActive_maxHistoryEntriesWithDelayedClose() {
        // add 5 entries to the history map but leave the oldest as unclosed, this should cause the second oldest
        // to be removed on the first iteration
        final Instant now = Instant.now();
        final ConnectionId conn1Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final ConnectionHistory conn1History = new ConnectionHistory(conn1Id, null, null, null, null, null);
        final ConnectionId conn2Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2);
        final ConnectionHistory conn2History =
                new ConnectionHistory(conn2Id, null, null, now.minusSeconds(25), null, null);
        final ConnectionId conn3Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 3);
        final ConnectionHistory conn3History =
                new ConnectionHistory(conn3Id, null, null, now.minusSeconds(20), null, null);
        final ConnectionId conn4Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 4);
        final ConnectionHistory conn4History =
                new ConnectionHistory(conn4Id, null, null, now.minusSeconds(15), null, null);
        final ConnectionId conn5Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 5);
        final ConnectionHistory conn5History =
                new ConnectionHistory(conn5Id, null, null, now.minusSeconds(10), null, null);

        connectionHistories().put(conn1Id, conn1History);
        connectionHistories().put(conn2Id, conn2History);
        connectionHistories().put(conn3Id, conn3History);
        connectionHistories().put(conn4Id, conn4History);
        connectionHistories().put(conn5Id, conn5History);

        // Current History: STR1 - STR2 - STR3 - STR4 - STR5

        final ConnectionId conn6Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 6);
        final BlockNodeStreamingConnection conn6 = mock(BlockNodeStreamingConnection.class);
        when(conn6.createTimestamp()).thenReturn(now);
        when(conn6.configuration()).thenReturn(configuration);
        when(conn6.connectionId()).thenReturn(conn6Id);

        node.onActive(conn6);

        // since Conn 1 didn't have a close timestamp, it will still exist in the map until it is closed
        assertThat(connectionHistories()).hasSize(5).containsOnlyKeys(conn1Id, conn3Id, conn4Id, conn5Id, conn6Id);

        // Current History: STR1 - STR3 - STR4 - STR5 - STR6
        // now close Conn 1 and add a new connection... now Conn 1 should be removed

        final BlockNodeStreamingConnection conn1 = mock(BlockNodeStreamingConnection.class);
        when(conn1.closeTimestamp()).thenReturn(now.minusSeconds(30));
        when(conn1.closeReason()).thenReturn(CloseReason.CONNECTION_ERROR);
        when(conn1.numberOfBlocksSent()).thenReturn(10);
        when(conn1.configuration()).thenReturn(configuration);
        when(conn1.connectionId()).thenReturn(conn1Id);

        node.onClose(conn1);

        final ConnectionId conn7Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 7);
        final BlockNodeStreamingConnection conn7 = mock(BlockNodeStreamingConnection.class);
        when(conn7.createTimestamp()).thenReturn(now);
        when(conn7.configuration()).thenReturn(configuration);
        when(conn7.connectionId()).thenReturn(conn7Id);

        node.onActive(conn7);

        // Current History: STR3 - STR4 - STR5 - STR6 - STR7
        // now the history map should contain connections connection 3-7
        assertThat(connectionHistories()).hasSize(5).containsOnlyKeys(conn3Id, conn4Id, conn5Id, conn6Id, conn7Id);
    }

    @Test
    void testOnClose_nullConnection() {
        assertThatThrownBy(() -> node.onClose(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Connection is required");
    }

    @Test
    void testOnClose_noHistory() {
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamConnectionCount.set(1);
        // don't create a history entry for the connection
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 134));

        node.onClose(connection);

        // since there is no matching history entry for the connection, the connection counters should not change
        assertThat(localActiveStreamingConnectionCount()).hasValue(1);
        assertThat(globalActiveStreamConnectionCount).hasValue(1);

        verify(connection).configuration();
        verify(connection, times(2)).connectionId();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_noCloseReason() {
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamConnectionCount.set(1);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 110);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connectionId);
        final Instant closeTimestamp = Instant.now();
        when(connection.closeTimestamp()).thenReturn(closeTimestamp);
        when(connection.closeReason()).thenReturn(null);
        when(connection.numberOfBlocksSent()).thenReturn(10);

        connectionHistories()
                .put(connectionId, new ConnectionHistory(connectionId, Instant.now(), null, null, null, null));

        node.onClose(connection);

        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(globalActiveStreamConnectionCount).hasValue(0);
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        final ConnectionHistory history = connectionHistories().get(connectionId);
        assertThat(history).isNotNull();
        assertThat(history.closeTimestamp).isEqualTo(closeTimestamp);
        assertThat(history.closeReason).isEqualTo(CloseReason.UNKNOWN);
        assertThat(history.numBlocksSent).isEqualTo(10);

        verify(connection, times(2)).configuration();
        verify(connection, times(2)).connectionId();
        verify(connection).closeReason();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_coolDownRequired() {
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamConnectionCount.set(1);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 110);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connectionId);
        final Instant closeTimestamp = Instant.now();
        when(connection.closeTimestamp()).thenReturn(closeTimestamp);
        when(connection.closeReason()).thenReturn(CloseReason.CONNECTION_ERROR); // CONNECTION_ERROR requires cool down
        when(connection.numberOfBlocksSent()).thenReturn(10);

        connectionHistories()
                .put(connectionId, new ConnectionHistory(connectionId, Instant.now(), null, null, null, null));

        node.onClose(connection);

        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(globalActiveStreamConnectionCount).hasValue(0);
        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(COOL_DOWN_SECONDS);
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);

        final ConnectionHistory history = connectionHistories().get(connectionId);
        assertThat(history).isNotNull();
        assertThat(history.closeTimestamp).isEqualTo(closeTimestamp);
        assertThat(history.closeReason).isEqualTo(CloseReason.CONNECTION_ERROR);
        assertThat(history.numBlocksSent).isEqualTo(10);

        verify(connection).configuration();
        verify(connection).connectionId();
        verify(connection).closeReason();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnTerminate() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(activeConnection);
        isTerminated().set(false);

        node.onTerminate(CloseReason.CONFIG_UPDATE);

        assertThat(isTerminated()).isTrue();

        verify(activeConnection).closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
    }

    @Test
    void testCloseConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(activeConnection);

        node.closeConnection(CloseReason.HIGHER_PRIORITY_FOUND);

        verify(activeConnection).closeAtBlockBoundary(CloseReason.HIGHER_PRIORITY_FOUND);
    }

    @Test
    void testIsRemovable_false() {
        isTerminated().set(false);
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));

        assertThat(node.isRemovable()).isFalse();
    }

    @Test
    void testIsRemovable_true() {
        isTerminated().set(true);
        activeStreamingConnectionRef().set(null);

        assertThat(node.isRemovable()).isTrue();
    }

    @Test
    void testOnConfigUpdate_nullConfig() {
        assertThatThrownBy(() -> node.onConfigUpdate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Configuration is required");
    }

    @Test
    void testOnConfigUpdate() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(activeConnection);
        nodeCoolDownTimestampRef().set(Instant.now());

        node.onConfigUpdate(newBlockNodeConfig("localhost", 1234, 1));

        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        verify(activeConnection).closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
        verifyNoMoreInteractions(activeConnection);
    }

    @Test
    void testIsStreamingCandidate_terminated() {
        isTerminated().set(true);

        assertThat(node.isStreamingCandidate()).isFalse();
    }

    @Test
    void testIsStreamingCandidate_nodeCoolDownActive() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(Instant.now(clock).plusSeconds(90));

        assertThat(node.isStreamingCandidate()).isFalse();
    }

    @Test
    void testIsStreamingCandidate_activeConnectionPresent() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(null);
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));

        assertThat(node.isStreamingCandidate()).isFalse();
    }

    @Test
    void testIsStreamCandidate_true() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(Instant.now(clock).minusSeconds(10));
        activeStreamingConnectionRef().set(null);

        assertThat(node.isStreamingCandidate()).isTrue();
    }

    @SuppressWarnings("unchecked")
    AtomicReference<BlockNodeConfiguration> configurationRef() {
        return (AtomicReference<BlockNodeConfiguration>) configurationRefHandle.get(node);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<Instant> nodeCoolDownTimestampRef() {
        return (AtomicReference<Instant>) nodeCoolDownTimestampRefHandle.get(node);
    }

    @SuppressWarnings("unchecked")
    ConcurrentMap<ConnectionId, ConnectionHistory> connectionHistories() {
        return (ConcurrentMap<ConnectionId, ConnectionHistory>) connectionHistoriesHandle.get(node);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<BlockNodeStreamingConnection> activeStreamingConnectionRef() {
        return (AtomicReference<BlockNodeStreamingConnection>) activeStreamingConnectionRefHandle.get(node);
    }

    AtomicBoolean isTerminated() {
        return (AtomicBoolean) isTerminatedHandle.get(node);
    }

    AtomicInteger localActiveStreamingConnectionCount() {
        return (AtomicInteger) localActiveStreamingConnectionCountHandle.get(node);
    }
}
