// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceCommands;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.config.data.QuiescenceConfig;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * Test helper that constructs the {@link QuiescenceController}, {@link QuiescenceCommands} and
 * {@link QuiescedHeartbeat} instances required to build a {@link BlockRecordManagerImpl} in unit
 * tests that do not exercise quiescence behavior themselves. Quiescence is disabled, so the
 * activity supplier and {@code recordActivity} callback are never consulted by the controller.
 */
final class QuiescenceTestBase {

    private final QuiescenceController controller;
    private final QuiescenceCommands commands;
    private final QuiescedHeartbeat heartbeat;

    QuiescenceTestBase(@NonNull final Platform platform) {
        this.controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(5), Duration.ofSeconds(5)),
                InstantSource.system(),
                () -> 0,
                Instant::now,
                () -> {});
        this.commands = new QuiescenceCommands(platform);
        this.heartbeat = new QuiescedHeartbeat(controller, commands);
    }

    public QuiescenceController getController() {
        return controller;
    }

    public QuiescenceCommands getCommands() {
        return commands;
    }

    public QuiescedHeartbeat getHeartbeat() {
        return heartbeat;
    }
}
