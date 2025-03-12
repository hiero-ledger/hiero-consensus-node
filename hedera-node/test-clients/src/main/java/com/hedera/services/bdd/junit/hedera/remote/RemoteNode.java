// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.remote;

import com.hedera.services.bdd.junit.hedera.AbstractNode;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.MarkerFile;
import com.hedera.services.bdd.junit.hedera.NodeMetadata;
import com.hedera.services.bdd.junit.hedera.subprocess.NodeStatus;
import com.swirlds.platform.system.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class RemoteNode extends AbstractNode implements HederaNode {
    public RemoteNode(@NonNull final NodeMetadata metadata) {
        super(metadata);
    }

    @Override
    public Path getExternalPath(@NonNull final ExternalPath path) {
        throw new UnsupportedOperationException("There is no local path to a remote node's " + path);
    }

    @Override
    public HederaNode initWorkingDir(@NonNull final String configTxt) {
        throw new UnsupportedOperationException("Cannot initialize a remote node's working directory");
    }

    @Override
    public HederaNode start() {
        // No-op, remote nodes must already be running
        return this;
    }

    @Override
    public CompletableFuture<Void> mfFuture(@NonNull MarkerFile markerFile) {
        throw new UnsupportedOperationException("Cannot check marker files on a remote node");
    }

    @Override
    public CompletableFuture<Void> statusFuture(
            @NonNull final PlatformStatus status, @Nullable final Consumer<NodeStatus> nodeStatusObserver) {
        // (FUTURE) Implement this via Prometheus and gRPC if it turns out to be useful
        throw new UnsupportedOperationException("Cannot check the status of a remote node");
    }

    @Override
    public CompletableFuture<Void> stopFuture() {
        // (FUTURE) Implement this via Prometheus and gRPC if it turns out to be useful
        throw new UnsupportedOperationException("Cannot stop a remote node");
    }

    @Override
    public CompletableFuture<Void> logFuture(@NonNull final String pattern) {
        throw new UnsupportedOperationException("Cannot await log file creation in an embedded network");
    }
}
