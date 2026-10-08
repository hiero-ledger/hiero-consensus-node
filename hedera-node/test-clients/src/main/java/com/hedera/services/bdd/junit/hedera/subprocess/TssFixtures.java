// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeTssMetadata;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
public final class TssFixtures {
    private static final Logger log = LogManager.getLogger(TssFixtures.class);

    private TssFixtures() {}

    /** Bounded cap clearing the ~20MB WRAPS-proof string; still fails fast on garbage. */
    private static final int FIXTURE_MAX_STRING_LENGTH = 100 * 1024 * 1024;

    /**
     * Streams a fixture's {@code nodeMetadata} on the port-reservation path. Jackson's default
     * {@code maxStringLength} (20MB) is smaller than a real fixture's {@code tssMetadata} WRAPS-proof string
     * (~20MB and growing), so the constraint is raised to stay safe while the parser passes over it.
     */
    private static final ObjectMapper FIXTURE_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxStringLength(FIXTURE_MAX_STRING_LENGTH)
                    .build())
            .build());

    /** Splits a multi-node fixture stem {@code <network>-node<id>} into its network name and node id. */
    private static final Pattern NODE_FIXTURE_STEM = Pattern.compile("^(.*)-node(\\d+)$");

    /** Name of the directory holding the committed fixtures and the WRAPS artifacts. */
    private static final String TSS_STARTUP_ASSETS_DIR = "tss-startup-assets";

    /** {@code tss-startup-assets/} as seen from a {@code hedera-node/test-clients/} working dir (Gradle). */
    private static final Path FROM_TEST_CLIENTS = Path.of(TSS_STARTUP_ASSETS_DIR);

    /** {@code tss-startup-assets/} as seen from the repo root (typical IDE working dir). */
    private static final Path FROM_REPO_ROOT = Path.of("hedera-node", "test-clients", TSS_STARTUP_ASSETS_DIR);

    /**
     * Where {@code tssPreload}-opted networks read cached fixtures from / write fresh ones to (absolute; see
     * {@link #resolveTssFixtureCacheDir}). The dir is gitignored; co-locates with the
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

    /**
     * Harvests the merged per-ledger TSS fixture on the cold-bootstrap path, where
     * {@code runFreezeForExport} has just successfully fired so every node's freeze export MUST exist.
     * Throws on any failure so the test surfaces a clear error instead of silently proceeding to
     * {@code restartWithFixture} (which would then fail with a generic {@code NoSuchFileException} for
     * the missing cache file).
     */
    public static void generateNetworkFixture(@NonNull final SubProcessNetwork network) {
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
    private static Network mergePerLedgerFixture(@NonNull final SubProcessNetwork network) {
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
     * Merges per-node freeze exports (keyed by nodeId) into one {@link Network}: the roster and network-wide
     * {@code tssMetadata} come from the lowest-nodeId export, and {@code nodeTssMetadata} is rebuilt (in
     * nodeId order) so every node's entry carries its own {@code blsPrivateKey}. Exports are read with
     * {@link DiskStartupNetworks#loadNetworkFrom}, the same parser the node uses for its genesis network.
     *
     * <p>Package-private for testing.
     */
    static Network mergeNodeExports(@NonNull final Map<Long, Path> exportByNode) {
        if (exportByNode.isEmpty()) {
            throw new IllegalStateException("No node exports to merge");
        }
        Network merged = null;
        final List<NodeTssMetadata> nodeTssMetadata = new ArrayList<>();
        for (final var entry : new TreeMap<>(exportByNode).entrySet()) {
            final var source = entry.getValue();
            final Network export = DiskStartupNetworks.loadNetworkFrom(source)
                    .orElseThrow(() -> new IllegalStateException("Failed to load node export " + source));
            if (merged == null) {
                merged = export;
            }
            nodeTssMetadata.add(ownPrivateEntry(export, entry.getKey(), source.toString()));
        }
        return merged.copyBuilder().nodeTssMetadata(nodeTssMetadata).build();
    }

    /**
     * The {@code nodeTssMetadata} entry carrying this node's own {@code blsPrivateKey} in its export. A node
     * can only export its own secret, so an export carrying another node's private key (or none for this
     * node) means the harvest is broken and the merged fixture would not warm-start every node.
     */
    private static NodeTssMetadata ownPrivateEntry(
            @NonNull final Network export, final long nodeId, @NonNull final String source) {
        NodeTssMetadata own = null;
        for (final var entry : export.nodeTssMetadata()) {
            final boolean hasPrivateKey = entry.blsPrivateKey().length() > 0;
            if (hasPrivateKey && entry.nodeId() != nodeId) {
                throw new IllegalStateException(source + " carries node" + entry.nodeId()
                        + "'s private key; expected only node" + nodeId + "'s");
            }
            if (entry.nodeId() == nodeId) {
                own = entry;
            }
        }
        if (own == null || own.blsPrivateKey().length() == 0) {
            throw new IllegalStateException(source + " has no private key for node" + nodeId);
        }
        return own;
    }

    private static void writeGzipped(@NonNull final Network network, @NonNull final Path dst) throws IOException {
        try (var out = new GZIPOutputStream(Files.newOutputStream(dst))) {
            Network.JSON.write(network, new WritableStreamingData(out));
        }
    }

    /**
     * Fixture filename for a whole network. Every network — regardless of size — caches a <b>single</b>
     * {@code <network>-genesis-network.json} that merges each node's own TSS private-key entry (see
     * {@link #generateNetworkFixture}). One file warm-starts the whole network, because at genesis
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
     * Resolves the fixtures dir for the two working dirs tests run from: {@code hedera-node/test-clients/}
     * (Gradle) or the repo root (IDE). Falls back to the Gradle-relative path if neither exists.
     */
    private static Path resolveTssFixtureCacheDir() {
        final var cacheDir = Files.isDirectory(FROM_REPO_ROOT) ? FROM_REPO_ROOT : FROM_TEST_CLIENTS;
        if (!Files.isDirectory(cacheDir)) {
            log.warn(
                    "[CLPR-FIXTURE] no {} dir found from working dir {}; committed fixtures won't be found",
                    TSS_STARTUP_ASSETS_DIR,
                    Path.of("").toAbsolutePath());
        }
        log.info("[CLPR-FIXTURE] using TSS fixture dir {}", cacheDir.toAbsolutePath());
        return cacheDir.toAbsolutePath();
    }
}
