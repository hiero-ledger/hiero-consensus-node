// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import static com.swirlds.base.utility.NetworkUtils.isNameResolvable;
import static com.swirlds.base.utility.NetworkUtils.resolveName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Validates the behavior of the {@link NetworkUtils} utility class.
 */
public class NetworkUtilsTest {

    private static final Duration SHORT_WAIT_TIME = Duration.of(25, ChronoUnit.SECONDS);
    private static final Duration SHORT_RETRY_DELAY = Duration.of(25, ChronoUnit.MILLIS);

    @Test
    void ipAddressShouldResolve() throws UnknownHostException {
        final String ipv4 = "127.0.0.1";
        assertThat(isNameResolvable(ipv4)).isTrue();
        assertThat(resolveName(ipv4)).isNotNull();
    }

    @Test
    void validHostnameShouldResolve() throws UnknownHostException {
        final String hostname = "localhost";
        assertThat(isNameResolvable(hostname)).isTrue();
        assertThat(resolveName(hostname)).isNotNull();
    }

    @Test
    void invalidHostnameShouldNotResolve() {
        final String hostname = "invalid-hostname";
        assertThat(isNameResolvable(hostname, SHORT_WAIT_TIME, SHORT_RETRY_DELAY))
                .isFalse();
        assertThatThrownBy(() -> resolveName(hostname, SHORT_WAIT_TIME, SHORT_RETRY_DELAY))
                .isInstanceOf(UnknownHostException.class)
                .hasMessage("invalid-hostname: Name or service not known");
    }

    @Test
    void publicHostnameShouldResolve() throws UnknownHostException {
        final String hostname = "google.com";
        assertThat(isNameResolvable(hostname)).isTrue();
        assertThat(resolveName(hostname)).isNotNull().isInstanceOf(InetAddress.class);
    }

    @Test
    void nullHostnameShouldThrow() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> isNameResolvable(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("name must not be null");

        //noinspection DataFlowIssue
        assertThatThrownBy(() -> resolveName(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("name must not be null");
    }

    @Test
    void zeroDelayShouldThrow() {
        assertThatThrownBy(() -> isNameResolvable("localhost", SHORT_WAIT_TIME, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The retry delay must be greater than zero (0)");

        assertThatThrownBy(() -> resolveName("localhost", SHORT_WAIT_TIME, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The retry delay must be greater than zero (0)");
    }

    @Test
    void negativeDelayShouldThrow() {
        assertThatThrownBy(() -> isNameResolvable("localhost", SHORT_WAIT_TIME, SHORT_RETRY_DELAY.negated()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The retry delay must be greater than zero (0)");

        assertThatThrownBy(() -> resolveName("localhost", SHORT_WAIT_TIME, SHORT_RETRY_DELAY.negated()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The retry delay must be greater than zero (0)");
    }

    @Test
    void zeroWaitTimeShouldThrow() {
        assertThatThrownBy(() -> isNameResolvable("localhost", Duration.ZERO, SHORT_RETRY_DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum wait time must be greater than zero (0)");

        assertThatThrownBy(() -> resolveName("localhost", Duration.ZERO, SHORT_RETRY_DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum wait time must be greater than zero (0)");
    }

    @Test
    void negativeWaitTimeShouldThrow() {
        assertThatThrownBy(() -> isNameResolvable("localhost", SHORT_WAIT_TIME.negated(), SHORT_RETRY_DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum wait time must be greater than zero (0)");

        assertThatThrownBy(() -> resolveName("localhost", SHORT_WAIT_TIME.negated(), SHORT_RETRY_DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum wait time must be greater than zero (0)");
    }
}
