// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.UnidirectionalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link BidirectionalConnectionImpl}.
 */
class BidirectionalConnectionImplTest {

    private UnidirectionalConnection connection;
    private UnidirectionalConnection reverse;
    private Node node1;
    private Node node2;
    private BidirectionalConnectionImpl subject;

    @BeforeEach
    void setUp() {
        connection = mock(UnidirectionalConnection.class);
        reverse = mock(UnidirectionalConnection.class);
        node1 = mock(Node.class);
        node2 = mock(Node.class);

        when(connection.sender()).thenReturn(node1);
        when(connection.receiver()).thenReturn(node2);

        subject = new BidirectionalConnectionImpl(connection, reverse);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void constructorThrowsOnNullConnection() {
        assertThatThrownBy(() -> new BidirectionalConnectionImpl(null, reverse))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BidirectionalConnectionImpl(connection, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void returnsNodes() {
        assertThat(subject.node1()).isEqualTo(node1);
        assertThat(subject.node2()).isEqualTo(node2);
    }

    @Test
    void disconnectCallsBothDirections() {
        subject.disconnect();

        verify(connection).disconnect();
        verify(reverse).disconnect();
    }

    @Test
    void connectCallsBothDirections() {
        subject.connect();

        verify(connection).connect();
        verify(reverse).connect();
    }

    @ParameterizedTest
    @CsvSource({
        "true, true, true", // both directions connected
        "false, true, false", // connection disconnected
        "true, false, false", // reverse disconnected
        "false, false, false" // both disconnected
    })
    void isConnectedReturnsTrueOnlyWhenBothDirectionsConnected(
            final boolean connectionConnected, final boolean reverseConnected, final boolean expected) {
        when(connection.isConnected()).thenReturn(connectionConnected);
        when(reverse.isConnected()).thenReturn(reverseConnected);

        assertThat(subject.isConnected()).isEqualTo(expected);
    }

    @Test
    void restoreConnectivityCallsBothDirections() {
        subject.restoreConnectivity();

        verify(connection).restoreConnectivity();
        verify(reverse).restoreConnectivity();
    }

    @ParameterizedTest
    @CsvSource({
        "100, 200, 200", // connection has higher latency
        "200, 100, 200", // reverse has higher latency
        "150, 150, 150" // both equal
    })
    void latencyReturnsMaximum(final long latency1Ms, final long latency2Ms, final long expectedMs) {
        when(connection.latency()).thenReturn(Duration.ofMillis(latency1Ms));
        when(reverse.latency()).thenReturn(Duration.ofMillis(latency2Ms));

        assertThat(subject.latency()).isEqualTo(Duration.ofMillis(expectedMs));
    }

    @Test
    void setLatencyCallsBothDirections() {
        final Duration latency = Duration.ofMillis(100);
        subject.latency(latency);

        verify(connection).latency(latency);
        verify(reverse).latency(latency);
    }

    @ParameterizedTest
    @CsvSource({
        "10.0, 20.0, 20.0", // connection has higher jitter
        "20.0, 10.0, 20.0", // reverse has higher jitter
        "15.0, 15.0, 15.0" // both equal
    })
    void jitterReturnsMaximum(final double jitter1, final double jitter2, final double expected) {
        when(connection.jitter()).thenReturn(Percentage.withPercentage(jitter1));
        when(reverse.jitter()).thenReturn(Percentage.withPercentage(jitter2));

        assertThat(subject.jitter()).isEqualTo(Percentage.withPercentage(expected));
    }

    @Test
    void setJitterCallsBothDirections() {
        final Percentage jitter = Percentage.withPercentage(10.0);
        subject.jitter(jitter);

        verify(connection).jitter(jitter);
        verify(reverse).jitter(jitter);
    }

    @Test
    void restoreLatencyCallsBothDirections() {
        subject.restoreLatency();

        verify(connection).restoreLatency();
        verify(reverse).restoreLatency();
    }

    @ParameterizedTest
    @CsvSource({
        "1000, 2000, 1000", // connection has lower bandwidth (more restrictive)
        "2000, 1000, 1000", // reverse has lower bandwidth (more restrictive)
        "1500, 1500, 1500" // both equal
    })
    void bandwidthLimitReturnsMinimum(final int bw1, final int bw2, final int expected) {
        final BandwidthLimit limit1 = BandwidthLimit.ofKilobytesPerSecond(bw1);
        final BandwidthLimit limit2 = BandwidthLimit.ofKilobytesPerSecond(bw2);

        when(connection.bandwidthLimit()).thenReturn(limit1);
        when(reverse.bandwidthLimit()).thenReturn(limit2);

        assertThat(subject.bandwidthLimit().toKilobytesPerSecond()).isEqualTo(expected);
    }

    @Test
    void setBandwidthLimitCallsBothDirections() {
        final BandwidthLimit limit = BandwidthLimit.ofKilobytesPerSecond(1000);
        subject.bandwidthLimit(limit);

        verify(connection).bandwidthLimit(limit);
        verify(reverse).bandwidthLimit(limit);
    }

    @Test
    void restoreBandwidthLimitCallsBothDirections() {
        subject.restoreBandwidthLimit();

        verify(connection).restoreBandwidthLimit();
        verify(reverse).restoreBandwidthLimit();
    }
}
