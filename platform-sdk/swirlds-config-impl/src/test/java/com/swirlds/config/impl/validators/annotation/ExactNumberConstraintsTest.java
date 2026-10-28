// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.api.validation.annotation.Negative;
import com.swirlds.config.api.validation.annotation.Positive;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks that {@code @Min}, {@code @Max}, {@code @Positive} and {@code @Negative} compare a value in its own type. The
 * comments name what the former comparison of {@link Number#longValue()} with the bound resulted in.
 */
class ExactNumberConstraintsTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(textBlock = """
            # A fraction above the maximum: truncated to 100, passed.
            percent,      100.9,                  Value must be <= 100
            # A fraction below the minimum: truncated to -1, passed.
            percent,      -1.5,                   Value must be >= -1
            # The bounds themselves: passed.
            percent,      100.0,
            percent,      -1.0,
            # A float above the maximum: truncated to 100, passed.
            floatPercent, 100.5,                  Value must be <= 100
            # A positive fraction: truncated to 0, rejected.
            positive,     0.5,
            # Zero: rejected.
            positive,     0.0,                    Value must be > 0
            # A negative fraction: truncated to 0, rejected.
            negative,     -0.5,
            # A decimal above the maximum: truncated to 100, passed.
            decimal,      100.5,                  Value must be <= 100
            # A decimal below the minimum: truncated to 0, passed.
            decimal,      -0.5,                   Value must be >= 0
            # 2^64 + 5 above the maximum: wrapped to 5, passed.
            bigInteger,   18446744073709551621,   Value must be <= 100
            # -2^64 below the minimum: wrapped to 0, passed.
            bigInteger,   -18446744073709551616,  Value must be >= 0
            # Infinity: converted to Long.MAX_VALUE, rejected.
            percent,      Infinity,               Value must be <= 100
            # Negative infinity: converted to Long.MIN_VALUE, rejected.
            percent,      -Infinity,              Value must be >= -1
            """)
    void testExactComparison(final String property, final String value, final String expectedMessage) {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue("exact." + property, value)
                .withConfigDataType(ExactNumberConstraintsConfigData.class);

        // then
        if (expectedMessage == null) {
            assertDoesNotThrow(configurationBuilder::build, "No violation should happen");
            return;
        }
        final ConfigViolationException exception =
                assertThrows(ConfigViolationException.class, configurationBuilder::build, "A violation should happen");
        assertEquals(1, exception.getViolations().size());
        final ConfigViolation violation = exception.getViolations().getFirst();
        assertEquals("exact." + property, violation.getPropertyName());
        assertEquals(value, violation.getPropertyValue());
        assertEquals(expectedMessage, violation.getMessage());
    }

    /**
     * NaN is neither less than, equal to nor greater than any bound, so it violates both. It was converted to 0 and
     * passed both.
     */
    @Test
    void testNaNViolatesEveryBound() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue("exact.percent", "NaN")
                .withConfigDataType(ExactNumberConstraintsConfigData.class);

        // when
        final ConfigViolationException exception =
                assertThrows(ConfigViolationException.class, configurationBuilder::build, "A violation should happen");

        // then
        assertEquals(
                List.of("Value must be <= 100", "Value must be >= -1"),
                exception.getViolations().stream()
                        .map(ConfigViolation::getMessage)
                        .sorted()
                        .toList());
    }

    /**
     * Number types whose values a conversion to {@code long} would truncate or wrap. Every default satisfies its
     * constraints, so a test can set exactly one property.
     */
    @ConfigData("exact")
    public record ExactNumberConstraintsConfigData(
            @ConfigProperty(defaultValue = "0") @Min(-1) @Max(100)
            double percent,

            @ConfigProperty(defaultValue = "0") @Min(-1) @Max(100)
            float floatPercent,

            @ConfigProperty(defaultValue = "1") @Positive double positive,
            @ConfigProperty(defaultValue = "-1") @Negative double negative,

            @ConfigProperty(defaultValue = "0") @Min(0) @Max(100)
            BigDecimal decimal,

            @ConfigProperty(defaultValue = "0") @Min(0) @Max(100)
            BigInteger bigInteger) {}
}
