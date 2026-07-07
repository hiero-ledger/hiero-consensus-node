// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.util;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class HapiUtilsTest {

    @ParameterizedTest
    @CsvSource(textBlock = """
            2007-12-03T10:15:30.00Z, 2007-12-03T10:15:30.01Z
            2007-12-31T23:59:59.99Z, 2008-01-01T00:00:00.00Z
            """)
    @DisplayName("When timestamp t1 comes before timestamp t2")
    void isBefore(@NonNull final Instant i1, @NonNull final Instant i2) {
        final var t1 = asTimestamp(i1);
        final var t2 = asTimestamp(i2);
        assertThat(HapiUtils.isBefore(t1, t2)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            2007-12-03T10:15:30.01Z, 2007-12-03T10:15:30.00Z
            2008-01-01T00:00:00.00Z, 2007-12-31T23:59:59.99Z
            """)
    @DisplayName("When timestamp t1 comes after timestamp t2")
    void isAfter(@NonNull final Instant i1, @NonNull final Instant i2) {
        final var t1 = asTimestamp(i1);
        final var t2 = asTimestamp(i2);
        assertThat(HapiUtils.isBefore(t1, t2)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            2007-12-03T10:15:30.00Z, 2007-12-03T10:15:30.00Z
            2007-12-31T23:59:59.99Z, 2007-12-31T23:59:59.99Z
            2008-01-01T00:00:00.00Z, 2008-01-01T00:00:00.00Z
            """)
    @DisplayName("When timestamp t1 is the same as timestamp t2")
    void isEqual(@NonNull final Instant i1, @NonNull final Instant i2) {
        final var t1 = asTimestamp(i1);
        final var t2 = asTimestamp(i2);
        assertThat(HapiUtils.isBefore(t1, t2)).isFalse();
    }

    @Test
    @DisplayName("Converting an Instant into a Timestamp")
    void convertInstantToTimestamp() {
        // Given an instant with nanosecond precision
        final var instant = Instant.ofEpochSecond(1000, 123456789);
        // When we convert it into a timestamp
        final var timestamp = asTimestamp(instant);
        // Then we find the timestamp matches the original instant
        assertThat(timestamp)
                .isEqualTo(Timestamp.newBuilder().seconds(1000).nanos(123456789).build());
    }

    @Test
    void nonAlphaPreReleasePartsComeAfterAnyAlpha() {
        final var alphaVersion = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("alpha.4")
                .build();
        final var nonAlphaVersion = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("abcdefg")
                .build();
        assertThat(SEMANTIC_VERSION_COMPARATOR.compare(alphaVersion, nonAlphaVersion))
                .isLessThan(0);
    }

    @Test
    void nonNumericBuildPartsHaveNoEffectOnOrdering() {
        final var abcBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("abc")
                .build();
        final var defBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("def")
                .build();
        assertThat(SEMANTIC_VERSION_COMPARATOR.compare(abcBuild, defBuild)).isEqualTo(0);
    }

    @Test
    void numericBuildPartsHaveEffectOnOrdering() {
        final var zeroBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("0")
                .build();
        final var oneBuild = SemanticVersion.newBuilder()
                .major(1)
                .minor(2)
                .patch(3)
                .pre("")
                .build("1")
                .build();
        assertThat(SEMANTIC_VERSION_COMPARATOR.compare(zeroBuild, oneBuild)).isLessThan(0);
    }

    @Test
    void testEndpointForValidIpV4Address() {
        final ServiceEndpoint endpoint = HapiUtils.endpointFor("192.168.1.1", 2);
        assertEquals(endpoint.ipAddressV4(), Bytes.wrap(new byte[] {(byte) 192, (byte) 168, 1, 1}));
    }

    @Test
    void testEndpointForInvalidIpAddressConvertsToDomainName() {
        final String invalidIpAddress = "192.168.is.bad";
        Assertions.assertEquals(
                Bytes.EMPTY, HapiUtils.endpointFor(invalidIpAddress, 2).ipAddressV4());
        Assertions.assertEquals(
                invalidIpAddress, HapiUtils.endpointFor(invalidIpAddress, 2).domainName());
    }
}
