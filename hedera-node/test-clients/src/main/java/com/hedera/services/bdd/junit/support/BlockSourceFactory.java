// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.spec.HapiPropertySource.inPriorityOrder;

import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chooses the appropriate {@link BlockSource} for a {@link HapiSpec}.
 *
 * <p>When the spec runs in GRPC-only writer mode <em>and</em> an active block node network exists, no
 * {@code .blk} files are written to disk, so blocks must be read from the block node over gRPC via a
 * {@link BlockNodeBlockSource}. In every other case (any disk-writing mode, or no block node — e.g.
 * embedded networks that resolve as GRPC but still write to disk) the historical
 * {@link FileSystemBlockSource} is used. This mirrors the guard used in
 * {@code HapiSpecWaitUntilNextBlock} and {@code StreamValidationOp}.
 */
public final class BlockSourceFactory {
    private BlockSourceFactory() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the block source to use for the given spec.
     *
     * @param spec the spec
     * @return a block-node source when GRPC-only and a block node network exists; otherwise a
     * file-system source over the first node's block-stream directory
     */
    @NonNull
    public static BlockSource blockSourceFor(@NonNull final HapiSpec spec) {
        final var blockNodeNetwork = resolveBlockNodeNetwork();
        if (isWriterModeGrpcOnly(spec) && blockNodeNetwork != null && !blockNodeNetwork.isEmpty()) {
            return new BlockNodeBlockSource(blockNodeNetwork);
        }
        return new FileSystemBlockSource(
                spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(BLOCK_STREAMS_DIR));
    }

    /**
     * Returns the <em>effective</em> startup properties for the spec's first node: the node's on-disk
     * {@code application.properties} (which includes any per-node {@code @GenesisSubprocessTest} overrides
     * written during {@code start()}) layered in priority over {@link HapiSpec#startupProperties()}. The
     * node file wins for keys it defines; {@code startupProperties()} supplies the rest. Falls back to
     * {@link HapiSpec#startupProperties()} when the node file is missing or unreadable.
     *
     * @param spec the spec
     * @return the effective startup properties
     */
    @NonNull
    public static HapiPropertySource effectiveStartupProperties(@NonNull final HapiSpec spec) {
        final var startupProperties = spec.startupProperties();
        try {
            final Path appPropertiesPath =
                    spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(APPLICATION_PROPERTIES);
            if (appPropertiesPath != null && Files.isReadable(appPropertiesPath)) {
                return inPriorityOrder(new JutilPropertySource(appPropertiesPath), startupProperties);
            }
        } catch (final Exception ignore) {
            // Fall back to the spec's startup properties below
        }
        return startupProperties;
    }

    @Nullable
    private static BlockNodeNetwork resolveBlockNodeNetwork() {
        var network = HapiSpec.TARGET_BLOCK_NODE_NETWORK.get();
        if (network == null) {
            network = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        }
        return network;
    }

    private static boolean isWriterModeGrpcOnly(@NonNull final HapiSpec spec) {
        try {
            final var writerMode = effectiveStartupProperties(spec).get("blockStream.writerMode");
            return BlockStreamWriterMode.GRPC.name().equals(writerMode);
        } catch (final Exception e) {
            return false;
        }
    }
}
