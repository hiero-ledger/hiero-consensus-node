// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

// this class should be moved to a different package, but modules are WIP as of now and it is not possible to do that
// without breaking build
// package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.ConfigurationBuilder;
import java.time.Duration;
import java.util.function.Consumer;
import org.hiero.consensus.gossip.config.BroadcastConfig;
import org.hiero.consensus.gossip.config.BroadcastConfig_;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncMetrics;
import org.hiero.consensus.gossip.impl.network.protocol.rpc.RpcOverloadMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

public class RpcOverloadMonitorTest {

    private static final int QUEUE_THRESHOLD = 10;
    private static final Duration PING_THRESHOLD = Duration.ofMillis(50);
    private static final Duration PAUSE = Duration.ofMillis(100);

    private FakeTime time;
    private SyncMetrics syncMetrics;
    private Consumer<Boolean> overloadHandler;
    private RpcOverloadMonitor monitor;

    @BeforeEach
    void setUp() {
        time = new FakeTime();
        syncMetrics = mock(SyncMetrics.class);
        overloadHandler = mock(Consumer.class);

        monitor = new RpcOverloadMonitor(
                testConfig(QUEUE_THRESHOLD, PING_THRESHOLD, PAUSE), syncMetrics, time, overloadHandler);
    }

    @Test
    void outputQueueDisablesOnThresholdBreachAndEnablesAfterPause() {
        // breach triggers disable
        monitor.reportOutputQueueSize(QUEUE_THRESHOLD + 1);

        final InOrder inOrder = inOrder(syncMetrics, overloadHandler);
        inOrder.verify(syncMetrics).disabledBroadcastDueToOverload(true);
        inOrder.verify(overloadHandler).accept(true);

        // queue is now healthy, but not enough time has passed -> still disabled
        monitor.reportOutputQueueSize(0);
        verify(syncMetrics, never()).disabledBroadcastDueToOverload(false);
        verify(overloadHandler, never()).accept(false);

        // after strictly more than PAUSE, recovery should re-enable
        time.tick(PAUSE.plusMillis(1));
        monitor.reportOutputQueueSize(0);

        verify(syncMetrics).disabledBroadcastDueToOverload(false);
        verify(overloadHandler).accept(false);
        verifyNoMoreInteractions(syncMetrics, overloadHandler);
    }

    @Test
    void outputQueueRepeatedBreachesDoNotDuplicateDisableMetricOrHandlerCall() {
        monitor.reportOutputQueueSize(QUEUE_THRESHOLD + 1);
        monitor.reportOutputQueueSize(QUEUE_THRESHOLD + 2);
        monitor.reportOutputQueueSize(QUEUE_THRESHOLD + 100);

        verify(syncMetrics, times(1)).disabledBroadcastDueToOverload(true);
        verify(overloadHandler, times(1)).accept(true);
        verify(syncMetrics, never()).disabledBroadcastDueToOverload(false);
        verify(overloadHandler, never()).accept(false);
    }

    @Test
    void pingDisablesOnThresholdBreachAndEnablesAfterPause() {
        monitor.reportPing(PING_THRESHOLD.toMillis() + 1);

        final InOrder inOrder = inOrder(syncMetrics, overloadHandler);
        inOrder.verify(syncMetrics).disabledBroadcastDueToLag(true);
        inOrder.verify(overloadHandler).accept(true);

        // healthy ping, but not enough time has passed -> still disabled
        monitor.reportPing(0);
        verify(syncMetrics, never()).disabledBroadcastDueToLag(false);
        verify(overloadHandler, never()).accept(false);

        time.tick(PAUSE.plusMillis(1));
        monitor.reportPing(0);

        verify(syncMetrics).disabledBroadcastDueToLag(false);
        verify(overloadHandler).accept(false);
        verifyNoMoreInteractions(syncMetrics, overloadHandler);
    }

    @Test
    void doesNotReEnableUntilBothQueueAndPingHaveRecovered() {
        // disable due to queue
        monitor.reportOutputQueueSize(QUEUE_THRESHOLD + 1);
        verify(syncMetrics).disabledBroadcastDueToOverload(true);
        verify(overloadHandler).accept(true);

        // then also disable due to lag
        monitor.reportPing(PING_THRESHOLD.toMillis() + 1);
        verify(syncMetrics).disabledBroadcastDueToLag(true);
        verify(overloadHandler, times(2)).accept(true); // once for queue, once for lag

        // queue recovers (after pause), but lag is still disabled -> no "accept(false)" yet
        time.tick(PAUSE.plusMillis(1));
        monitor.reportOutputQueueSize(0);

        verify(syncMetrics).disabledBroadcastDueToOverload(false);
        verify(overloadHandler, never()).accept(false);

        // lag still breaching refreshes the disable timestamp
        monitor.reportPing(PING_THRESHOLD.toMillis() + 1);

        // now lag recovers, but must wait the pause from the last lag breach
        monitor.reportPing(0);
        verify(syncMetrics, never()).disabledBroadcastDueToLag(false);

        time.tick(PAUSE.plusMillis(1));
        monitor.reportPing(0);

        verify(syncMetrics).disabledBroadcastDueToLag(false);
        verify(overloadHandler).accept(false);

        verifyNoMoreInteractions(syncMetrics, overloadHandler);
    }

    private static BroadcastConfig testConfig(
            final int queueThreshold, final Duration pingThreshold, final Duration pauseBroadcastOnLag) {

        return ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(BroadcastConfig_.THROTTLE_OUTPUT_QUEUE_THRESHOLD, String.valueOf(queueThreshold))
                .withValue(BroadcastConfig_.DISABLE_PING_THRESHOLD, String.valueOf(pingThreshold))
                .withValue(BroadcastConfig_.PAUSE_ON_LAG, String.valueOf(pauseBroadcastOnLag))
                .build()
                .getConfigData(BroadcastConfig.class);
    }
}
