// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class SemanticVersionConverterTest {
    public static Stream<Arguments> goodVersions() {
        return Stream.of(
                Arguments.of("0.0.0", new SemanticVersion(0, 0, 0, "", "")),
                Arguments.of("0.0.1", new SemanticVersion(0, 0, 1, "", "")),
                Arguments.of("0.1.0", new SemanticVersion(0, 1, 0, "", "")),
                Arguments.of("0.1.1", new SemanticVersion(0, 1, 1, "", "")),
                Arguments.of("1.0.0", new SemanticVersion(1, 0, 0, "", "")),
                Arguments.of("1.0.1", new SemanticVersion(1, 0, 1, "", "")),
                Arguments.of("1.1.0", new SemanticVersion(1, 1, 0, "", "")),
                Arguments.of("1.1.1", new SemanticVersion(1, 1, 1, "", "")),
                Arguments.of("1.0.0-alpha", new SemanticVersion(1, 0, 0, "alpha", "")),
                Arguments.of("1.0.0-alpha.1", new SemanticVersion(1, 0, 0, "alpha.1", "")),
                Arguments.of("1.0.0-x.7.z.92", new SemanticVersion(1, 0, 0, "x.7.z.92", "")),
                Arguments.of("1.0.0-x-y-z.--", new SemanticVersion(1, 0, 0, "x-y-z.--", "")),
                Arguments.of("1.0.0-alpha+001", new SemanticVersion(1, 0, 0, "alpha", "001")),
                Arguments.of("1.0.0+20130313144700", new SemanticVersion(1, 0, 0, "", "20130313144700")),
                Arguments.of("1.0.0-beta+exp.sha.5114f85", new SemanticVersion(1, 0, 0, "beta", "exp.sha.5114f85")),
                Arguments.of(
                        "1.0.0+21AF26D3----117B344092BD",
                        new SemanticVersion(1, 0, 0, "", "21AF26D3----117B344092BD")));
    }

    // Future: Would be worth adding some fuzz testing here, or at least many more invalid versions.
    public static Stream<Arguments> badVersions() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("1"),
                Arguments.of("1.0"),
                Arguments.of("-1.0.0"),
                Arguments.of("1.-1.0"),
                Arguments.of("1.0.-1"),
                Arguments.of(".1"),
                Arguments.of("..1"),
                Arguments.of(" 1.0.0"),
                Arguments.of("1. 0.0"),
                Arguments.of("1.0. 0"),
                Arguments.of("1.0.0 "),
                Arguments.of("1.0.1-+"));
    }

    @ParameterizedTest
    @MethodSource("goodVersions")
    void convertsValidVersion(@NonNull final String goodVersion, @NonNull final SemanticVersion expected) {
        // given:
        final var subject = new SemanticVersionConverter();

        // expect:
        assertThat(subject.convert(goodVersion)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("badVersions")
    void rejectsInvalidVersion(@NonNull final String badVersion) {
        // given:
        final var subject = new SemanticVersionConverter();

        // expect:
        assertThatThrownBy(() -> subject.convert(badVersion)).isInstanceOf(IllegalArgumentException.class);
    }
}
