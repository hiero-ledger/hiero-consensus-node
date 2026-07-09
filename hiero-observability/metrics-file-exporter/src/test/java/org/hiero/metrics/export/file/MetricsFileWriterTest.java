// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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

/**
 * Unit tests for {@link MetricsFileWriter}. Mirrors the harness of {@code OpenMetricsWriterTest},
 * but adapted for the Prometheus text format produced by this writer:
 * <ul>
 *   <li>{@code _total} and {@code _<unit>} suffixes are folded into the metric <b>name</b>, so
 *       {@code # TYPE}/{@code # HELP} use the full name;</li>
 *   <li>no {@code # UNIT} and no {@code # EOF} lines;</li>
 *   <li>each sample line ends with the snapshot's millisecond timestamp.</li>
 * </ul>
 * Expected outputs use the {@code @TS@} placeholder wherever the snapshot timestamp is expected;
 * the harness substitutes the captured {@link MetricRegistrySnapshot#timestamp()}.
 */
public class MetricsFileWriterTest {

    /** Placeholder substituted with the captured snapshot timestamp in expected outputs. */
    private static final String TS = "@TS@";

    private static class TestMetricsExporter implements MetricsExporter {

        private Supplier<MetricRegistrySnapshot> snapshotSupplier;
        private long lastTimestamp;

        @Override
        public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
            this.snapshotSupplier = snapshotSupplier;
        }

        /** Take a single snapshot, serialize it, and return the produced text. */
        String export(MetricsFileWriter writer) {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                MetricRegistrySnapshot snapshot = snapshotSupplier.get();
                lastTimestamp = snapshot.timestamp();
                writer.write(snapshot, outputStream);
                return outputStream.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void exportAndVerify(MetricsFileWriter writer, String expectedOutput, Object... args) {
            String actual = export(writer);
            if (args.length > 0) {
                expectedOutput = String.format(expectedOutput, args);
            }
            expectedOutput = expectedOutput.replace(TS, Long.toString(lastTimestamp));
            assertThat(actual).isEqualTo(expectedOutput);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private final MetricsFileWriter defaultWriter = new MetricsFileWriter("#.###");
    private final TestMetricsExporter exporter = new TestMetricsExporter();

    private MetricRegistry createRegistry(Label... globalLabels) {
        MetricRegistry.Builder builder = MetricRegistry.builder().setMetricsExporter(exporter);
        for (Label globalLabel : globalLabels) {
            builder.addGlobalLabel(globalLabel);
        }
        return builder.build();
    }

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
                Arguments.of("тест", "тест"));
    }

    @Nested
    class MetadataTests {

        @Test
        void testCounterTypeUsesTotalSuffix() {
            LongCounter.builder("test_counter").register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    """);
        }

        @Test
        void testGaugeTypeHasNoTotalSuffix() {
            LongGauge.builder("test_gauge").register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_gauge gauge
                    """);
        }

        @Test
        void testUnitFoldedIntoNameWithoutUnitLine() {
            LongGauge.builder("test_gauge").setUnit(Unit.BYTE_UNIT).register(createRegistry());

            // No "# UNIT" line is emitted; the unit is part of the metric name.
            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_gauge_byte gauge
                    """);
        }

        @Test
        void testUnitBeforeTotalSuffixForCounter() {
            LongCounter.builder("test_counter").setUnit(Unit.BYTE_UNIT).register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_byte_total counter
                    """);
        }

        @Test
        void testHelpWrittenWhenDescriptionPresent() {
            LongGauge.builder("test_gauge").setDescription("A description").register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_gauge gauge
                    # HELP test_gauge A description
                    """);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void testHelpSkippedWhenDescriptionBlank(String description) {
            LongGauge.builder("test_gauge").setDescription(description).register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_gauge gauge
                    """);
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.export.file.MetricsFileWriterTest#anyValueStrings")
        void testDescriptionEscape(String description, String expectedEscapedDescription) {
            LongGauge.builder("test_gauge").setDescription(description).register(createRegistry());

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_gauge gauge
                    # HELP test_gauge %s
                    """, expectedEscapedDescription);
        }

        @Test
        void testNoEofMarkerEmitted() {
            LongCounter counter = LongCounter.builder("test_counter").register(createRegistry());
            counter.getOrCreateNotLabeled().increment();

            assertThat(exporter.export(defaultWriter)).doesNotContain("# EOF");
        }

        @Test
        void testEmptyRegistryProducesNoOutput() {
            createRegistry();

            assertThat(exporter.export(defaultWriter)).isEmpty();
        }

        @Test
        void testMetadataWrittenOnlyOnceAcrossSnapshots() {
            LongCounter counter =
                    LongCounter.builder("test_counter").setDescription("desc").register(createRegistry());
            counter.getOrCreateNotLabeled().increment();

            String first = exporter.export(defaultWriter);
            long ts1 = exporter.lastTimestamp;
            String second = exporter.export(defaultWriter);
            long ts2 = exporter.lastTimestamp;

            assertThat(first)
                    .isEqualTo("# TYPE test_counter_total counter\n" + "# HELP test_counter_total desc\n"
                            + "test_counter_total 1 " + ts1 + "\n");
            // The second snapshot repeats only the sample line, not the metadata.
            assertThat(second).isEqualTo("test_counter_total 1 " + ts2 + "\n");
        }

        @Test
        void testMetadataWrittenForMetricRegisteredAfterFirstSnapshot() {
            MetricRegistry registry = createRegistry();
            LongCounter counter = LongCounter.builder("test_counter").register(registry);
            counter.getOrCreateNotLabeled().increment();

            String first = exporter.export(defaultWriter);
            long ts1 = exporter.lastTimestamp;

            // A metric registered between snapshots must get its metadata in the next snapshot.
            LongGauge gauge = LongGauge.builder("late_gauge").register(registry);
            gauge.getOrCreateNotLabeled().set(5);

            String second = exporter.export(defaultWriter);
            long ts2 = exporter.lastTimestamp;

            assertThat(first).isEqualTo("# TYPE test_counter_total counter\n" + "test_counter_total 1 " + ts1 + "\n");
            assertThat(second)
                    .isEqualTo("test_counter_total 1 " + ts2 + "\n" + "# TYPE late_gauge gauge\n" + "late_gauge 5 "
                            + ts2 + "\n");
        }
    }

    @Nested
    class LabelTests {

        @Test
        void testNoLabelsHasNoBraces() {
            LongCounter counter = LongCounter.builder("test_counter").register(createRegistry());
            counter.getOrCreateNotLabeled().increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    test_counter_total 1 @TS@
                    """);
        }

        @Test
        void testStaticLabelOnly() {
            LongCounter counter = LongCounter.builder("test_counter")
                    .addStaticLabels(new Label("static_label", "static_value"))
                    .register(createRegistry());
            counter.getOrCreateNotLabeled().increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    test_counter_total{static_label="static_value"} 1 @TS@
                    """);
        }

        @Test
        void testDynamicLabelOnly() {
            LongCounter counter = LongCounter.builder("test_counter")
                    .addDynamicLabelNames("dynamic_label")
                    .register(createRegistry());
            counter.getOrCreateLabeled("dynamic_label", "dynamic_value").increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    test_counter_total{dynamic_label="dynamic_value"} 1 @TS@
                    """);
        }

        @Test
        void testGlobalStaticAndDynamicLabelsCombined() {
            LongCounter counter = LongCounter.builder("test_counter")
                    .addStaticLabels(new Label("static_label", "static_value"))
                    .addDynamicLabelNames("dl2", "dl1")
                    .register(createRegistry(new Label("global_label", "global_value")));

            counter.getOrCreateLabeled("dl1", "11", "dl2", "21").increment();
            counter.getOrCreateLabeled("dl2", "22", "dl1", "12").increment(2);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    test_counter_total{global_label="global_value",static_label="static_value",dl1="11",dl2="21"} 1 @TS@
                    test_counter_total{global_label="global_value",static_label="static_value",dl1="12",dl2="22"} 2 @TS@
                    """);
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.export.file.MetricsFileWriterTest#anyValueStrings")
        void testStaticLabelValueEscape(String labelValue, String expectedLabelValue) {
            LongCounter counter = LongCounter.builder("test_counter")
                    .addStaticLabels(new Label("static_label", labelValue))
                    .register(createRegistry());
            counter.getOrCreateNotLabeled().increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    test_counter_total{static_label="%s"} 1 @TS@
                    """, expectedLabelValue);
        }

        @ParameterizedTest
        @MethodSource("org.hiero.metrics.export.file.MetricsFileWriterTest#anyValueStrings")
        void testDynamicLabelValueEscape(String labelValue, String expectedLabelValue) {
            LongCounter counter = LongCounter.builder("test_counter")
                    .addDynamicLabelNames("dynamic_label")
                    .register(createRegistry());
            counter.getOrCreateLabeled("dynamic_label", labelValue).increment();

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE test_counter_total counter
                    test_counter_total{dynamic_label="%s"} 1 @TS@
                    """, expectedLabelValue);
        }
    }

    @Nested
    class ValueFormattingTests {

        @ParameterizedTest
        @MethodSource("longFormattingArguments")
        void testLongCounterFormatting(long value, String expectedRepresentation) {
            LongCounter counter = LongCounter.builder("long_counter").register(createRegistry());
            counter.getOrCreateNotLabeled().increment(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_counter_total counter
                    long_counter_total %s @TS@
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
        @MethodSource("longGaugeFormattingArguments")
        void testLongGaugeFormatting(long value, String expectedRepresentation) {
            LongGauge gauge = LongGauge.builder("long_gauge").register(createRegistry());
            gauge.getOrCreateNotLabeled().set(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE long_gauge gauge
                    long_gauge %s @TS@
                    """, expectedRepresentation);
        }

        private static Stream<Arguments> longGaugeFormattingArguments() {
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
        @MethodSource("doubleGaugeFormattingArguments")
        void testDoubleGaugeFormatting(double value, String expectedRepresentation) {
            DoubleGauge gauge = DoubleGauge.builder("double_gauge").register(createRegistry());
            gauge.getOrCreateNotLabeled().set(value);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE double_gauge gauge
                    double_gauge %s @TS@
                    """, expectedRepresentation);
        }

        private static Stream<Arguments> doubleGaugeFormattingArguments() {
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
                    Arguments.of(1234.5, "1234.5"));
        }

        @Test
        void testDoubleCounterFormatting() {
            DoubleCounter counter = DoubleCounter.builder("double_counter").register(createRegistry());
            counter.getOrCreateNotLabeled().increment(2.5);

            exporter.exportAndVerify(defaultWriter, """
                    # TYPE double_counter_total counter
                    double_counter_total 2.5 @TS@
                    """);
        }

        @Test
        void testDecimalSeparatorIsLocaleIndependent() {
            Locale previous = Locale.getDefault();
            try {
                // A locale whose decimal separator is ',' would corrupt the output if the writer
                // used the default locale instead of Locale.ROOT.
                Locale.setDefault(Locale.GERMANY);
                MetricsFileWriter writer = new MetricsFileWriter("#.###");

                DoubleGauge gauge = DoubleGauge.builder("double_gauge").register(createRegistry());
                gauge.getOrCreateNotLabeled().set(1234.5);

                String output = exporter.export(writer);
                assertThat(output).contains("double_gauge 1234.5 ").doesNotContain("1234,5");
            } finally {
                Locale.setDefault(previous);
            }
        }
    }

    @Nested
    class TimestampTests {

        @Test
        void testEverySampleLineCarriesSameTimestamp() {
            LongGauge gauge = LongGauge.builder("g").addDynamicLabelNames("l").register(createRegistry());
            gauge.getOrCreateLabeled("l", "a").set(1);
            gauge.getOrCreateLabeled("l", "b").set(2);

            String output = exporter.export(defaultWriter);
            long ts = exporter.lastTimestamp;

            assertThat(output)
                    .isEqualTo("# TYPE g gauge\n" + "g{l=\"a\"} 1 " + ts + "\n" + "g{l=\"b\"} 2 " + ts + "\n");
        }
    }
}
