// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.BlockNode.BlockNodeOutOfRange;
import com.hedera.node.app.blocks.impl.streaming.BlockNode.ConnectionHistory;
import com.hedera.node.app.blocks.impl.streaming.BlockNode.DeviantConnectionClose;
import com.hedera.node.app.blocks.impl.streaming.BlockNode.ServiceConnectionFailure;
import com.hedera.node.app.blocks.impl.streaming.BlockNode.WantedBlock;
import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockBufferConfig;
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
import org.hiero.block.api.PublishStreamRequest.EndStream;
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
    private static final VarHandle activeObservingConnectionRefHandle;
    private static final VarHandle isTerminatedHandle;
    private static final VarHandle localActiveStreamingConnectionCountHandle;
    private static final VarHandle localActiveObservingConnectionCountHandle;
    private static final VarHandle wantedBlockRefHandle;

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
            activeObservingConnectionRefHandle =
                    lookup.findVarHandle(cls, "activeObservingConnectionRef", AtomicReference.class);
            isTerminatedHandle = lookup.findVarHandle(cls, "isTerminated", AtomicBoolean.class);
            localActiveStreamingConnectionCountHandle =
                    lookup.findVarHandle(cls, "localActiveStreamingConnectionCount", AtomicInteger.class);
            localActiveObservingConnectionCountHandle =
                    lookup.findVarHandle(cls, "localActiveObservingConnectionCount", AtomicInteger.class);
            wantedBlockRefHandle = lookup.findVarHandle(cls, "wantedBlockRef", AtomicReference.class);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long NODE_ID = 0L;
    private static final int BASIC_COOL_DOWN_SECONDS = 15;
    private static final int EXTENDED_COOL_DOWN_SECONDS = 30;
    private static final double NUM_BLOCKS_BEHIND_LOW_THRESHOLD = 25.0D;
    private static final double NUM_BLOCKS_BEHIND_HIGH_THRESHOLD = 50.0D;
    private static final int MAX_BUFFERED_BLOCKS = 100;

    private ConfigProvider configProvider;
    private BlockNodeConfiguration configuration;
    private AtomicInteger globalActiveStreamingConnectionCount;
    private AtomicInteger globalActiveObservingConnectionCount;
    private BlockNodeStats nodeStats;
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-02T18:00:00.000Z"), ZoneId.of("UTC"));

    private BlockNode node;

    @BeforeEach
    void beforeEach() {
        configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);
        final BlockNodeConnectionConfig bncConfig = mock(BlockNodeConnectionConfig.class);
        final BlockBufferConfig bufferConfig = mock(BlockBufferConfig.class);
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(BlockNodeConnectionConfig.class))
                .thenReturn(bncConfig);
        lenient()
                .when(versionedConfiguration.getConfigData(BlockBufferConfig.class))
                .thenReturn(bufferConfig);
        lenient().when(bncConfig.basicNodeCoolDownSeconds()).thenReturn(BASIC_COOL_DOWN_SECONDS);
        lenient().when(bncConfig.extendedNodeCoolDownSeconds()).thenReturn(EXTENDED_COOL_DOWN_SECONDS);
        lenient().when(bncConfig.wantedBlockExpirationMillis()).thenReturn(2_000L);
        lenient().when(bncConfig.numBlocksBehindLowThreshold()).thenReturn(NUM_BLOCKS_BEHIND_LOW_THRESHOLD);
        lenient().when(bncConfig.numBlocksBehindHighThreshold()).thenReturn(NUM_BLOCKS_BEHIND_HIGH_THRESHOLD);
        lenient().when(bufferConfig.maxBlocks()).thenReturn(MAX_BUFFERED_BLOCKS);

        configuration = newBlockNodeConfig("localhost", 1234, 1);
        globalActiveStreamingConnectionCount = new AtomicInteger();
        globalActiveObservingConnectionCount = new AtomicInteger();
        nodeStats = mock(BlockNodeStats.class);

        node = new BlockNode(
                configProvider,
                configuration,
                globalActiveStreamingConnectionCount,
                globalActiveObservingConnectionCount,
                nodeStats,
                clock);
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
                Block Node (host: localhost, port: 1234, priority: 1, isStreamingCandidate: true, isObservingCandidate: true, coolDownTimestamp: -, activeStreamingConnections: 0, activeObservingConnections: 0)
                  Connection History
                    <none>""";

        final String actual = sb.toString();
        assertThat(actual).isEqualTo(expectedOutput);
    }

    @Test
    void testWriteInformation_withHistory() {
        final ConnectionId conn1Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final StreamingConnectionStatistics conn1Stats = newStatsBuilder()
                .heartbeat(10_000L)
                .blocksSent(1, 10)
                .blocksAcknowledged(1, 10)
                .requestsSent(25, 25)
                .stats();
        final ConnectionHistory conn1 = new ConnectionHistory(
                conn1Id,
                Instant.parse("2026-04-02T10:00:00.000Z"),
                Instant.parse("2026-04-02T10:00:01.000Z"),
                Instant.parse("2026-04-02T10:00:05.000Z"),
                CloseReason.CONFIG_UPDATE,
                conn1Stats);
        final ConnectionId conn2Id = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 1);
        final StreamingConnectionStatistics conn2Stats = newStatsBuilder()
                .heartbeat(15_000L)
                .blocksAcknowledged(7, 9)
                .requestsSent(5, 4)
                .stats();
        final ConnectionHistory conn2 = new ConnectionHistory(
                conn2Id,
                Instant.parse("2026-04-02T11:00:00.000Z"),
                Instant.parse("2026-04-02T11:00:01.000Z"),
                Instant.parse("2026-04-02T11:00:05.000Z"),
                CloseReason.CONNECTION_ERROR,
                conn2Stats);
        final ConnectionId conn3Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 3);
        final StreamingConnectionStatistics conn3Stats = newStatsBuilder()
                .heartbeat(20_000L)
                .blocksSent(50, 93)
                .blocksAcknowledged(50, 92)
                .requestsSent(100, 100)
                .stats();
        final ConnectionHistory conn3 = new ConnectionHistory(
                conn3Id,
                Instant.parse("2026-04-02T12:00:00.000Z"),
                Instant.parse("2026-04-02T12:00:01.000Z"),
                null,
                null,
                conn3Stats);

        connectionHistories().put(conn1Id, conn1);
        connectionHistories().put(conn2Id, conn2);
        connectionHistories().put(conn3Id, conn3);
        nodeCoolDownTimestampRef().set(Instant.parse("2026-04-02T18:00:15Z"));
        localActiveStreamingConnectionCount().set(1);

        final String expectedOutput =
                """
                Block Node (host: localhost, port: 1234, priority: 1, isStreamingCandidate: false, isObservingCandidate: false, coolDownTimestamp: 2026-04-02T18:00:15Z, activeStreamingConnections: 1, activeObservingConnections: 0)
                  Connection History
                    N0-STR3%s => created: 2026-04-02T12:00:00Z, activated: 2026-04-02T12:00:01Z, closed: *, duration: *, closeReason: *, reqSendAttempts: 100*, reqSendSuccesses: 100*, lastHeartbeat: 20000*, numBlocksAcked: 43*, lastBlockAcked: 92*, numBlocksSent: 44*, lastBlockSent: 93*
                    N0-OBS1%s => created: 2026-04-02T11:00:00Z, activated: 2026-04-02T11:00:01Z, closed: 2026-04-02T11:00:05Z, duration: PT4S, closeReason: CONNECTION_ERROR, reqSendAttempts: 5, reqSendSuccesses: 4, lastHeartbeat: 15000, numBlocksAcked: 3, lastBlockAcked: 9
                    N0-STR1%s => created: 2026-04-02T10:00:00Z, activated: 2026-04-02T10:00:01Z, closed: 2026-04-02T10:00:05Z, duration: PT4S, closeReason: CONFIG_UPDATE, reqSendAttempts: 25, reqSendSuccesses: 25, lastHeartbeat: 10000, numBlocksAcked: 10, lastBlockAcked: 10, numBlocksSent: 10, lastBlockSent: 10""".formatted(ConnectionId.JVM_SUFFIX, ConnectionId.JVM_SUFFIX, ConnectionId.JVM_SUFFIX);

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
    void testOnActive_openMultipleStreamingConnections() {
        final BlockNodeStreamingConnection existingConnection = mock(BlockNodeStreamingConnection.class);
        when(existingConnection.connectionId())
                .thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1));
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamingConnectionCount.set(1);
        activeStreamingConnectionRef().set(existingConnection);

        final BlockNodeStreamingConnection newConnection = mock(BlockNodeStreamingConnection.class);
        when(newConnection.configuration()).thenReturn(configuration);
        when(newConnection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2));
        when(newConnection.currentState()).thenReturn(ConnectionState.ACTIVE);

        node.onActive(newConnection);

        assertThat(connectionHistories())
                .hasSize(1)
                .containsKey(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 2));
        assertThat(globalActiveStreamingConnectionCount).hasValue(2);
        assertThat(localActiveStreamingConnectionCount()).hasValue(2);
        assertThat(activeStreamingConnectionRef()).doesNotHaveNullValue().hasValue(newConnection);

        verify(existingConnection).closeAtBlockBoundary(CloseReason.NEW_CONNECTION);
        verify(existingConnection).connectionId();
        verifyNoMoreInteractions(existingConnection);
    }

    @Test
    void testOnActive_openMultipleObservingConnections() {
        final BlockNodeObservingConnection existingConnection = mock(BlockNodeObservingConnection.class);
        when(existingConnection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 1));
        localActiveObservingConnectionCount().set(1);
        globalActiveObservingConnectionCount.set(1);
        activeObservingConnectionRef().set(existingConnection);

        final BlockNodeObservingConnection newConnection = mock(BlockNodeObservingConnection.class);
        when(newConnection.configuration()).thenReturn(configuration);
        when(newConnection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 2));
        when(newConnection.currentState()).thenReturn(ConnectionState.ACTIVE);

        node.onActive(newConnection);

        assertThat(connectionHistories())
                .hasSize(1)
                .containsKey(new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 2));
        assertThat(globalActiveObservingConnectionCount).hasValue(2);
        assertThat(localActiveObservingConnectionCount()).hasValue(2);
        assertThat(activeObservingConnectionRef()).doesNotHaveNullValue().hasValue(newConnection);

        verify(existingConnection).close(CloseReason.NEW_CONNECTION, true, EndStream.Code.RESET);
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
        when(newConnection.currentState()).thenReturn(ConnectionState.ACTIVE);

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
        when(conn6.currentState()).thenReturn(ConnectionState.ACTIVE);

        node.onActive(conn6);

        // since Conn 1 didn't have a close timestamp, it will still exist in the map until it is closed
        assertThat(connectionHistories()).hasSize(5).containsOnlyKeys(conn1Id, conn3Id, conn4Id, conn5Id, conn6Id);

        // Current History: STR1 - STR3 - STR4 - STR5 - STR6
        // now close Conn 1 and add a new connection... now Conn 1 should be removed

        final BlockNodeStreamingConnection conn1 = mock(BlockNodeStreamingConnection.class);
        when(conn1.closeTimestamp()).thenReturn(now.minusSeconds(30));
        when(conn1.closeReason()).thenReturn(CloseReason.CONNECTION_ERROR);
        when(conn1.configuration()).thenReturn(configuration);
        when(conn1.connectionId()).thenReturn(conn1Id);

        node.onClose(conn1);

        final ConnectionId conn7Id = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 7);
        final BlockNodeStreamingConnection conn7 = mock(BlockNodeStreamingConnection.class);
        when(conn7.createTimestamp()).thenReturn(now);
        when(conn7.configuration()).thenReturn(configuration);
        when(conn7.connectionId()).thenReturn(conn7Id);
        when(conn7.currentState()).thenReturn(ConnectionState.ACTIVE);

        node.onActive(conn7);

        // Current History: STR3 - STR4 - STR5 - STR6 - STR7
        // now the history map should contain connections connection 3-7
        assertThat(connectionHistories()).hasSize(5).containsOnlyKeys(conn3Id, conn4Id, conn5Id, conn6Id, conn7Id);
    }

    @Test
    void testOnActive_streamingConnectionAlreadyClosedIsUnregistered() {
        final ConnectionId connId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connId);
        when(connection.currentState()).thenReturn(ConnectionState.CLOSED);

        node.onActive(connection);

        // the connection was closed before registration completed, so it must not be left registered
        assertThat(activeStreamingConnectionRef()).hasNullValue();
        assertThat(connectionHistories()).isEmpty();
        assertThat(globalActiveStreamingConnectionCount).hasValue(0);
        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(node.isStreamingCandidate()).isTrue();
    }

    @Test
    void testOnActive_observingConnectionAlreadyClosedIsUnregistered() {
        final ConnectionId connId = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 1);
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connId);
        when(connection.currentState()).thenReturn(ConnectionState.CLOSED);

        node.onActive(connection);

        // the connection was closed before registration completed, so it must not be left registered
        assertThat(activeObservingConnectionRef()).hasNullValue();
        assertThat(connectionHistories()).isEmpty();
        assertThat(globalActiveObservingConnectionCount).hasValue(0);
        assertThat(localActiveObservingConnectionCount()).hasValue(0);
        assertThat(node.isStreamingCandidate()).isTrue();
    }

    @Test
    void testOnActive_streamingConnectionClosedDuringRegistrationIsCleanedUpOnce() {
        final ConnectionId connId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connId);
        when(connection.closeReason()).thenReturn(CloseReason.PERIODIC_RESET);
        // the close lands once the history entry exists, so onClose() already undoes the registration
        when(connection.currentState()).thenAnswer(invocation -> {
            node.onClose(connection);
            return ConnectionState.CLOSED;
        });

        node.onActive(connection);

        // onActive() must not undo it a second time, or the counters would go negative
        assertThat(activeStreamingConnectionRef()).hasNullValue();
        assertThat(globalActiveStreamingConnectionCount).hasValue(0);
        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(connectionHistories()).containsOnlyKeys(connId);
        assertThat(connectionHistories().get(connId).closeReason).isEqualTo(CloseReason.PERIODIC_RESET);
    }

    @Test
    void testOnActive_observingConnectionClosedDuringRegistrationIsCleanedUpOnce() {
        final ConnectionId connId = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 1);
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connId);
        when(connection.closeReason()).thenReturn(CloseReason.SHUTDOWN);
        // the close lands once the history entry exists, so onClose() already undoes the registration
        when(connection.currentState()).thenAnswer(invocation -> {
            node.onClose(connection);
            return ConnectionState.CLOSED;
        });

        node.onActive(connection);

        // onActive() must not undo it a second time, or the counters would go negative
        assertThat(activeObservingConnectionRef()).hasNullValue();
        assertThat(globalActiveObservingConnectionCount).hasValue(0);
        assertThat(localActiveObservingConnectionCount()).hasValue(0);
        assertThat(connectionHistories()).containsOnlyKeys(connId);
        assertThat(connectionHistories().get(connId).closeReason).isEqualTo(CloseReason.SHUTDOWN);
    }

    @Test
    void testOnActive_streamingConnectionStillClosingKeepsItsCoolDown() {
        final ConnectionId connId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 1);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connId);
        when(connection.closeReason()).thenReturn(CloseReason.CONNECTION_ERROR);
        // close() is still running on another thread; it calls onClose() once the connection is CLOSED
        when(connection.currentState()).thenReturn(ConnectionState.CLOSING);

        node.onActive(connection);
        node.onClose(connection);

        // the registration must survive the in-progress close, or onClose() would skip the cool down
        assertThat(nodeCoolDownTimestampRef()).hasValue(Instant.now(clock).plusSeconds(BASIC_COOL_DOWN_SECONDS));
        assertThat(activeStreamingConnectionRef()).hasNullValue();
        assertThat(globalActiveStreamingConnectionCount).hasValue(0);
        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(connectionHistories().get(connId).closeReason).isEqualTo(CloseReason.CONNECTION_ERROR);
    }

    @Test
    void testOnActive_observingConnectionStillClosingKeepsItsCoolDown() {
        final ConnectionId connId = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 1);
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connId);
        when(connection.closeReason()).thenReturn(CloseReason.CONNECTION_ERROR);
        // close() is still running on another thread; it calls onClose() once the connection is CLOSED
        when(connection.currentState()).thenReturn(ConnectionState.CLOSING);

        node.onActive(connection);
        node.onClose(connection);

        // the registration must survive the in-progress close, or onClose() would skip the cool down
        assertThat(nodeCoolDownTimestampRef()).hasValue(Instant.now(clock).plusSeconds(BASIC_COOL_DOWN_SECONDS));
        assertThat(activeObservingConnectionRef()).hasNullValue();
        assertThat(globalActiveObservingConnectionCount).hasValue(0);
        assertThat(localActiveObservingConnectionCount()).hasValue(0);
        assertThat(connectionHistories().get(connId).closeReason).isEqualTo(CloseReason.CONNECTION_ERROR);
    }

    @Test
    void testOnClose_nullConnection() {
        assertThatThrownBy(() -> node.onClose(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Connection is required");
    }

    @Test
    void testOnClose_streamingConnectionNoHistory() {
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamingConnectionCount.set(1);
        // don't create a history entry for the connection
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 134));

        node.onClose(connection);

        // since there is no matching history entry for the connection, the connection counters should not change
        assertThat(localActiveStreamingConnectionCount()).hasValue(1);
        assertThat(globalActiveStreamingConnectionCount).hasValue(1);

        verify(connection).configuration();
        verify(connection, times(2)).connectionId();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_observingConnectionNoHistory() {
        localActiveObservingConnectionCount().set(1);
        globalActiveObservingConnectionCount.set(1);
        // don't create a history entry for the connection
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 134));

        node.onClose(connection);

        // since there is no matching history entry for the connection, the connection counters should not change
        assertThat(localActiveObservingConnectionCount()).hasValue(1);
        assertThat(globalActiveObservingConnectionCount).hasValue(1);

        verify(connection).configuration();
        verify(connection, times(2)).connectionId();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_streamingConnectionNoCloseReason() {
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamingConnectionCount.set(1);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 110);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connectionId);
        final Instant closeTimestamp = Instant.now();
        when(connection.closeTimestamp()).thenReturn(closeTimestamp);
        when(connection.closeReason()).thenReturn(null);

        connectionHistories()
                .put(connectionId, new ConnectionHistory(connectionId, Instant.now(), null, null, null, null));

        node.onClose(connection);

        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(globalActiveStreamingConnectionCount).hasValue(0);
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        final ConnectionHistory history = connectionHistories().get(connectionId);
        assertThat(history).isNotNull();
        assertThat(history.closeTimestamp).isEqualTo(closeTimestamp);
        assertThat(history.closeReason).isEqualTo(CloseReason.UNKNOWN);

        verify(connection, times(2)).configuration();
        verify(connection, times(2)).connectionId();
        verify(connection).closeReason();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_observingConnectionNoCloseReason() {
        localActiveObservingConnectionCount().set(1);
        globalActiveObservingConnectionCount.set(1);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 110);
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connectionId);
        final Instant closeTimestamp = Instant.now();
        when(connection.closeTimestamp()).thenReturn(closeTimestamp);
        when(connection.closeReason()).thenReturn(null);

        connectionHistories()
                .put(connectionId, new ConnectionHistory(connectionId, Instant.now(), null, null, null, null));

        node.onClose(connection);

        assertThat(localActiveObservingConnectionCount()).hasValue(0);
        assertThat(globalActiveObservingConnectionCount).hasValue(0);
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        final ConnectionHistory history = connectionHistories().get(connectionId);
        assertThat(history).isNotNull();
        assertThat(history.closeTimestamp).isEqualTo(closeTimestamp);
        assertThat(history.closeReason).isEqualTo(CloseReason.UNKNOWN);

        verify(connection, times(2)).configuration();
        verify(connection, times(2)).connectionId();
        verify(connection).closeReason();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_streamingConnectionCoolDownRequired() {
        localActiveStreamingConnectionCount().set(1);
        globalActiveStreamingConnectionCount.set(1);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 110);
        final BlockNodeStreamingConnection connection = mock(BlockNodeStreamingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connectionId);
        final Instant closeTimestamp = Instant.now();
        when(connection.closeTimestamp()).thenReturn(closeTimestamp);
        when(connection.closeReason())
                .thenReturn(CloseReason.CONNECTION_ERROR); // CONNECTION_ERROR requires basic cool down

        connectionHistories()
                .put(connectionId, new ConnectionHistory(connectionId, Instant.now(), null, null, null, null));

        node.onClose(connection);

        assertThat(localActiveStreamingConnectionCount()).hasValue(0);
        assertThat(globalActiveStreamingConnectionCount).hasValue(0);
        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(BASIC_COOL_DOWN_SECONDS);
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);

        final ConnectionHistory history = connectionHistories().get(connectionId);
        assertThat(history).isNotNull();
        assertThat(history.closeTimestamp).isEqualTo(closeTimestamp);
        assertThat(history.closeReason).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(connection).configuration();
        verify(connection).connectionId();
        verify(connection).closeReason();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnClose_observingConnectionCoolDownRequired() {
        localActiveObservingConnectionCount().set(1);
        globalActiveObservingConnectionCount.set(1);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 110);
        final BlockNodeObservingConnection connection = mock(BlockNodeObservingConnection.class);
        when(connection.configuration()).thenReturn(configuration);
        when(connection.connectionId()).thenReturn(connectionId);
        final Instant closeTimestamp = Instant.now();
        when(connection.closeTimestamp()).thenReturn(closeTimestamp);
        when(connection.closeReason())
                .thenReturn(CloseReason.CONNECTION_ERROR); // CONNECTION_ERROR requires basic cool down

        connectionHistories()
                .put(connectionId, new ConnectionHistory(connectionId, Instant.now(), null, null, null, null));

        node.onClose(connection);

        assertThat(localActiveObservingConnectionCount()).hasValue(0);
        assertThat(globalActiveObservingConnectionCount).hasValue(0);
        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(BASIC_COOL_DOWN_SECONDS);
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);

        final ConnectionHistory history = connectionHistories().get(connectionId);
        assertThat(history).isNotNull();
        assertThat(history.closeTimestamp).isEqualTo(closeTimestamp);
        assertThat(history.closeReason).isEqualTo(CloseReason.CONNECTION_ERROR);

        verify(connection).configuration();
        verify(connection).connectionId();
        verify(connection).closeReason();
        verifyNoMoreInteractions(connection);
    }

    @Test
    void testOnTerminateStreamingConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(activeConnection);
        isTerminated().set(false);

        node.onTerminate(CloseReason.CONFIG_UPDATE);

        assertThat(isTerminated()).isTrue();

        verify(activeConnection).closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
    }

    @Test
    void testOnTerminateObservingConnection() {
        final BlockNodeObservingConnection activeConnection = mock(BlockNodeObservingConnection.class);
        activeObservingConnectionRef().set(activeConnection);
        isTerminated().set(false);

        node.onTerminate(CloseReason.CONFIG_UPDATE);

        assertThat(isTerminated()).isTrue();

        verify(activeConnection).close(CloseReason.CONFIG_UPDATE, true, EndStream.Code.RESET);
    }

    @Test
    void testCloseStreamingConnection() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(activeConnection);

        node.closeConnection(CloseReason.HIGHER_PRIORITY_FOUND);

        verify(activeConnection).closeAtBlockBoundary(CloseReason.HIGHER_PRIORITY_FOUND);
    }

    @Test
    void testCloseStreamingConnection_failureSuppressed() {
        final BlockNodeStreamingConnection activeConnection = mock(BlockNodeStreamingConnection.class);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.BLOCK_STREAMING, 110);
        when(activeConnection.connectionId()).thenReturn(connectionId);
        doAnswer(_ -> {
                    throw new RuntimeException("this better not be propagated");
                })
                .when(activeConnection)
                .closeAtBlockBoundary(any(CloseReason.class));

        activeStreamingConnectionRef().set(activeConnection);

        node.closeConnection(CloseReason.HIGHER_PRIORITY_FOUND);

        verify(activeConnection).closeAtBlockBoundary(CloseReason.HIGHER_PRIORITY_FOUND);
    }

    @Test
    void testCloseObservingConnection() {
        final BlockNodeObservingConnection activeConnection = mock(BlockNodeObservingConnection.class);
        activeObservingConnectionRef().set(activeConnection);

        node.closeConnection(CloseReason.HIGHER_PRIORITY_FOUND);

        verify(activeConnection).close(CloseReason.HIGHER_PRIORITY_FOUND, true, EndStream.Code.RESET);
    }

    @Test
    void testCloseObservingConnection_failureSuppressed() {
        final BlockNodeObservingConnection activeConnection = mock(BlockNodeObservingConnection.class);
        final ConnectionId connectionId = new ConnectionId(NODE_ID, ConnectionType.ACK_OBSERVING, 110);
        when(activeConnection.connectionId()).thenReturn(connectionId);
        doAnswer(_ -> {
                    throw new RuntimeException("this better not be propagated");
                })
                .when(activeConnection)
                .close(any(CloseReason.class), anyBoolean(), any(EndStream.Code.class));

        activeObservingConnectionRef().set(activeConnection);

        node.closeConnection(CloseReason.HIGHER_PRIORITY_FOUND);

        verify(activeConnection).close(CloseReason.HIGHER_PRIORITY_FOUND, true, EndStream.Code.RESET);
    }

    @Test
    void testIsRemovable_false_withStreamingConnection() {
        isTerminated().set(false);
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));

        assertThat(node.isRemovable()).isFalse();
    }

    @Test
    void testIsRemovable_false_withObservingConnection() {
        isTerminated().set(false);
        activeObservingConnectionRef().set(mock(BlockNodeObservingConnection.class));

        assertThat(node.isRemovable()).isFalse();
    }

    @Test
    void testIsRemovable_true() {
        isTerminated().set(true);
        activeStreamingConnectionRef().set(null);
        activeObservingConnectionRef().set(null);

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
        final BlockNodeStreamingConnection streamingConnection = mock(BlockNodeStreamingConnection.class);
        activeStreamingConnectionRef().set(streamingConnection);
        final BlockNodeObservingConnection observingConnection = mock(BlockNodeObservingConnection.class);
        activeObservingConnectionRef().set(observingConnection);
        nodeCoolDownTimestampRef().set(Instant.now());

        node.onConfigUpdate(newBlockNodeConfig("localhost", 1234, 1));

        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        verify(streamingConnection).closeAtBlockBoundary(CloseReason.CONFIG_UPDATE);
        verify(observingConnection).close(CloseReason.CONFIG_UPDATE, true, EndStream.Code.RESET);
        verifyNoMoreInteractions(streamingConnection);
        verifyNoMoreInteractions(observingConnection);
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
    void testIsStreamingCandidate_activeStreamingConnectionPresent() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(null);
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));

        assertThat(node.isStreamingCandidate()).isFalse();
    }

    @Test
    void testIsStreamingCandidate_activeObservingConnectionPresent() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(null);
        activeObservingConnectionRef().set(mock(BlockNodeObservingConnection.class));

        assertThat(node.isStreamingCandidate()).isTrue();
    }

    @Test
    void testIsStreamCandidate_true() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(Instant.now(clock).minusSeconds(10));
        activeStreamingConnectionRef().set(null);

        assertThat(node.isStreamingCandidate()).isTrue();
    }

    @Test
    void testIsObservingCandidate_terminated() {
        isTerminated().set(true);
        nodeCoolDownTimestampRef().set(null);
        activeStreamingConnectionRef().set(null);
        activeObservingConnectionRef().set(null);

        assertThat(node.isObservingCandidate()).isFalse();
    }

    @Test
    void testIsObservingCandidate_nodeCoolDownActive() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(Instant.now(clock).plusSeconds(90));
        activeStreamingConnectionRef().set(null);
        activeObservingConnectionRef().set(null);

        assertThat(node.isObservingCandidate()).isFalse();
    }

    @Test
    void testIsObservingCandidate_activeStreamingConnection() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(null);
        activeStreamingConnectionRef().set(mock(BlockNodeStreamingConnection.class));
        activeObservingConnectionRef().set(null);

        assertThat(node.isObservingCandidate()).isFalse();
    }

    @Test
    void testIsObservingCandidate_activeObservingConnection() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(null);
        activeStreamingConnectionRef().set(null);
        activeObservingConnectionRef().set(mock(BlockNodeObservingConnection.class));

        assertThat(node.isObservingCandidate()).isFalse();
    }

    @Test
    void testIsObservingCandidate_true() {
        isTerminated().set(false);
        nodeCoolDownTimestampRef().set(Instant.now(clock).minusSeconds(10));
        activeStreamingConnectionRef().set(null);
        activeObservingConnectionRef().set(null);

        assertThat(node.isObservingCandidate()).isTrue();
    }

    @Test
    void testApplyCoolDown_serviceConnectionFailure() {
        assertThat(node.isStreamingCandidate()).isTrue();
        assertThat(node.isObservingCandidate()).isTrue();
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        node.applyCoolDown(new ServiceConnectionFailure());

        assertThat(node.isStreamingCandidate()).isFalse();
        assertThat(node.isObservingCandidate()).isFalse();
        assertThat(nodeCoolDownTimestampRef()).doesNotHaveNullValue();
    }

    @Test
    void testApplyCoolDown_deviantConnectionClose_basicCoolDown() {
        assertThat(node.isStreamingCandidate()).isTrue();
        assertThat(node.isObservingCandidate()).isTrue();
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        node.applyCoolDown(new DeviantConnectionClose(CloseReason.BUFFER_SATURATION));

        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(BASIC_COOL_DOWN_SECONDS);

        assertThat(node.isStreamingCandidate()).isFalse();
        assertThat(node.isObservingCandidate()).isFalse();
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);
    }

    @Test
    void testApplyCoolDown_deviantConnectionClose_extendedCoolDown() {
        assertThat(node.isStreamingCandidate()).isTrue();
        assertThat(node.isObservingCandidate()).isTrue();
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        node.applyCoolDown(new DeviantConnectionClose(CloseReason.TOO_MANY_END_STREAM_RESPONSES));

        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(EXTENDED_COOL_DOWN_SECONDS);

        assertThat(node.isStreamingCandidate()).isFalse();
        assertThat(node.isObservingCandidate()).isFalse();
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);
    }

    @Test
    void testApplyCoolDown_deviantConnectionClose_noCoolDown() {
        // This scenario shouldn't be possible, but the code is defensive to check for it
        assertThat(node.isStreamingCandidate()).isTrue();
        assertThat(node.isObservingCandidate()).isTrue();
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        node.applyCoolDown(new DeviantConnectionClose(CloseReason.SHUTDOWN));

        assertThat(node.isStreamingCandidate()).isTrue();
        assertThat(node.isObservingCandidate()).isTrue();
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();
    }

    @Test
    void testApplyCoolDown_existingLongerCoolDown() {
        // explicitly set the cool down such that an extended cool down was previously applied
        // then try to apply a basic cool down. the extended cool down should be kept since it is longer
        final Instant extendedCoolDownTimestamp = Instant.now(clock).plusSeconds(EXTENDED_COOL_DOWN_SECONDS);
        nodeCoolDownTimestampRef().set(extendedCoolDownTimestamp);

        node.applyCoolDown(new DeviantConnectionClose(CloseReason.BUFFER_SATURATION));

        assertThat(nodeCoolDownTimestampRef()).hasValue(extendedCoolDownTimestamp);
    }

    @Test
    void testApplyCoolDown_outOfRange_nonTriggering() {
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        // while the block node is out of range, it isn't out of range enough to trigger a cool down
        // buffer size is 100, and the low threshold is 25% (or 25 blocks), so make sure the amount behind is less
        node.applyCoolDown(new BlockNodeOutOfRange(24));

        assertThat(nodeCoolDownTimestampRef()).hasNullValue();
    }

    @Test
    void testApplyCoolDown_outOfRange_basicCoolDown() {
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        // the block node is just out of range to trigger the basic cool down
        // buffer size is 100, and the low threshold is 25% (or 25 blocks)
        node.applyCoolDown(new BlockNodeOutOfRange(25));

        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(BASIC_COOL_DOWN_SECONDS);
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);
    }

    @Test
    void testApplyCoolDown_outOfRange_extendedCoolDown() {
        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        // the block node is really out of range and should trigger the extended cool down
        // buffer size is 100, and the high threshold is 50% (or 50 blocks)
        node.applyCoolDown(new BlockNodeOutOfRange(50));

        final Instant expectedCoolDownTimestamp = Instant.now(clock).plusSeconds(EXTENDED_COOL_DOWN_SECONDS);
        assertThat(nodeCoolDownTimestampRef()).hasValue(expectedCoolDownTimestamp);
    }

    @Test
    void testOnServerStatusCheck_reachable() {
        final BlockNodeStatus nodeStatus = BlockNodeStatus.reachable(10, 11);

        node.onServerStatusCheck(nodeStatus);

        final AtomicReference<WantedBlock> wantedBlockRef = wantedBlockRef();
        assertThat(wantedBlockRef).doesNotHaveNullValue();
        final WantedBlock wantedBlock = wantedBlockRef.get();
        assertThat(wantedBlock.blockNumber()).isEqualTo(11);
    }

    @Test
    void testOnServerStatusCheck_reachable_blockNotSpecified() {
        final BlockNodeStatus nodeStatus = BlockNodeStatus.reachable(10, -1);

        node.onServerStatusCheck(nodeStatus);

        final AtomicReference<WantedBlock> wantedBlockRef = wantedBlockRef();
        assertThat(wantedBlockRef).doesNotHaveNullValue();
        final WantedBlock wantedBlock = wantedBlockRef.get();
        // if the wanted block is -1, then it should be carried straight over without being incremented
        assertThat(wantedBlock.blockNumber()).isEqualTo(-1);
    }

    @Test
    void testOnServerStatusCheck_notReachable() {
        final BlockNodeStatus nodeStatus = BlockNodeStatus.notReachable();

        assertThat(nodeCoolDownTimestampRef()).hasNullValue();

        node.onServerStatusCheck(nodeStatus);

        // since the node was not reachable, the node should be placed into cool down
        assertThat(nodeCoolDownTimestampRef()).doesNotHaveNullValue();
    }

    @Test
    void testWantedBlock_nullRef() {
        wantedBlockRef().set(null);

        final long wantedBlock = node.wantedBlock();

        assertThat(wantedBlock).isEqualTo(-1L);
    }

    @Test
    void testWantedBlock_expired() {
        wantedBlockRef().set(new WantedBlock(10, Instant.now(clock).minusMillis(2_001)));

        final long wantedBlock = node.wantedBlock();

        assertThat(wantedBlock).isEqualTo(-1L);
    }

    @Test
    void testWantedBlock() {
        wantedBlockRef().set(new WantedBlock(15, Instant.now(clock).minusMillis(500)));

        final long wantedBlock = node.wantedBlock();

        assertThat(wantedBlock).isEqualTo(15L);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<WantedBlock> wantedBlockRef() {
        return (AtomicReference<WantedBlock>) wantedBlockRefHandle.get(node);
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

    @SuppressWarnings("unchecked")
    AtomicReference<BlockNodeObservingConnection> activeObservingConnectionRef() {
        return (AtomicReference<BlockNodeObservingConnection>) activeObservingConnectionRefHandle.get(node);
    }

    AtomicBoolean isTerminated() {
        return (AtomicBoolean) isTerminatedHandle.get(node);
    }

    AtomicInteger localActiveStreamingConnectionCount() {
        return (AtomicInteger) localActiveStreamingConnectionCountHandle.get(node);
    }

    AtomicInteger localActiveObservingConnectionCount() {
        return (AtomicInteger) localActiveObservingConnectionCountHandle.get(node);
    }

    StreamingConnectionStatisticsBuilder newStatsBuilder() {
        return new StreamingConnectionStatisticsBuilder();
    }

    private static class StreamingConnectionStatisticsBuilder {
        final StreamingConnectionStatistics stats = new StreamingConnectionStatistics();

        StreamingConnectionStatisticsBuilder blocksSent(final long firstBlockNum, final long lastBlockNum) {
            for (long blockNum = firstBlockNum; blockNum <= lastBlockNum; ++blockNum) {
                stats.recordBlockSent(blockNum);
            }

            return this;
        }

        StreamingConnectionStatisticsBuilder heartbeat(final long heartbeatMillis) {
            stats.recordHeartbeat(heartbeatMillis);
            return this;
        }

        StreamingConnectionStatisticsBuilder blocksAcknowledged(final long firstBlockNum, final long lastBlockNum) {
            for (long blockNum = firstBlockNum; blockNum <= lastBlockNum; ++blockNum) {
                stats.recordAcknowledgement(blockNum);
            }

            return this;
        }

        StreamingConnectionStatisticsBuilder requestsSent(final int attempts, final int successes) {
            for (int i = 0; i < attempts; ++i) {
                stats.recordRequestSendAttempt();
            }

            for (int i = 0; i < successes; ++i) {
                stats.recordRequestSendSuccess();
            }

            return this;
        }

        StreamingConnectionStatistics stats() {
            return stats;
        }
    }
}
