// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.forensics.RecordParsers.parseV6RecordStreamEntriesIn;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hederahashgraph.api.proto.java.ContractLoginfo;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders a cross-ledger CLPR "PingPong volley" by ingesting alice's and bob's
 * record streams, extracting top-level operator serve() invocations and PingPong
 * application-level events ({@code MessageReceived}, {@code MessageDropped},
 * {@code ResponseReceived}, {@code Bounced}), and emitting:
 *
 * <ul>
 *   <li>A colored CLI timeline (also written to {@code timeline.txt}).</li>
 *   <li>A Mermaid sequence diagram of the interaction ({@code volley.mmd}) — paste into
 *       any markdown viewer or <a href="https://mermaid.live">mermaid.live</a>.</li>
 * </ul>
 *
 * <p>Configurable via system properties (defaults point at the local dev paths):
 * <ul>
 *   <li>{@code -Dalice.records=/path/to/record0.0.3/}</li>
 *   <li>{@code -Dbob.records=/path/to/record0.0.3/}</li>
 *   <li>{@code -Dout.dir=/tmp/clpr-viz}</li>
 *   <li>{@code -Dfollow.seconds=N} — when {@code > 0}, stay open for N seconds and
 *       stream new events as they arrive (default 0 = single pass).</li>
 *   <li>{@code -Dfollow.interval=S} — poll interval in seconds (default 3, min 1).</li>
 * </ul>
 *
 * <p>Run via:
 * <pre>{@code
 *   # Fast path (always re-runs, no Gradle test-cache hit):
 *   ./gradlew :app-hapi-utils:visualizeVolley
 *
 *   # Or as a JUnit test (Gradle caches the result; use --rerun-tasks to force):
 *   ./gradlew :app-hapi-utils:test --tests '*ClprVolleyVisualizer*'
 *
 *   # Or invoke the main() directly from your IDE (also fast).
 * }</pre>
 */
public class ClprVolleyVisualizer {

    private static final String DEFAULT_ALICE_DIR =
            "/Users/michaeltinker/YetAnotherDev/clpr-hiero/hedera-node/hedera-app/build/node/data/recordStreams/record0.0.3/";
    private static final String DEFAULT_BOB_DIR =
            "/Users/michaeltinker/ContDev/clpr-hiero/hedera-node/hedera-app/build/node/data/recordStreams/record0.0.3/";
    private static final String DEFAULT_OUT_DIR = "/tmp/clpr-viz";

    // PingPong event topic[0] = keccak256(eventSignature)
    private static final byte[] T_MESSAGE_RECEIVED = keccak("MessageReceived(bytes32,bytes,bytes)");
    private static final byte[] T_MESSAGE_DROPPED = keccak("MessageDropped(bytes32,bytes)");
    private static final byte[] T_RESPONSE_RECEIVED = keccak("ResponseReceived(bytes32,uint64,uint8,bytes)");
    private static final byte[] T_BOUNCED = keccak("Bounced(bytes32,bytes)");

    // serve(bytes32,bytes32,bytes,bytes) selector = keccak256(sig)[:4] = 0x662cc5fb
    private static final byte[] SERVE_SELECTOR = new byte[] {0x66, 0x2c, (byte) 0xc5, (byte) 0xfb};

    // ABI tuple shapes for the non-indexed event data.
    private static final TupleType<Tuple> MESSAGE_RECEIVED_DATA = TupleType.parse("(bytes,bytes)");
    private static final TupleType<Tuple> MESSAGE_DROPPED_DATA = TupleType.parse("(bytes)");
    private static final TupleType<Tuple> RESPONSE_RECEIVED_DATA = TupleType.parse("(uint64,uint8,bytes)");
    private static final TupleType<Tuple> BOUNCED_DATA = TupleType.parse("(bytes)");

    // ANSI colors for the CLI timeline.
    private static final String RESET = "[0m";
    private static final String CYAN = "[36m";
    private static final String MAGENTA = "[35m";
    private static final String GREEN = "[32m";
    private static final String YELLOW = "[33m";
    private static final String RED = "[31m";
    private static final String GRAY = "[90m";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    /**
     * Entry point. All configuration flows through system properties (each falls back to a
     * built-in default if unset):
     * <ul>
     *   <li>{@code -Dalice.records=/path/to/record0.0.3/}</li>
     *   <li>{@code -Dbob.records=/path/to/record0.0.3/}</li>
     *   <li>{@code -Dout.dir=/path/to/output/dir}</li>
     *   <li>{@code -Dfollow.seconds=N} — when {@code > 0}, the process stays open for N
     *       seconds and re-scans both record streams on every poll, streaming any
     *       newly-observed events. The {@code timeline.txt} and {@code volley.mmd}
     *       files are rewritten with the cumulative event set on each poll.</li>
     *   <li>{@code -Dfollow.interval=S} — poll interval in seconds (default 3, min 1).</li>
     * </ul>
     */
    public static void main(final String[] args) throws IOException {
        final var aliceDir = System.getProperty("alice.records", DEFAULT_ALICE_DIR);
        final var bobDir = System.getProperty("bob.records", DEFAULT_BOB_DIR);
        final var outDir = Path.of(System.getProperty("out.dir", DEFAULT_OUT_DIR));
        final long followSeconds = parseLongOr(System.getProperty("follow.seconds"), 0L);
        final long intervalSeconds = Math.max(1L, parseLongOr(System.getProperty("follow.interval"), 3L));
        render(aliceDir, bobDir, outDir, followSeconds, intervalSeconds);
    }

    @Test
    @DisplayName("render CLPR PingPong volley timeline + Mermaid diagram")
    void renderVolleyAsTest() throws IOException {
        main(new String[0]);
    }

    static void render(
            final String aliceDir,
            final String bobDir,
            final Path outDir,
            final long followSeconds,
            final long intervalSeconds)
            throws IOException {
        Files.createDirectories(outDir);

        // Header.
        System.out.println();
        System.out.println(CYAN + "==> CLPR PingPong volley" + RESET);
        System.out.println(GRAY + "    alice:  " + aliceDir + RESET);
        System.out.println(GRAY + "    bob:    " + bobDir + RESET);
        if (followSeconds > 0) {
            System.out.println(
                    GRAY + "    follow: " + followSeconds + "s @ " + intervalSeconds + "s intervals" + RESET);
        }
        System.out.println();

        // Initial pass.
        final var seen = new HashSet<Event>();
        pollAndStream(aliceDir, bobDir, outDir, seen);

        if (followSeconds <= 0) {
            return;
        }

        // Follow loop.
        final long deadlineMs = System.currentTimeMillis() + followSeconds * 1000L;
        try {
            while (System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(intervalSeconds * 1000L);
                pollAndStream(aliceDir, bobDir, outDir, seen);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println();
            System.out.println(YELLOW + "Follow interrupted." + RESET);
            return;
        }
        System.out.println();
        System.out.println(CYAN + "==> Follow window ended (" + followSeconds + "s)." + RESET);
    }

    /**
     * Parses both record streams, streams any events not previously seen (with blank lines
     * before each {@code SERVE} to delineate volleys), and rewrites timeline.txt + volley.mmd
     * with the cumulative event set.
     */
    private static void pollAndStream(
            final String aliceDir, final String bobDir, final Path outDir, final Set<Event> seen) throws IOException {
        final var events = new ArrayList<Event>();
        events.addAll(extractEvents("alice", aliceDir));
        events.addAll(extractEvents("bob", bobDir));
        events.sort(Comparator.comparing(Event::t));

        for (final var e : events) {
            if (!seen.add(e)) {
                continue;
            }
            if (e.type == EventType.SERVE) {
                System.out.println();
                System.out.println();
            }
            System.out.println(e.cliLineColored());
        }

        // Rewrite the artifact files with the cumulative set.
        final var plain = new ArrayList<String>();
        for (final var e : events) {
            if (e.type == EventType.SERVE && !plain.isEmpty()) {
                plain.add("");
                plain.add("");
            }
            plain.add(e.cliLinePlain());
        }
        Files.write(outDir.resolve("timeline.txt"), plain);
        Files.writeString(outDir.resolve("volley.mmd"), buildMermaid(events));
    }

    private static long parseLongOr(final String s, final long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    // ───────────────────────────────────── extraction ──────────────────────────────────────

    private static List<Event> extractEvents(final String side, final String dir) throws IOException {
        if (!Files.isDirectory(Path.of(dir))) {
            // Tolerate a missing record-stream directory (e.g., during a node restart that
            // wipes it). Useful in follow mode so one side disappearing doesn't kill the loop.
            return List.of();
        }
        final var entries = parseV6RecordStreamEntriesIn(dir);
        final var out = new ArrayList<Event>();
        for (final var entry : entries) {
            final var t = entry.consensusTime();
            final var body = entry.body();
            final var txnRecord = entry.txnRecord();

            // Operator-initiated serve() invocations (top-level ContractCall with serve selector).
            if (entry.parts().function() == HederaFunctionality.ContractCall
                    && body.hasContractCall()
                    && body.getContractCall().getFunctionParameters().size() >= 4) {
                final var params =
                        body.getContractCall().getFunctionParameters().toByteArray();
                if (params[0] == SERVE_SELECTOR[0]
                        && params[1] == SERVE_SELECTOR[1]
                        && params[2] == SERVE_SELECTOR[2]
                        && params[3] == SERVE_SELECTOR[3]) {
                    // Decode (bytes32 channelId, bytes32 connectorId, bytes targetApp, bytes messageData)
                    final var tuple = TupleType.parse("(bytes32,bytes32,bytes,bytes)")
                            .decode(java.util.Arrays.copyOfRange(params, 4, params.length));
                    final var connId = (byte[]) tuple.get(0);
                    final var messageData = (byte[]) tuple.get(3);
                    out.add(new Event(side, t, EventType.SERVE, hex(connId), null, null, asText(messageData)));
                }
            }

            // PingPong event logs (in either ContractCall or ContractCreate results).
            extractLogs(side, t, txnRecord, out);
        }
        return out;
    }

    private static void extractLogs(
            final String side, final Instant t, final TransactionRecord r, final List<Event> out) {
        final var logs = new ArrayList<ContractLoginfo>();
        if (r.hasContractCallResult()) {
            logs.addAll(r.getContractCallResult().getLogInfoList());
        }
        if (r.hasContractCreateResult()) {
            logs.addAll(r.getContractCreateResult().getLogInfoList());
        }
        for (final var log : logs) {
            if (log.getTopicCount() < 1) {
                continue;
            }
            final var topic0 = log.getTopic(0).toByteArray();
            final var connId = log.getTopicCount() >= 2 ? hex(log.getTopic(1).toByteArray()) : "<no-channel-id>";
            final var data = log.getData().toByteArray();

            if (java.util.Arrays.equals(topic0, T_MESSAGE_RECEIVED)) {
                final var d = MESSAGE_RECEIVED_DATA.decode(data);
                out.add(new Event(side, t, EventType.MESSAGE_RECEIVED, connId, null, null, asText((byte[]) d.get(1))));
            } else if (java.util.Arrays.equals(topic0, T_MESSAGE_DROPPED)) {
                final var d = MESSAGE_DROPPED_DATA.decode(data);
                out.add(new Event(side, t, EventType.MESSAGE_DROPPED, connId, null, null, asText((byte[]) d.get(0))));
            } else if (java.util.Arrays.equals(topic0, T_RESPONSE_RECEIVED)) {
                final var d = RESPONSE_RECEIVED_DATA.decode(data);
                // headlong returns BigInteger for uint64 (sign-aware) and Integer for uint8 (fits int).
                final long msgId = ((java.math.BigInteger) d.get(0)).longValueExact();
                final int status = ((Integer) d.get(1));
                final var responseData = (byte[]) d.get(2);
                out.add(new Event(side, t, EventType.RESPONSE_RECEIVED, connId, msgId, status, asText(responseData)));
            } else if (java.util.Arrays.equals(topic0, T_BOUNCED)) {
                final var d = BOUNCED_DATA.decode(data);
                out.add(new Event(side, t, EventType.BOUNCED, connId, null, null, asText((byte[]) d.get(0))));
            }
        }
    }

    // ───────────────────────────────────── output ──────────────────────────────────────

    private static String buildMermaid(final List<Event> events) {
        final var sb = new StringBuilder();
        sb.append("sequenceDiagram\n");
        sb.append("    autonumber\n");
        sb.append("    participant A as alice\n");
        sb.append("    participant B as bob\n");
        for (final var e : events) {
            final var time = TS.format(e.t);
            final var actor = e.side.equals("alice") ? "A" : "B";
            final var label =
                    switch (e.type) {
                        case SERVE -> String.format("%s serve(\"%s\")", time, escape(e.payload));
                        case MESSAGE_RECEIVED -> String.format("%s MessageReceived \"%s\"", time, escape(e.payload));
                        case MESSAGE_DROPPED ->
                            String.format("%s MessageDropped \"%s\" (25%%)", time, escape(e.payload));
                        case RESPONSE_RECEIVED ->
                            String.format(
                                    "%s ResponseReceived msgId=%d status=%d data=\"%s\"",
                                    time, e.messageId, e.status, escape(e.payload));
                        case BOUNCED -> String.format("%s Bounced \"%s\" (75%%)", time, escape(e.payload));
                    };
            sb.append("    Note over ").append(actor).append(": ").append(label).append('\n');

            // Add an implicit arrow when a message lands on the other side.
            if (e.type == EventType.MESSAGE_RECEIVED || e.type == EventType.MESSAGE_DROPPED) {
                // alice→bob if event is on bob's side, bob→alice if event is on alice's side
                final var src = actor.equals("A") ? "B" : "A";
                sb.append("    ").append(src).append("-->>").append(actor).append(": delivered\n");
            } else if (e.type == EventType.RESPONSE_RECEIVED) {
                final var src = actor.equals("A") ? "B" : "A";
                sb.append("    ").append(src).append("-->>").append(actor).append(": response\n");
            }
        }
        return sb.toString();
    }

    // ───────────────────────────────────── helpers ──────────────────────────────────────

    enum EventType {
        SERVE,
        MESSAGE_RECEIVED,
        MESSAGE_DROPPED,
        RESPONSE_RECEIVED,
        BOUNCED
    }

    private record Event(
            String side, Instant t, EventType type, String channelId, Long messageId, Integer status, String payload) {

        String cliLinePlain() {
            return String.format(
                    "[%s %s] %-18s conn=%s %s",
                    side.toUpperCase(), TS.format(t), type, abbrev(channelId), payloadOrMeta());
        }

        String cliLineColored() {
            final var sideColor = side.equals("alice") ? CYAN : MAGENTA;
            final var typeColor =
                    switch (type) {
                        case SERVE -> YELLOW;
                        case MESSAGE_RECEIVED -> GREEN;
                        case MESSAGE_DROPPED -> RED;
                        case RESPONSE_RECEIVED -> GREEN;
                        case BOUNCED -> YELLOW;
                    };
            return String.format(
                    "%s[%-5s %s]%s %s%-18s%s conn=%s %s",
                    sideColor,
                    side.toUpperCase(),
                    TS.format(t),
                    RESET,
                    typeColor,
                    type,
                    RESET,
                    GRAY + abbrev(channelId) + RESET,
                    payloadOrMeta());
        }

        private String payloadOrMeta() {
            if (type == EventType.RESPONSE_RECEIVED) {
                return String.format(
                        "msgId=%d status=%d data=\"%s\"", messageId, status, payload == null ? "" : payload);
            }
            return "data=\"" + (payload == null ? "" : payload) + "\"";
        }
    }

    private static byte[] keccak(final String s) {
        return new Keccak.Digest256().digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(final byte[] b) {
        final var sb = new StringBuilder(2 * b.length);
        for (final var by : b) {
            sb.append(String.format("%02x", by & 0xFF));
        }
        return sb.toString();
    }

    private static String abbrev(final String hexHash) {
        if (hexHash == null || hexHash.length() <= 12) return String.valueOf(hexHash);
        return hexHash.substring(0, 8) + "…" + hexHash.substring(hexHash.length() - 4);
    }

    private static String asText(final byte[] b) {
        if (b == null || b.length == 0) return "";
        // Heuristic unwrap: if the bytes look like ABI-encoded `bytes` (32-byte offset
        // `0x...0020` then 32-byte length then padded payload), unwrap one level.
        // This handles the double-wrap that responseData picks up through the
        // synthetic onClprResponse dispatch.
        final var unwrapped = tryUnwrapAbiBytes(b);
        final var raw = unwrapped != null ? unwrapped : b;
        final var s = new String(raw, StandardCharsets.UTF_8);
        boolean printable = true;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t') {
                printable = false;
                break;
            }
        }
        return printable ? s : "0x" + hex(raw);
    }

    /** Returns the inner payload if {@code b} looks like ABI-encoded `bytes` (one level); else null. */
    private static byte[] tryUnwrapAbiBytes(final byte[] b) {
        if (b.length < 64) return null;
        // First 32 bytes: offset, expected = 0x20 (i.e. 32).
        for (int i = 0; i < 31; i++) if (b[i] != 0) return null;
        if (b[31] != 0x20) return null;
        // Next 32 bytes: length (we expect a small length that fits in an int).
        long len = 0;
        for (int i = 32; i < 64; i++) len = (len << 8) | (b[i] & 0xFF);
        if (len < 0 || len > b.length - 64) return null;
        return java.util.Arrays.copyOfRange(b, 64, 64 + (int) len);
    }

    private static String escape(final String s) {
        return s == null ? "" : s.replace("\"", "\\\"").replace("\n", "\\n");
    }
}
