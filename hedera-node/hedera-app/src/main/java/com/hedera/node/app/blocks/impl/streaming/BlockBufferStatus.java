package com.hedera.node.app.blocks.impl.streaming;

import java.time.Instant;

public record BlockBufferStatus(Instant timestamp, double saturationPercent, boolean isActionStage) {
}
