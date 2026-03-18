// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.metrics.DoubleCounter;
import org.hiero.metrics.DoubleGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.core.Label;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.core.Unit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class OpenMetricsWriterTest {

    private static class TestMetricsExporter implements MetricsExporter {

        private Supplier<MetricRegistrySnapshot> snapshotSupplier;

        @Override
        public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
            this.snapshotSupplier = snapshotSupplier;
        }

        public void exportAndVerify(OpenMetricsWriter writer, String expectedOutput, Object... args) {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                writer.write(snapshotSupplier.get(), outputStream);
                if (args.length > 0) {
                    expectedOutput = String.format(expectedOutput, args);
                }
                assertThat(new ByteArrayInputStream(outputStream.toByteArray())).hasContent(expectedOutput);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private final OpenMetricsWriter defaultWriter = new OpenMetricsWriter("#.###");
    private final TestMetricsExporter exporter = new TestMetricsExporter();

    private MetricRegistry createRegistry(Label... globalLabels) {
        MetricRegistry.Builder builder = MetricRegistry.builder().setMetricsExporter(exporter);
        for (Label globalLabel : globalLabels) {
            builder.addGlobalLabel(globalLabel);
        }
        return builder.build();
    }

    @Nested
    class CommonMetricTests {

        private static Stream<Arguments> anyValueStrings() {
            return Stream.of(
                    Arguments.of("\n Newline", "\\n Newline"),
                    Arguments.of("\\n Escaped Newline", "\\\\n Escaped Newline"),
                    Arguments.of("\t Tab", "\t Tab"),
                    Arguments.of("\\t Escaped Tab", "\\\\t Escaped Tab"),
                    Arguments.of("\" Double Quote", "\\\" Double Quote"),
                    Arguments.of("\\\" Escaped Double Quote", "\\\\\\\" Escaped Double Quote"),
                    Arguments.of("\\ Backslash", "\\\\ Backslash"),
                    Arguments.of("\\\\ Escaped Backslash", "\\\\\\\\ Escaped Backslash"),
                    Arguments.of("1", "1"),
                    Arguments.of("a", "a"),
                    Arguments.of("#$%^&*()_+-=[]{}|;':,.<>/?`~", "#$%^&*()_+-=[]{}|;':,.<>/?`~"),
                    Arguments.of("測試指標", "測試指標"),
                    Arguments.of("prueba_métrica", "prueba_métrica"),
                    Arguments.of("prueba_métrica", "prueba_métrica"),
                    Arguments.of("тест", "тест"));
        }

        @ParameterizedTest
        @MethodSource("anyValueStrings")
        void testDescriptionEscape(String description, String expectedEscapedDescription) {
            LongCounter.builder("test_metric").setDescription(description).register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_metric counter
                    # HELP test_metric %s
                    # EOF
                    """, expectedEscapedDescription);
        }

        @ParameterizedTest
        @MethodSource("anyValueStrings")
        void testGlobalLabelValueEscape(String labelValue, String expectedLabelValue) {
            LongCounter counter =
                    LongCounter.builder("test_metric").register(createRegistry(new Label("global_label", labelValue)));

            counter.getOrCreateNotLabeled().increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_metric counter
                    test_metric_total{global_label="%s"} 1
                    # EOF
                    """, expectedLabelValue);
        }

        @ParameterizedTest
        @MethodSource("anyValueStrings")
        void testStaticLabelValueEscape(String labelValue, String expectedLabelValue) {
            LongCounter counter = LongCounter.builder("test_metric")
                    .addStaticLabels(new Label("static_label", labelValue))
                    .register(createRegistry());

            counter.getOrCreateNotLabeled().increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_metric counter
                    test_metric_total{static_label="%s"} 1
                    # EOF
                    """, expectedLabelValue);
        }

        @ParameterizedTest
        @MethodSource("anyValueStrings")
        void testDynamicLabelValueEscape(String labelValue, String expectedLabelValue) {
            LongCounter counter = LongCounter.builder("test_metric")
                    .addDynamicLabelNames("dynamic_label")
                    .register(createRegistry());

            counter.getOrCreateLabeled("dynamic_label", labelValue).increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_metric counter
                    test_metric_total{dynamic_label="%s"} 1
                    # EOF
                    """, expectedLabelValue);
        }

        @Test
        void testAllLabels() {
            LongCounter counter = LongCounter.builder("test_metric")
                    .addStaticLabels(new Label("static_label", "static_value"))
                    .addDynamicLabelNames("dl2", "dl1")
                    .register(createRegistry(new Label("global_label", "global_value")));

            counter.getOrCreateLabeled("dl1", "11", "dl2", "21").increment();
            counter.getOrCreateLabeled("dl2", "22", "dl1", "12").increment(2);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_metric counter
                    test_metric_total{global_label="global_value",static_label="static_value",dl1="11",dl2="21"} 1
                    test_metric_total{global_label="global_value",static_label="static_value",dl1="12",dl2="22"} 2
                    # EOF
                    """);
        }
    }

    @Nested
    class SimplCounterTests {

        @Test
        void testEmpty() {
            MetricRegistry registry = createRegistry();

            LongCounter.builder("long_counter").register(registry);
            DoubleCounter.builder("double_counter").register(registry);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_counter counter
                    # TYPE double_counter counter
                    # EOF
                    """);
        }

        @ParameterizedTest
        @ValueSource(strings = {"bytes", "a", "a9", "valid__name__"})
        void testWithOnlyUnit(String unit) {
            LongCounter.builder("long_counter").setUnit(unit).register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_counter_%s counter
                    # UNIT long_counter_%s %s
                    # EOF
                    """, unit, unit, unit);
        }

        @Test
        void testWithUnitAndDescription() {
            LongCounter.builder("long_counter")
                    .setUnit(Unit.BYTE_UNIT)
                    .setDescription("Test description")
                    .register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_counter_byte counter
                    # UNIT long_counter_byte byte
                    # HELP long_counter_byte Test description
                    # EOF
                    """);
        }

        @ParameterizedTest
        @MethodSource("longFormattingArguments")
        void testNoLabelsLongFormatting(long value, String expectedRepresentation) {
            LongCounter counter = LongCounter.builder("long_counter").register(createRegistry());
            counter.getOrCreateNotLabeled().increment(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_counter counter
                    long_counter_total %s
                    # EOF
                    """, expectedRepresentation);
        }

        private static Stream<Arguments> longFormattingArguments() {
            return Stream.of(
                    Arguments.of(0L, "0"),
                    Arguments.of(1L, "1"),
                    Arguments.of(123456789L, "123456789"),
                    Arguments.of(Long.MAX_VALUE, "+Inf"));
        }

        @ParameterizedTest
        @MethodSource("doubleFormattingArguments")
        void testNoLabelsLongFormatting(double value, String expectedRepresentation) {
            DoubleCounter counter = DoubleCounter.builder("double_counter").register(createRegistry());
            counter.getOrCreateNotLabeled().increment(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE double_counter counter
                    double_counter_total %s
                    # EOF
                    """, expectedRepresentation);
        }

        private static Stream<Arguments> doubleFormattingArguments() {
            return Stream.of(
                    Arguments.of(Double.NaN, "NaN"),
                    Arguments.of(0.0, "0"),
                    Arguments.of(1.0, "1"),
                    Arguments.of(Double.MIN_VALUE, "0"),
                    Arguments.of(Double.POSITIVE_INFINITY, "+Inf"),
                    Arguments.of(123.456, "123.456"),
                    Arguments.of(123.123456789, "123.123"));
        }
    }

    @Nested
    class SimplGaugeTests {

        @Test
        void testEmpty() {
            MetricRegistry registry = createRegistry();

            LongGauge.builder("long_gauge").register(registry);
            DoubleGauge.builder("double_gauge").register(registry);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_gauge gauge
                    # TYPE double_gauge gauge
                    # EOF
                    """);
        }

        @ParameterizedTest
        @ValueSource(strings = {"bytes", "a", "a9", "valid__name__"})
        void testWithOnlyUnit(String unit) {
            LongGauge.builder("long_gauge").setUnit(unit).register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_gauge_%s gauge
                    # UNIT long_gauge_%s %s
                    # EOF
                    """, unit, unit, unit);
        }

        @Test
        void testWithUnitAndDescription() {
            LongGauge.builder("long_gauge")
                    .setUnit(Unit.BYTE_UNIT)
                    .setDescription("Test description")
                    .register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_gauge_byte gauge
                    # UNIT long_gauge_byte byte
                    # HELP long_gauge_byte Test description
                    # EOF
                    """);
        }

        @ParameterizedTest
        @MethodSource("longFormattingArguments")
        void testNoLabelsLongFormatting(long value, String expectedRepresentation) {
            LongGauge gauge = LongGauge.builder("long_gauge").register(createRegistry());
            gauge.getOrCreateNotLabeled().set(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_gauge gauge
                    long_gauge %s
                    # EOF
                    """, expectedRepresentation);
        }

        private static Stream<Arguments> longFormattingArguments() {
            return Stream.of(
                    Arguments.of(0L, "0"),
                    Arguments.of(-1L, "-1"),
                    Arguments.of(1L, "1"),
                    Arguments.of(123456789L, "123456789"),
                    Arguments.of(-123456789L, "-123456789"),
                    Arguments.of(Long.MIN_VALUE, "-Inf"),
                    Arguments.of(Long.MAX_VALUE, "+Inf"));
        }

        @ParameterizedTest
        @MethodSource("doubleFormattingArguments")
        void testNoLabelsLongFormatting(double value, String expectedRepresentation) {
            DoubleGauge gauge = DoubleGauge.builder("double_gauge").register(createRegistry());
            gauge.getOrCreateNotLabeled().set(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE double_gauge gauge
                    double_gauge %s
                    # EOF
                    """, expectedRepresentation);
        }

        private static Stream<Arguments> doubleFormattingArguments() {
            return Stream.of(
                    Arguments.of(Double.NaN, "NaN"),
                    Arguments.of(0.0, "0"),
                    Arguments.of(1.0, "1"),
                    Arguments.of(-1.0, "-1"),
                    Arguments.of(Double.MIN_VALUE, "0"),
                    Arguments.of(Double.NEGATIVE_INFINITY, "-Inf"),
                    Arguments.of(Double.POSITIVE_INFINITY, "+Inf"),
                    Arguments.of(123.456, "123.456"),
                    Arguments.of(-123.45, "-123.45"),
                    Arguments.of(123.123456789, "123.123"),
                    Arguments.of(-123.123456789, "-123.123"));
        }
    }
}
