// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.swirlds.base.time.Time;
import com.swirlds.platform.test.fixtures.sync.FakeConnection;
import java.time.Duration;
import javax.net.ssl.SSLException;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkUtilsTest {
    private final RateLimiter socketExceptionRateLimiter = new RateLimiter(Time.getCurrent(), Duration.ofMinutes(1));

    @Test
    void handleNetworkExceptionTest() {
        final Connection c = new FakeConnection();
        Assertions.assertDoesNotThrow(
                () -> NetworkUtils.handleNetworkException(new Exception(), c, socketExceptionRateLimiter),
                "handling should not throw an exception");
        Assertions.assertFalse(c.connected(), "method should have disconnected the connection");

        Assertions.assertDoesNotThrow(
                () -> NetworkUtils.handleNetworkException(
                        new SSLException("test", new NullPointerException()), null, socketExceptionRateLimiter),
                "handling should not throw an exception");

        Assertions.assertThrows(
                InterruptedException.class,
                () -> NetworkUtils.handleNetworkException(new InterruptedException(), null, socketExceptionRateLimiter),
                "an interrupted exception should be rethrown");
    }
}
