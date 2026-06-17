// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;

import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

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
        if (isWriterModeGrpcOnly(spec) && blockNodeNetwork != null) {
            return new BlockNodeBlockSource(blockNodeNetwork);
        }
        return new FileSystemBlockSource(
                spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(BLOCK_STREAMS_DIR));
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
            final var writerMode = spec.startupProperties().get("blockStream.writerMode");
            return BlockStreamWriterMode.GRPC.name().equals(writerMode);
        } catch (final Exception e) {
            return false;
        }
    }
}
