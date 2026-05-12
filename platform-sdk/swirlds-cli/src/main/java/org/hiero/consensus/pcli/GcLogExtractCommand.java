// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;

/**
 * Extracts per-GC-cycle summaries from ZGC log files into a simple CSV for
 * easy correlation with stats metrics.
 *
 * <p>Produces one row per GC cycle with human-readable UTC timestamps.
 * Example output:
 * <pre>
 * time,jvm_sec,gc,type,heap_before_mb,heap_after_mb,duration_sec,concurrent_mark_ms,pause_mark_start_ms,pause_relocate_start_ms,heap_used_pct,system_load,alloc_stalls,eden_garbage_mb
 * 2026-03-14 05:00:22 UTC,22.543,0,Major,12100,638,0.077,35.244,0.038,0.010,10,0.88,0,12001
 * </pre>
 */
@CommandLine.Command(
        name = "gc-extract",
        mixinStandardHelpOptions = true,
        description = "Extracts per-GC-cycle summaries from ZGC logs into a CSV")
@SubcommandOf(Pcli.class)
public class GcLogExtractCommand extends AbstractCommand {

    private static final DateTimeFormatter OUTPUT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    // GC(0) Major Collection (Warmup) 12100M(10%)->638M(1%) 0.077s
    private static final Pattern CONCLUSION_PATTERN = Pattern.compile(
            "\\[(\\d+\\.\\d+)s].*GC\\((\\d+)\\)\\s+(Major|Minor) Collection.*?(\\d+)M\\(\\d+%\\)->(\\d+)M\\(\\d+%\\)\\s+(\\d+\\.\\d+)s");

    // GC(0) Y: Concurrent Mark 35.244ms
    private static final Pattern PHASE_PATTERN = Pattern.compile(
            "\\[(\\d+\\.\\d+)s].*GC\\((\\d+)\\)\\s+([YyOo]):\\s+(Pause|Concurrent)\\s+(.+?)\\s+(\\d+\\.\\d+)ms");

    // GC(0) Y: Load: 0.88 (2%) / 0.72 (2%) / 3.15 (7%)
    private static final Pattern LOAD_PATTERN =
            Pattern.compile("\\[(\\d+\\.\\d+)s].*GC\\((\\d+)\\)\\s+([YyOo]):\\s+Load:\\s+(\\d+\\.\\d+)");

    // GC(0) Y: Used:    12100M (10%)
    private static final Pattern HEAP_USED_PATTERN =
            Pattern.compile("\\[(\\d+\\.\\d+)s].*GC\\((\\d+)\\)\\s+([YyOo]):\\s+Used:\\s+(\\d+)M\\s+\\((\\d+)%\\)");

    // GC(0) Y: Allocation Stalls:  0  0  0  0
    private static final Pattern ALLOC_STALL_PATTERN =
            Pattern.compile("\\[(\\d+\\.\\d+)s].*GC\\((\\d+)\\)\\s+([YyOo]):\\s+Allocation Stalls:\\s+(\\d+)");

    // GC(0) Y: Eden   100M (0%)   12005M (10%)
    private static final Pattern EDEN_PATTERN = Pattern.compile(
            "\\[(\\d+\\.\\d+)s].*GC\\((\\d+)\\)\\s+([YyOo]):\\s+Eden\\s+(\\d+)M\\s+\\(\\d+%\\)\\s+(\\d+)M");

    @CommandLine.Parameters(description = "GC log file to extract")
    private Path input;

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Output CSV file (default: gc-events.csv)")
    private Path output;

    @CommandLine.Option(
            names = {"-s", "--start-time"},
            description = "JVM start time as ISO-8601 or epoch seconds, for absolute timestamps")
    private String startTime;

    @Override
    public Integer call() throws Exception {
        if (!input.toFile().isFile()) {
            System.err.println("Input file not found: " + input);
            return 1;
        }

        final long startEpochSeconds = resolveStartEpoch();
        final Path outputPath = output != null ? output : Path.of("gc-events.csv");

        // First pass: collect per-cycle data
        final Map<String, CycleData> cycles = new LinkedHashMap<>();

        try (final BufferedReader reader = new BufferedReader(new FileReader(input.toFile(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m;

                // Conclusion line (one per cycle): type, heap before/after, total duration
                m = CONCLUSION_PATTERN.matcher(line);
                if (m.find()) {
                    final String gcId = m.group(2);
                    final CycleData cycle = cycles.computeIfAbsent(gcId, k -> new CycleData());
                    cycle.conclusionSec = Double.parseDouble(m.group(1));
                    cycle.type = m.group(3);
                    cycle.heapBeforeMb = Integer.parseInt(m.group(4));
                    cycle.heapAfterMb = Integer.parseInt(m.group(5));
                    cycle.durationSec = Double.parseDouble(m.group(6));
                    continue;
                }

                // Phase durations (pick key ones for the young gen)
                m = PHASE_PATTERN.matcher(line);
                if (m.find()) {
                    final String gcId = m.group(2);
                    final String gen = m.group(3);
                    if (!"Y".equals(gen) && !"y".equals(gen)) continue;
                    final String phaseType = m.group(4);
                    final String phaseName = m.group(5).trim();
                    final double ms = Double.parseDouble(m.group(6));
                    final CycleData cycle = cycles.computeIfAbsent(gcId, _ -> new CycleData());
                    if (cycle.startSec == 0) {
                        cycle.startSec = Double.parseDouble(m.group(1));
                    }
                    if ("Concurrent".equals(phaseType) && "Mark".equals(phaseName)) {
                        cycle.concurrentMarkMs = ms;
                    } else if ("Pause".equals(phaseType) && phaseName.startsWith("Mark Start")) {
                        cycle.pauseMarkStartMs = ms;
                    } else if ("Pause".equals(phaseType) && "Mark End".equals(phaseName)) {
                        cycle.pauseMarkEndMs = ms;
                    } else if ("Pause".equals(phaseType) && "Relocate Start".equals(phaseName)) {
                        cycle.pauseRelocateStartMs = ms;
                    } else if ("Concurrent".equals(phaseType) && "Relocate".equals(phaseName)) {
                        cycle.concurrentRelocateMs = ms;
                    }
                    continue;
                }

                // System load (young gen only)
                m = LOAD_PATTERN.matcher(line);
                if (m.find()) {
                    final String gen = m.group(3);
                    if ("Y".equals(gen) || "y".equals(gen)) {
                        final CycleData cycle = cycles.computeIfAbsent(m.group(2), k -> new CycleData());
                        cycle.systemLoad = m.group(4);
                    }
                    continue;
                }

                // Heap used % (first occurrence per cycle, young gen)
                m = HEAP_USED_PATTERN.matcher(line);
                if (m.find()) {
                    final String gen = m.group(3);
                    if ("Y".equals(gen) || "y".equals(gen)) {
                        final CycleData cycle = cycles.computeIfAbsent(m.group(2), k -> new CycleData());
                        if (cycle.heapUsedPct == null) {
                            cycle.heapUsedPct = m.group(5);
                        }
                    }
                    continue;
                }

                // Allocation stalls
                m = ALLOC_STALL_PATTERN.matcher(line);
                if (m.find()) {
                    final String gen = m.group(3);
                    if ("Y".equals(gen) || "y".equals(gen)) {
                        final CycleData cycle = cycles.computeIfAbsent(m.group(2), k -> new CycleData());
                        cycle.allocStalls = m.group(4);
                    }
                    continue;
                }

                // Eden garbage
                m = EDEN_PATTERN.matcher(line);
                if (m.find()) {
                    final String gen = m.group(3);
                    if ("Y".equals(gen) || "y".equals(gen)) {
                        final CycleData cycle = cycles.computeIfAbsent(m.group(2), k -> new CycleData());
                        cycle.edenLiveMb = m.group(4);
                        cycle.edenGarbageMb = m.group(5);
                    }
                }
            }
        }

        // Write CSV
        try (final BufferedWriter writer =
                new BufferedWriter(new FileWriter(outputPath.toFile(), StandardCharsets.UTF_8))) {
            writer.write("time,jvm_sec,gc,type,heap_before_mb,heap_after_mb,duration_sec,"
                    + "concurrent_mark_ms,concurrent_relocate_ms,"
                    + "pause_mark_start_ms,pause_mark_end_ms,pause_relocate_start_ms,"
                    + "heap_used_pct,system_load,alloc_stalls,eden_live_mb,eden_garbage_mb");
            writer.newLine();

            for (final var entry : cycles.entrySet()) {
                final String gcId = entry.getKey();
                final CycleData c = entry.getValue();
                final double timeSec = c.conclusionSec > 0 ? c.conclusionSec : c.startSec;

                final String timeStr;
                if (startEpochSeconds > 0 && timeSec > 0) {
                    final long ms = (long) ((startEpochSeconds + timeSec) * 1000.0);
                    timeStr = OUTPUT_TIME_FORMAT.format(Instant.ofEpochMilli(ms));
                } else {
                    timeStr = String.format("%.3fs", timeSec);
                }

                writer.write(timeStr);
                writer.write(',');
                writer.write(String.format("%.3f", timeSec));
                writer.write(',');
                writer.write(gcId);
                writer.write(',');
                writer.write(c.type != null ? c.type : "");
                writer.write(',');
                writer.write(c.heapBeforeMb > 0 ? Integer.toString(c.heapBeforeMb) : "");
                writer.write(',');
                writer.write(c.heapAfterMb > 0 ? Integer.toString(c.heapAfterMb) : "");
                writer.write(',');
                writer.write(c.durationSec > 0 ? String.format("%.3f", c.durationSec) : "");
                writer.write(',');
                writer.write(c.concurrentMarkMs > 0 ? String.format("%.3f", c.concurrentMarkMs) : "");
                writer.write(',');
                writer.write(c.concurrentRelocateMs > 0 ? String.format("%.3f", c.concurrentRelocateMs) : "");
                writer.write(',');
                writer.write(c.pauseMarkStartMs > 0 ? String.format("%.3f", c.pauseMarkStartMs) : "");
                writer.write(',');
                writer.write(c.pauseMarkEndMs > 0 ? String.format("%.3f", c.pauseMarkEndMs) : "");
                writer.write(',');
                writer.write(c.pauseRelocateStartMs > 0 ? String.format("%.3f", c.pauseRelocateStartMs) : "");
                writer.write(',');
                writer.write(c.heapUsedPct != null ? c.heapUsedPct : "");
                writer.write(',');
                writer.write(c.systemLoad != null ? c.systemLoad : "");
                writer.write(',');
                writer.write(c.allocStalls != null ? c.allocStalls : "");
                writer.write(',');
                writer.write(c.edenLiveMb != null ? c.edenLiveMb : "");
                writer.write(',');
                writer.write(c.edenGarbageMb != null ? c.edenGarbageMb : "");
                writer.newLine();
            }
        }

        System.err.printf("Extracted %d GC cycles to %s%n", cycles.size(), outputPath);
        return 0;
    }

    private long resolveStartEpoch() {
        if (startTime != null && !startTime.isBlank()) {
            try {
                return Long.parseLong(startTime);
            } catch (final NumberFormatException e) {
                return Instant.parse(startTime).getEpochSecond();
            }
        }
        return 0;
    }

    private static class CycleData {
        double startSec;
        double conclusionSec;
        String type;
        int heapBeforeMb;
        int heapAfterMb;
        double durationSec;
        double concurrentMarkMs;
        double concurrentRelocateMs;
        double pauseMarkStartMs;
        double pauseMarkEndMs;
        double pauseRelocateStartMs;
        String heapUsedPct;
        String systemLoad;
        String allocStalls;
        String edenLiveMb;
        String edenGarbageMb;
    }
}
