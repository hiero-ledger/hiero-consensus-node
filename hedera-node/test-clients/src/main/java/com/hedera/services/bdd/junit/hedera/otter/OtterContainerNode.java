// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.hedera.AbstractNode;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.MarkerFile;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.container.ContainerNode;

/**
 * A Hedera node that runs in a container using the Otter test framework.
 */
public class OtterContainerNode extends AbstractNode implements HederaNode {

    private final ContainerNode node;
    private final ConcurrentMap<PlatformStatus, CompletableFuture<Void>> activeFuture = new ConcurrentHashMap<>();
    private final Executor executor;

    /**
     * Constructs a new OtterContainerNode.
     *
     * @param metadata the node metadata
     * @param node the otter container node
     * @param executor the executor for async operations
     */
    public OtterContainerNode(
            @NonNull final NodeMetadata metadata, @NonNull final ContainerNode node, @NonNull final Executor executor) {
        super(metadata);
        this.node = requireNonNull(node);
        this.executor = executor;
    }

    @Override
    @NonNull
    public Path getExternalPath(@NonNull final ExternalPath path) {
        throw new UnsupportedOperationException("There is no local path to a container node's " + path);
    }

    @Override
    @NonNull
    public HederaNode initWorkingDir(@NonNull final String configTxt) {
        requireNonNull(configTxt);
        final Path workingDir = requireNonNull(metadata.workingDir());
        new ContainerWorkingDirInitializer(node, workingDir, configTxt).recreateWorkingDir();
        return this;
    }

    @Override
    public HederaNode start() {
        activeFuture.computeIfAbsent(
                PlatformStatus.ACTIVE, status -> CompletableFuture.runAsync(node::start, executor));
        return this;
    }

    @Override
    public CompletableFuture<Void> statusFuture(
            @Nullable final Consumer<NodeStatus> nodeStatusObserver, @NonNull final PlatformStatus... statuses) {
        if (statuses.length != 1 || statuses[0] != PlatformStatus.ACTIVE) {
            throw new UnsupportedOperationException("Only ACTIVE status is supported currently");
        }
        final CompletableFuture<Void> future = activeFuture.get(PlatformStatus.ACTIVE);
        if (future == null) {
            throw new IllegalStateException("Node has not been started");
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> minLogsFuture(@NonNull final String pattern, final int n) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public CompletableFuture<Void> mfFuture(@NonNull final MarkerFile markerFile) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public CompletableFuture<Void> stopFuture() {
        return CompletableFuture.runAsync(node::killImmediately, executor);
    }
}
