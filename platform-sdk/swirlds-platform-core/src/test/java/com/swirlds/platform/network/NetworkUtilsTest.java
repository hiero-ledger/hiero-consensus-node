// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.sync.FakeConnection;
import java.time.Duration;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkUtilsTest {
    private final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
    private final PlatformContext platformContext =
            TestPlatformContextBuilder.create().withConfiguration(configuration).build();
    private final RateLimiter socketExceptionRateLimiter =
            new RateLimiter(platformContext.getTime(), Duration.ofMinutes(1));

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
