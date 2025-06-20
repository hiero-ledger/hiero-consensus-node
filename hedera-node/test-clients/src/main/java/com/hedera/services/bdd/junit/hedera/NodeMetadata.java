// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;

public record NodeMetadata(
        long nodeId,
        String name,
        AccountID accountId,
        String host,
        int grpcPort,
        int grpcNodeOperatorPort,
        int internalGossipPort,
        int externalGossipPort,
        int prometheusPort,
        @Nullable Path workingDir) {
    public static final int UNKNOWN_PORT = -1;

    /**
     * Create a new instance with the same values as this instance, but different ports.
     *
     * @param grpcPort the new grpc port
     * @param grpcNodeOperatorPort the new grpc node operator port
     * @param internalGossipPort the new internal gossip port
     * @param externalGossipPort the new external gossip port
     * @param prometheusPort the new prometheus port
     * @return a new instance with the same values as this instance, but different ports
     */
    public NodeMetadata withNewPorts(
            final int grpcPort,
            final int grpcNodeOperatorPort,
            final int internalGossipPort,
            final int externalGossipPort,
            final int prometheusPort) {
        return new NodeMetadata(
                nodeId,
                name,
                accountId,
                host,
                grpcPort,
                grpcNodeOperatorPort,
                internalGossipPort,
                externalGossipPort,
                prometheusPort,
                workingDir);
    }

    /**
     * Create a new instance with the same values as this instance, but a different account id.
     * @param accountId the new account id
     * @return a new instance with the same values as this instance, but a different account id
     */
    public NodeMetadata withNewAccountId(@NonNull final AccountID accountId) {
        requireNonNull(accountId);
        return new NodeMetadata(
                nodeId,
                name,
                accountId,
                host,
                grpcPort,
                grpcNodeOperatorPort,
                internalGossipPort,
                externalGossipPort,
                prometheusPort,
                workingDir);
    }

    /**
     * Returns the working directory for this node, or throws an exception if the working directory is null.
     *
     * @return the working directory for this node
     */
    public @NonNull Path workingDirOrThrow() {
        return requireNonNull(workingDir);
    }
}
