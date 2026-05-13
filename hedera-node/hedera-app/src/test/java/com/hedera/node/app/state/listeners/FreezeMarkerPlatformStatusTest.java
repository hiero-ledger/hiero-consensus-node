// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;

import org.junit.jupiter.api.Test;

class FreezeMarkerPlatformStatusTest {
    private final FreezeMarkerPlatformStatus subject = new FreezeMarkerPlatformStatus();

    @Test
    void completesFutureWhenPlatformReachesFreezeComplete() {
        final var freezeCompleteFuture = subject.freezeCompleteFuture();

        assertThat(freezeCompleteFuture).isNotDone();

        subject.update(FREEZE_COMPLETE);

        assertThat(freezeCompleteFuture).isCompleted();
        assertThat(subject.freezeCompleteFuture()).isCompleted();
    }

    @Test
    void resetsFutureWhenPlatformLeavesFreezeComplete() {
        subject.update(FREEZE_COMPLETE);
        final var completedFuture = subject.freezeCompleteFuture();

        subject.update(ACTIVE);
        final var nextFreezeCompleteFuture = subject.freezeCompleteFuture();

        assertThat(completedFuture).isCompleted();
        assertThat(nextFreezeCompleteFuture).isNotDone();
    }
}
