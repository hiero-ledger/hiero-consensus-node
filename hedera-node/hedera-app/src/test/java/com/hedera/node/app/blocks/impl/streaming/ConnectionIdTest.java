// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import java.security.ProviderException;
import java.security.SecureRandom;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConnectionIdTest {

    /**
     * Exactly eight base-36 characters.
     */
    private static final String JVM_ID_PATTERN = "[0-9a-z]{8}";

    /**
     * The JVM suffix appended to every correlation ID. Tests assert against this literal pattern rather than
     * {@link ConnectionId#JVM_SUFFIX} so that they fail if the JVM instance token is missing.
     */
    private static final String JVM_SUFFIX_PATTERN = "-JVM" + JVM_ID_PATTERN;

    /**
     * Maximum length of the correlation ID header accepted by block nodes.
     */
    private static final int MAX_CORRELATION_ID_HEADER_LENGTH = 64;

    @Test
    void testJvmIdFormat() {
        final String jvmId = ConnectionId.jvmId();

        assertThat(jvmId).isNotEmpty().matches(JVM_ID_PATTERN);
        assertThat(ConnectionId.JVM_SUFFIX).matches(JVM_SUFFIX_PATTERN).isEqualTo("-JVM" + jvmId);
    }

    @Test
    void testJvmIdBoundIsAllBase36Values() {
        assertThat(ConnectionId.JVM_ID_BOUND).isEqualTo(Long.parseLong("zzzzzzzz", Character.MAX_RADIX) + 1);
    }

    @ParameterizedTest
    @CsvSource({
        "0, 00000000",
        "5, 00000005",
        "35, 0000000z",
        "36, 00000010",
        "1792610793, 00tn9vc9",
        "2821109907455, zzzzzzzz"
    })
    void testFormatJvmId(final long value, final String expected) {
        final String jvmId = ConnectionId.formatJvmId(value);

        assertThat(jvmId).isEqualTo(expected).matches(JVM_ID_PATTERN);
        assertThat(Long.parseLong(jvmId, Character.MAX_RADIX)).isEqualTo(value);
    }

    @Test
    void testFormatJvmId_invalidInputs() {
        assertThatThrownBy(() -> ConnectionId.formatJvmId(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionId.formatJvmId(ConnectionId.JVM_ID_BOUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testBuildJvmId_usesSuppliedRandom() {
        final LogCaptor logCaptor = new LogCaptor(LogManager.getLogger(ConnectionId.class));
        try {
            assertThat(ConnectionId.buildJvmId(() -> () -> 0L)).isEqualTo("00000000");
            assertThat(logCaptor.warnLogs()).isEmpty();
        } finally {
            logCaptor.stopCapture();
        }
    }

    @Test
    void testBuildJvmId_differsBetweenCalls() {
        final SecureRandom random = new SecureRandom();

        assertThat(ConnectionId.buildJvmId(() -> random))
                .matches(JVM_ID_PATTERN)
                .isNotEqualTo(ConnectionId.buildJvmId(() -> random));
    }

    @Test
    void testBuildJvmId_randomThrowsReturnsEmpty() {
        final LogCaptor logCaptor = new LogCaptor(LogManager.getLogger(ConnectionId.class));
        try {
            final String jvmId = ConnectionId.buildJvmId(() -> () -> {
                throw new UnsupportedOperationException("not supported");
            });

            assertThat(jvmId).isEmpty();
            assertThat(logCaptor.warnLogs()).singleElement().asString().contains("Unable to create JVM instance ID");
        } finally {
            logCaptor.stopCapture();
        }
    }

    @Test
    void testBuildJvmId_randomCreationThrowsReturnsEmpty() {
        final LogCaptor logCaptor = new LogCaptor(LogManager.getLogger(ConnectionId.class));
        try {
            // e.g. SecureRandom construction failing due to a misconfigured security provider
            final String jvmId = ConnectionId.buildJvmId(() -> {
                throw new ProviderException("no provider");
            });

            assertThat(jvmId).isEmpty();
            assertThat(logCaptor.warnLogs()).singleElement().asString().contains("Unable to create JVM instance ID");
        } finally {
            logCaptor.stopCapture();
        }
    }

    @Test
    void testCorrelationId() {
        final ConnectionId streamingId = new ConnectionId(37, ConnectionType.BLOCK_STREAMING, 6);
        final ConnectionId serviceId = new ConnectionId(37, ConnectionType.SERVER_STATUS, 41);

        assertThat(streamingId.toString())
                .matches("N37-STR6" + JVM_SUFFIX_PATTERN)
                .isEqualTo("N37-STR6" + ConnectionId.JVM_SUFFIX);
        assertThat(serviceId.toString())
                .matches("N37-SVC41" + JVM_SUFFIX_PATTERN)
                .isEqualTo("N37-SVC41" + ConnectionId.JVM_SUFFIX);
    }

    @Test
    void testJvmIdSameForAllConnections() {
        final ConnectionId first = ConnectionId.newConnectionId(1, ConnectionType.BLOCK_STREAMING);
        final ConnectionId second = ConnectionId.newConnectionId(2, ConnectionType.SERVER_STATUS);

        assertThat(first.toString()).matches("N1-STR\\d+" + JVM_SUFFIX_PATTERN).endsWith(ConnectionId.JVM_SUFFIX);
        assertThat(second.toString()).matches("N2-SVC\\d+" + JVM_SUFFIX_PATTERN).endsWith(ConnectionId.JVM_SUFFIX);
    }

    @Test
    void testEqualsAndHashCodeIgnoreJvmId() {
        final ConnectionId a = new ConnectionId(1, ConnectionType.BLOCK_STREAMING, 3);
        final ConnectionId b = new ConnectionId(1, ConnectionType.BLOCK_STREAMING, 3);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void testCorrelationIdFitsWithinHeaderLimit() {
        // worst case: max node ID / sequence number; the JVM ID is always a fixed length
        final ConnectionId connectionId =
                new ConnectionId(Long.MAX_VALUE, ConnectionType.BLOCK_STREAMING, Integer.MAX_VALUE);

        assertThat(connectionId.toString())
                .matches("N\\d+-STR\\d+" + JVM_SUFFIX_PATTERN)
                .hasSizeLessThanOrEqualTo(MAX_CORRELATION_ID_HEADER_LENGTH);
    }
}
