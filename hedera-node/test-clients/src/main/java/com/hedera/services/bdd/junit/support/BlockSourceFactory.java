// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;

import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeReader;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;

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
        final var blockNodeNetwork = BlockNodeReader.activeNetwork();
        if (isWriterModeGrpcOnly(spec) && blockNodeNetwork != null && !blockNodeNetwork.isEmpty()) {
            return new BlockNodeBlockSource(blockNodeNetwork);
        }
        return new FileSystemBlockSource(
                spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(BLOCK_STREAMS_DIR));
    }

    /**
     * Returns the <em>effective</em> startup properties for the spec's first node — its network
     * {@link HapiSpec#startupProperties() startup properties} with that node's per-node
     * {@code @GenesisSubprocessTest}/{@code @HapiBlockNode} overrides layered on top (see
     * {@link SubProcessNetwork#effectiveStartupProperties(long)}). For non-subprocess networks, or when the
     * node has no per-node overrides, this is exactly {@link HapiSpec#startupProperties()} — so per-task
     * PR-check overrides (e.g. {@code blockStream.streamMode=RECORDS}) stay authoritative.
     *
     * @param spec the spec
     * @return the effective startup properties for routing decisions
     */
    @NonNull
    public static HapiPropertySource effectiveStartupProperties(@NonNull final HapiSpec spec) {
        try {
            final var network = spec.targetNetworkOrThrow();
            if (network instanceof SubProcessNetwork subProcessNetwork
                    && !network.nodes().isEmpty()) {
                return subProcessNetwork.effectiveStartupProperties(
                        network.nodes().getFirst().getNodeId());
            }
        } catch (final Exception ignore) {
            // Fall back to the spec's startup properties below
        }
        return spec.startupProperties();
    }

    /**
     * Returns whether the spec is configured for GRPC-only block streaming ({@code writerMode=GRPC}),
     * reading the {@link #effectiveStartupProperties(HapiSpec) effective} per-node config so per-node
     * {@code @GenesisSubprocessTest}/{@code @HapiBlockNode} overrides are honored. Shared by the consumers
     * that gate block-node reads on writer mode.
     *
     * @param spec the spec
     * @return true if the effective writer mode is GRPC-only
     */
    public static boolean isWriterModeGrpcOnly(@NonNull final HapiSpec spec) {
        try {
            final var writerMode = effectiveStartupProperties(spec).get("blockStream.writerMode");
            return BlockStreamWriterMode.GRPC.name().equals(writerMode);
        } catch (final Exception e) {
            return false;
        }
    }
}
