// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.STARTING_UP;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Tracks platform status changes needed to decide when it is safe to write the {@code now_frozen.mf} marker.
 */
@Singleton
public class FreezeMarkerPlatformStatus {
    private final Object lock = new Object();

    private PlatformStatus status = STARTING_UP;
    private CompletableFuture<Void> freezeCompleteFuture = new CompletableFuture<>();

    @Inject
    public FreezeMarkerPlatformStatus() {}

    /**
     * Updates the current platform status.
     *
     * @param status the latest platform status
     */
    public void update(@NonNull final PlatformStatus status) {
        requireNonNull(status);
        synchronized (lock) {
            this.status = status;
            if (status == FREEZE_COMPLETE) {
                freezeCompleteFuture.complete(null);
            } else if (freezeCompleteFuture.isDone()) {
                freezeCompleteFuture = new CompletableFuture<>();
            }
        }
    }

    /**
     * Returns a future that completes the next time the platform reaches {@link PlatformStatus#FREEZE_COMPLETE}, or a
     * completed future if the platform is already in that status.
     *
     * @return a future completed by {@link PlatformStatus#FREEZE_COMPLETE}
     */
    public @NonNull CompletableFuture<Void> freezeCompleteFuture() {
        synchronized (lock) {
            return status == FREEZE_COMPLETE ? CompletableFuture.completedFuture(null) : freezeCompleteFuture;
        }
    }
}
