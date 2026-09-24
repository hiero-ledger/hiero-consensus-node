// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Harvests, merges, caches, installs and reads the per-network genesis TSS fixtures that
 * {@code MultiNetworkExtension} preloads for {@code tssPreload}-opted networks. A focused,
 * single-purpose helper split out of the extension so the extension retains only the
 * port-reservation policy; the fixture-file lifecycle lives here.
 */
public final class ClprTssFixtureHarvester {
    private static final Logger log = LogManager.getLogger(ClprTssFixtureHarvester.class);

    private ClprTssFixtureHarvester() {}

    /** Bounded cap clearing the ~20MB WRAPS-proof string; still fails fast on garbage. */
    private static final int FIXTURE_MAX_STRING_LENGTH = 100 * 1024 * 1024;

    /**
     * Reads fixture JSON: only a fixture's {@code nodeMetadata} on the reservation path, but the whole
     * freeze export on the merge path. Jackson's default {@code maxStringLength} (20MB) is smaller than a
     * real export's {@code tssMetadata} WRAPS-proof string (~20MB and growing), so the constraint is raised
     * or {@link #mergeNodeExports} fails with {@code StreamConstraintsException}.
     */
    private static final ObjectMapper FIXTURE_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxStringLength(FIXTURE_MAX_STRING_LENGTH)
                    .build())
            .build());

    /** Splits a multi-node fixture stem {@code <network>-node<id>} into its network name and node id. */
    private static final Pattern NODE_FIXTURE_STEM = Pattern.compile("^(.*)-node(\\d+)$");

    /**
     * Where {@code tssPreload}-opted networks read cached fixtures from / write fresh ones to. Resolved
     * against the project root (gradle/test working dir varies, so we walk up from CWD until we find a
     * directory containing {@code tss-startup-assets/}). The dir is gitignored; co-locates with the
     * extracted WRAPS artifacts ({@code wraps-vX.Y.Z/}, which {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} points at).
     */
    public static final Path CACHE_DIR = resolveTssFixtureCacheDir();

    /**
     * Where {@code BlockStreamManagerImpl} writes the network-info export. Dev defaults route
     * here (see {@link com.hedera.node.config.data.NetworkAdminConfig#diskNetworkExportFile} =
     * {@code output/network.json}). For cold-cache prep runs we override the mode to
     * {@code EVERY_BLOCK} so the snapshot is continually refreshed; the opted-in prep test
     * then settles a few seconds past WRAPS-extensible to ensure the snapshot captures a
     * resume-safe {@code HistoryProofConstruction} (hasTargetProof=true).
     */
    private static final String EXPORTED_NETWORK_RELATIVE = "output/network.json";

    /** Filename consumed by DiskStartupNetworks during subprocess genesis. */
    public static final String GENESIS_NETWORK_JSON = "genesis-network.json";

    /** Extracts a node fixture's {@code blsPrivateKey} for the distinct-per-node TSS-key assertion. */
    private static final Pattern BLS_PRIVATE_KEY = Pattern.compile("\"blsPrivateKey\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Harvests the merged per-ledger TSS fixture on the cold-bootstrap path, where
     * {@code runFreezeForExport} has just successfully fired so every node's freeze export MUST exist.
     * Throws on any failure so the test surfaces a clear error instead of silently proceeding to
     * {@code restartWithFixture} (which would then fail with a generic {@code NoSuchFileException} for
     * the missing cache file).
     */
    public static void harvestFreshFixtureOrThrow(@NonNull final SubProcessNetwork network) {
        try {
            Files.createDirectories(CACHE_DIR);
            final var dst = CACHE_DIR.resolve(fixtureFileName(network.name()) + ".gz");
            writeGzipped(mergePerLedgerFixture(network), dst);
            log.info(
                    "[CLPR-FIXTURE] harvested merged '{}' fixture ({} nodes) to {} ({} bytes)",
                    network.name(),
                    network.nodes().size(),
                    dst,
                    Files.size(dst));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to harvest fresh TSS fixture for '" + network.name() + "'", e);
        }
    }

    /**
     * Merges each node's freeze export ({@code node<i>/output/network.json}) into a single per-ledger
     * network JSON. The roster and the network-wide WRAPS proof ({@code tssMetadata}) are identical across
     * all exports, so those are taken from the first node; {@code nodeTssMetadata} is rebuilt so every
     * node's entry carries <em>its own</em> private key (each export only holds the exporting node's
     * secret). At genesis each node then loads only its own key (filtered by {@code selfNodeId}), and the
     * public material entering hashed state is identical to any single node's export.
     */
    private static byte[] mergePerLedgerFixture(@NonNull final SubProcessNetwork network) throws IOException {
        final Map<Long, Path> exportByNode = new TreeMap<>();
        for (final var node : network.nodes()) {
            final var src = node.getExternalPath(WORKING_DIR).resolve(EXPORTED_NETWORK_RELATIVE);
            if (!Files.exists(src)) {
                throw new IllegalStateException(
                        "Harvest expected freeze export at " + src + " (node" + node.getNodeId() + ") — not found");
            }
            exportByNode.put(node.getNodeId(), src);
        }
        return mergeNodeExports(exportByNode);
    }

    /**
     * Merges per-node freeze exports (keyed by nodeId) into one network JSON: the roster and network-wide
     * {@code tssMetadata} come from the lowest-nodeId export, and {@code nodeTssMetadata} is rebuilt (in
     * nodeId order) so every node's entry carries its own {@code blsPrivateKey}.
     */
    public static byte[] mergeNodeExports(@NonNull final Map<Long, Path> exportByNode) throws IOException {
        if (exportByNode.isEmpty()) {
            throw new IllegalStateException("No node exports to merge");
        }
        ObjectNode merged = null;
        final ArrayNode nodeTssMetadata = FIXTURE_MAPPER.createArrayNode();
        for (final var entry : new TreeMap<>(exportByNode).entrySet()) {
            final JsonNode export = FIXTURE_MAPPER.readTree(entry.getValue().toFile());
            if (merged == null) {
                merged = (ObjectNode) export.deepCopy();
            }
            nodeTssMetadata.add(
                    ownPrivateEntry(export, entry.getKey(), entry.getValue().toString()));
        }
        merged.set("nodeTssMetadata", nodeTssMetadata);
        return FIXTURE_MAPPER.writeValueAsBytes(merged);
    }

    /** The {@code nodeTssMetadata} entry carrying this node's own {@code blsPrivateKey} in its export. */
    private static JsonNode ownPrivateEntry(
            @NonNull final JsonNode export, final long nodeId, @NonNull final String network) {
        for (final JsonNode entry : export.path("nodeTssMetadata")) {
            if (!entry.path("blsPrivateKey").asText().isEmpty()) {
                return entry;
            }
        }
        throw new IllegalStateException(
                "Node" + nodeId + " export for '" + network + "' has no blsPrivateKey entry to merge");
    }

    private static void writeGzipped(@NonNull final byte[] bytes, @NonNull final Path dst) throws IOException {
        try (var out = new GZIPOutputStream(Files.newOutputStream(dst))) {
            out.write(bytes);
        }
    }

    /**
     * Fixture filename for a whole network. Every network — regardless of size — caches a <b>single</b>
     * {@code <network>-genesis-network.json} that merges each node's own TSS private-key entry (see
     * {@link #harvestFreshFixtureOrThrow}). One file warm-starts the whole network, because at genesis
     * {@code TssStartupNetworks} loads only the self node's key (filtered by {@code selfNodeId}) into
     * node-local key files, and the public material that enters hashed state is identical for every node.
     */
    private static String fixtureFileName(@NonNull final String networkName) {
        return networkName + "-" + GENESIS_NETWORK_JSON;
    }

    /**
     * Locate the cached fixture for {@code networkName}, preferring the committed gzipped form over a
     * stale uncompressed local copy. Returns {@code null} if neither exists.
     */
    @Nullable
    public static Path resolveCachedFixturePath(@NonNull final String networkName) {
        final var base = fixtureFileName(networkName);
        final var gz = CACHE_DIR.resolve(base + ".gz");
        if (Files.exists(gz)) return gz;
        final var raw = CACHE_DIR.resolve(base);
        return Files.exists(raw) ? raw : null;
    }

    /** True iff {@code networkName} has a cached fixture. */
    public static boolean fixturePresent(@NonNull final String networkName) {
        return resolveCachedFixturePath(networkName) != null;
    }

    /**
     * Test hook: verifies the per-node TSS fixtures for {@code networkName} each carry exactly one
     * {@code blsPrivateKey} and that those keys are pairwise <b>distinct</b> — proving the per-node
     * harvest captured each node's own secret rather than a single shared node0 key (which is what
     * silently broke multi-node warm start). Throws {@link AssertionError} otherwise. Call after the
     * network is up (fixtures are harvested during network start).
     */
    public static void assertPerNodeFixturesHaveDistinctKeys(@NonNull final String networkName, final int size) {
        final var path = resolveCachedFixturePath(networkName);
        if (path == null) {
            throw new AssertionError("Missing TSS fixture for '" + networkName + "'");
        }
        final Set<String> keys = new HashSet<>();
        final var matcher = BLS_PRIVATE_KEY.matcher(readFixtureJson(path));
        while (matcher.find()) {
            final String key = matcher.group(1);
            if (key.isEmpty()) {
                throw new AssertionError("Empty blsPrivateKey in merged '" + networkName + "' fixture");
            }
            if (!keys.add(key)) {
                throw new AssertionError("Merged '" + networkName + "' fixture has a duplicate blsPrivateKey — "
                        + "the per-node private entries were not merged distinctly");
            }
        }
        if (keys.size() != size) {
            throw new AssertionError("Merged '" + networkName + "' fixture has " + keys.size()
                    + " blsPrivateKeys, expected one per node (" + size + ")");
        }
        log.info("[CLPR-FIXTURE] verified {} distinct blsPrivateKeys in merged '{}' fixture", size, networkName);
    }

    private static String readFixtureJson(@NonNull final Path path) {
        try {
            if (path.getFileName().toString().endsWith(".gz")) {
                try (var in = new GZIPInputStream(Files.newInputStream(path))) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return Files.readString(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read TSS fixture " + path, e);
        }
    }

    /**
     * Install a cached fixture at {@code dst}, decompressing on the fly if {@code src} is gzipped.
     * The subprocess's DiskStartupNetworks always reads plain JSON, so the destination is always
     * uncompressed regardless of the cached form.
     */
    public static void installFixture(@NonNull final Path src, @NonNull final Path dst) throws IOException {
        if (src.getFileName().toString().endsWith(".gz")) {
            try (var in = new GZIPInputStream(Files.newInputStream(src))) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Network name a committed fixture belongs to, or null if {@code fileName} is not a fixture we should
     * seed from. To read each network only once, multi-node networks are keyed off their {@code node0}
     * file (the other per-node files carry the same {@code nodeMetadata}); size-1 networks use their
     * single file. Handles both the committed {@code .gz} and any local uncompressed {@code .json}.
     */
    @Nullable
    public static String fixtureNetworkName(@NonNull final String fileName) {
        final String base = fileName.endsWith(".gz") ? fileName.substring(0, fileName.length() - 3) : fileName;
        final String suffix = "-" + GENESIS_NETWORK_JSON;
        if (!base.endsWith(suffix)) {
            return null;
        }
        final String stem = base.substring(0, base.length() - suffix.length());
        final var matcher = NODE_FIXTURE_STEM.matcher(stem);
        if (matcher.matches()) {
            return "0".equals(matcher.group(2)) ? matcher.group(1) : null;
        }
        return stem;
    }

    /**
     * Streams a fixture's {@code nodeMetadata} (stopping before the ~42MB {@code tssMetadata}) and returns
     * {@code {base, nodeCount}}, where {@code base} is the lowest node gRPC (service) port — i.e. the
     * network's {@code firstGrpcPort}. Returns null if the fixture has no usable {@code nodeMetadata}.
     */
    @Nullable
    public static int[] fixtureBaseAndNodeCount(@NonNull final Path fixture) throws IOException {
        int minGrpcPort = Integer.MAX_VALUE;
        int nodeCount = 0;
        try (InputStream raw = Files.newInputStream(fixture);
                InputStream in = fixture.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                JsonParser p = FIXTURE_MAPPER.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                final String field = p.currentName();
                p.nextToken();
                if (!"nodeMetadata".equals(field)) {
                    p.skipChildren();
                    continue;
                }
                if (p.currentToken() != JsonToken.START_ARRAY) {
                    return null;
                }
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    final JsonNode md = FIXTURE_MAPPER.readTree(p);
                    final int grpc = md.path("node")
                            .path("serviceEndpoint")
                            .path(0)
                            .path("port")
                            .asInt(-1);
                    if (grpc > 0) {
                        minGrpcPort = Math.min(minGrpcPort, grpc);
                    }
                    nodeCount++;
                }
                // Consumed nodeMetadata; stop before tssMetadata.
                break;
            }
        }
        return (nodeCount == 0 || minGrpcPort == Integer.MAX_VALUE) ? null : new int[] {minGrpcPort, nodeCount};
    }

    /**
     * Walk up from the test JVM's working dir to find {@code tss-startup-assets/} — the
     * developer-local dir holding warm-cache fixtures alongside the extracted WRAPS proving
     * artifacts. Canonical location is {@code hedera-node/test-clients/tss-startup-assets/}
     * (it's used only by HAPI tests). The walk-up handles both:
     *
     * <ul>
     *   <li>{@code :test-clients:testSubprocess} — CWD is the test-clients project dir, so
     *       {@code tss-startup-assets/} is found on iteration 0;</li>
     *   <li>any other invocation — walks up until it finds either {@code tss-startup-assets/}
     *       directly or the canonical {@code hedera-node/test-clients/tss-startup-assets/}
     *       path under a parent.</li>
     * </ul>
     *
     * <p>Falls back to a plain relative path if neither is found; the cache then won't
     * persist across runs, which is harmless (just slow).
     */
    private static Path resolveTssFixtureCacheDir() {
        var dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(dir.resolve("tss-startup-assets"))) {
                return dir.resolve("tss-startup-assets");
            }
            final var canonical =
                    dir.resolve("hedera-node").resolve("test-clients").resolve("tss-startup-assets");
            if (Files.isDirectory(canonical)) {
                return canonical;
            }
            final var parent = dir.getParent();
            if (parent == null) break;
            dir = parent;
        }
        return Paths.get("tss-startup-assets");
    }
}
